// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/anonym239/shield/desktop/internal/dnsx"
	"github.com/anonym239/shield/desktop/internal/filter"
)

// Version of Shield for Windows (set by the build).
var Version = "dev"

// TestResult / Report: self-test (same test ids as on Android).
type TestResult struct {
	ID     string `json:"id"`
	Passed *bool  `json:"passed"`
	Detail string `json:"detail"`
}

type Report struct {
	Time    int64        `json:"time"`
	Results []TestResult `json:"results"`
}

func (r *Report) AllPassed() bool {
	n := 0
	for _, t := range r.Results {
		if t.Passed != nil {
			n++
			if !*t.Passed {
				return false
			}
		}
	}
	return n > 0
}

func boolp(b bool) *bool { return &b }

// Profile is the metadata of a WireGuard profile (config itself is DPAPI-encrypted).
type Profile struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Endpoint string `json:"endpoint"`
}

// Engine runs Shield for Windows.
type Engine struct {
	proton   protonWatch
	dir      string
	settings *settingsStore
	events   *eventLog
	filter   *filter.Engine
	lists    *lists
	res      *resolver

	mu        sync.Mutex
	phase     string
	errMsg    string
	startedAt int64
	cancel    context.CancelFunc
	wg        *wgTunnel
	wgName    string
	wgSince   int64
	report    *Report
	testing   bool
	heal      *HealPolicy
	ai        *AiVerdict
	aiRunning bool
	rules     []filter.Rule
	opMu      sync.Mutex // serialises start/stop
}

func NewEngine() *Engine {
	dir := DataDir()
	ev := newEventLog(dir)
	f := filter.NewEngine()
	e := &Engine{dir: dir, settings: loadSettings(dir), events: ev, filter: f, phase: "STOPPED", heal: NewHealPolicy(3)}
	e.lists = newLists(dir, f, ev)
	e.lists.current = e.settings.get
	e.res = newResolver(f, ev, dir)
	e.loadRules()
	if b, err := os.ReadFile(filepath.Join(dir, "ai.json")); err == nil {
		var v AiVerdict
		if json.Unmarshal(b, &v) == nil {
			e.ai = &v
		}
	}
	return e
}

// Init loads the lists and restores the previous state (protection on/off).
func (e *Engine) Init() {
	s := e.settings.get()
	e.res.noHistory.Store(s.NoHistory)
	e.lists.reload(s)
	if s.Enabled {
		if err := e.Start(); err != nil {
			logf("Start beim Hochfahren fehlgeschlagen: %v", err)
		}
	} else if _, err := os.Stat(backupPath(e.dir)); err == nil {
		// crashed while protection was on: give the user their DNS back
		_ = RestoreSystemDNS(e.dir)
		e.events.add("TUNNEL_ERROR", "WARNING", "DNS-Einstellungen nach unerwartetem Ende wiederhergestellt")
	}
	if e.lists.missing(s) {
		go e.lists.update(s, false)
	}
}

func backupPath(dir string) string { return filepath.Join(dir, "dns-backup.json") }

func (e *Engine) loadRules() {
	b, err := os.ReadFile(filepath.Join(e.dir, "rules.json"))
	if err == nil {
		_ = json.Unmarshal(b, &e.rules)
	}
	e.filter.SetRules(e.rules)
}

func (e *Engine) saveRules() {
	b, _ := json.Marshal(e.rules)
	_ = os.WriteFile(filepath.Join(e.dir, "rules.json"), b, 0o600)
	e.filter.SetRules(e.rules)
	e.res.cache.Clear()
}

// SetRule adds/replaces an own rule.
func (e *Engine) SetRule(pattern string, allow bool) error {
	p := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(pattern)), ".")
	if !filter.ValidPattern(p) {
		return errors.New("ungültige Domain oder Regel")
	}
	e.mu.Lock()
	out := e.rules[:0]
	for _, r := range e.rules {
		if r.Pattern != p {
			out = append(out, r)
		}
	}
	e.rules = append(out, filter.Rule{Pattern: p, Allow: allow})
	e.saveRules()
	e.mu.Unlock()
	verb := "Blockiert"
	if allow {
		verb = "Erlaubt"
	}
	e.events.add("CUSTOM_RULE_CHANGED", "INFO", "%s: %s", verb, p)
	return nil
}

func (e *Engine) RemoveRule(pattern string) {
	e.mu.Lock()
	out := e.rules[:0]
	for _, r := range e.rules {
		if r.Pattern != pattern {
			out = append(out, r)
		}
	}
	e.rules = out
	e.saveRules()
	e.mu.Unlock()
	e.events.add("CUSTOM_RULE_CHANGED", "INFO", "Regel entfernt: %s", pattern)
}

func (e *Engine) Rules() []filter.Rule {
	e.mu.Lock()
	defer e.mu.Unlock()
	return append([]filter.Rule(nil), e.rules...)
}

// ---- lifecycle ----

func (e *Engine) setPhase(p, msg string) {
	e.mu.Lock()
	e.phase, e.errMsg = p, msg
	e.mu.Unlock()
}

// Start turns protection on: local DNS filter, system DNS, optional WireGuard.
func (e *Engine) Start() error {
	e.opMu.Lock()
	defer e.opMu.Unlock()
	e.stopLocked(false)
	if !isAdmin() {
		e.setPhase("ERROR", "Shield braucht Administratorrechte")
		return errors.New("Shield braucht Administratorrechte")
	}
	e.setPhase("STARTING", "")
	s := e.settings.get()
	url, boot, _ := s.DoH()
	up, err := newDoH(url, boot)
	if err != nil {
		e.fail(err)
		return err
	}
	var fb *doh
	if f := s.Fallback(); f != nil {
		fb, _ = newDoH(f.URL, f.Bootstrap)
	}
	e.res.setUpstream(up, fb)
	ctx, cancel := context.WithCancel(context.Background())
	if err := e.res.listen(ctx, []string{"127.0.0.1:53", "[::1]:53"}); err != nil {
		cancel()
		e.fail(err)
		return err
	}
	if s.ActiveProfile != "" {
		if err := e.startVPN(s); err != nil {
			e.events.add("VPN_DISCONNECTED", "CRITICAL", "VPN konnte nicht starten: %v", err)
			// fail closed when the kill switch is on
			if s.KillSwitch {
				cancel()
				e.res.close()
				e.fail(err)
				return err
			}
		}
	}
	var dnsErr error
	for i := 0; i < 3; i++ { // PowerShell/WMI can be briefly unavailable
		if _, dnsErr = applySystemDNS(e.dir); dnsErr == nil {
			break
		}
		time.Sleep(time.Second)
	}
	if err := dnsErr; err != nil {
		cancel()
		e.closeVPN()
		e.res.close()
		e.fail(fmt.Errorf("System-DNS konnte nicht gesetzt werden: %w", err))
		return err
	}
	e.mu.Lock()
	e.cancel = cancel
	e.phase = "RUNNING"
	e.errMsg = ""
	e.startedAt = time.Now().UnixMilli()
	e.report = nil
	e.mu.Unlock()
	e.settings.update(func(x Settings) Settings { x.Enabled = true; return x })
	e.events.add("PROTECTION_ON", "INFO", "Schutz eingeschaltet (%s)", strings.ToLower(string(s.Level)))
	go e.loop(ctx)
	go func() {
		time.Sleep(2500 * time.Millisecond)
		e.waitLists(90 * time.Second)
		e.Heal(e.SelfTest(false))
	}()
	return nil
}

func (e *Engine) fail(err error) {
	e.setPhase("ERROR", err.Error())
	e.events.add("TUNNEL_ERROR", "CRITICAL", "%v", err)
}

func (e *Engine) waitLists(max time.Duration) {
	deadline := time.Now().Add(max)
	for time.Now().Before(deadline) {
		_, _, _, updating := e.lists.status(e.settings.get())
		if !updating {
			return
		}
		time.Sleep(time.Second)
	}
}

// Stop turns protection off and restores the system DNS.
func (e *Engine) Stop() {
	e.opMu.Lock()
	defer e.opMu.Unlock()
	e.stopLocked(true)
}

func (e *Engine) stopLocked(user bool) {
	e.mu.Lock()
	cancel := e.cancel
	e.cancel = nil
	running := e.phase == "RUNNING" || e.phase == "STARTING"
	e.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	e.closeVPN()
	e.res.close()
	if running || user {
		if err := RestoreSystemDNS(e.dir); err != nil {
			e.events.add("TUNNEL_ERROR", "WARNING", "DNS-Wiederherstellung: %v", err)
		}
	}
	if user {
		e.settings.update(func(x Settings) Settings { x.Enabled = false; return x })
		e.events.add("PROTECTION_OFF", "WARNING", "Schutz ausgeschaltet")
	}
	e.setPhase("STOPPED", "")
}

// Shutdown is called when the app exits: keep "enabled" so autostart resumes.
func (e *Engine) Shutdown() {
	e.opMu.Lock()
	defer e.opMu.Unlock()
	e.stopLocked(false)
}

func (e *Engine) Restart() error {
	e.events.add("SETTINGS_CHANGED", "INFO", "Tunnel wird neu gestartet")
	return e.Start()
}

func (e *Engine) loop(ctx context.Context) {
	tk := time.NewTicker(30 * time.Second)
	defer tk.Stop()
	var lastTest, lastStats, lastLists, lastAI time.Time = time.Now(), time.Now(), time.Now(), time.Time{}
	wasConnected := false
	for {
		select {
		case <-ctx.Done():
			return
		case <-tk.C:
		}
		// new adapters (hotspot, LAN) also get Shield as DNS
		if n, err := applySystemDNS(e.dir); err == nil && n > 0 {
			e.events.add("NETWORK_CHANGED", "INFO", "Neuer Netzwerkadapter: DNS auf Shield gesetzt")
		}
		e.mu.Lock()
		wg := e.wg
		e.mu.Unlock()
		if wg != nil {
			age := wg.handshakeAge()
			connected := age >= 0 && age < 180
			if connected != wasConnected {
				if connected {
					e.mu.Lock()
					e.wgSince = time.Now().UnixMilli()
					e.mu.Unlock()
					e.events.add("VPN_CONNECTED", "INFO", "WireGuard verbunden (%s)", e.wgName)
				} else {
					e.events.add("VPN_DISCONNECTED", "WARNING", "WireGuard getrennt (%s)", e.wgName)
				}
				wasConnected = connected
			}
		}
		if time.Since(lastStats) > 5*time.Minute {
			e.res.saveStats()
			lastStats = time.Now()
		}
		if time.Since(lastTest) > 15*time.Minute {
			lastTest = time.Now()
			go e.Heal(e.SelfTest(false))
		}
		if time.Since(lastLists) > 24*time.Hour {
			lastLists = time.Now()
			go e.lists.update(e.settings.get(), false)
		}
		s := e.settings.get()
		if s.AIEnabled && e.HasAIKey() && time.Since(lastAI) > 24*time.Hour {
			lastAI = time.Now()
			go func() { _, _ = e.AICheck() }()
		}
	}
}

// ---- VPN ----

func (e *Engine) profilesPath() string { return filepath.Join(e.dir, "profiles.json") }

func (e *Engine) Profiles() []Profile {
	var ps []Profile
	if b, err := os.ReadFile(e.profilesPath()); err == nil {
		_ = json.Unmarshal(b, &ps)
	}
	return ps
}

func (e *Engine) ImportProfile(name, conf string) (Profile, error) {
	c, err := ParseWgConfig(conf)
	if err != nil {
		return Profile{}, err
	}
	enc, err := protect([]byte(conf))
	if err != nil {
		return Profile{}, err
	}
	idb := make([]byte, 8)
	_, _ = rand.Read(idb)
	p := Profile{ID: hex.EncodeToString(idb), Name: strings.TrimSpace(name), Endpoint: c.Peers[0].Endpoint}
	if p.Name == "" {
		p.Name = p.Endpoint
	}
	if err := os.WriteFile(filepath.Join(e.dir, "wg_"+p.ID+".bin"), enc, 0o600); err != nil {
		return Profile{}, err
	}
	ps := append(e.Profiles(), p)
	b, _ := json.Marshal(ps)
	_ = os.WriteFile(e.profilesPath(), b, 0o600)
	e.events.add("SETTINGS_CHANGED", "INFO", "WireGuard-Profil importiert: %s", p.Name)
	if e.settings.get().ActiveProfile == "" {
		e.settings.update(func(s Settings) Settings { s.ActiveProfile = p.ID; return s })
		if e.Running() {
			go e.Restart()
		}
	}
	return p, nil
}

func (e *Engine) DeleteProfile(id string) {
	var out []Profile
	for _, p := range e.Profiles() {
		if p.ID != id {
			out = append(out, p)
		}
	}
	b, _ := json.Marshal(out)
	_ = os.WriteFile(e.profilesPath(), b, 0o600)
	_ = os.Remove(filepath.Join(e.dir, "wg_"+id+".bin"))
	if e.settings.get().ActiveProfile == id {
		e.settings.update(func(s Settings) Settings { s.ActiveProfile = ""; return s })
		if e.Running() {
			go e.Restart()
		}
	}
	e.events.add("SETTINGS_CHANGED", "INFO", "WireGuard-Profil gelöscht")
}

func (e *Engine) startVPN(s Settings) error {
	enc, err := os.ReadFile(filepath.Join(e.dir, "wg_"+s.ActiveProfile+".bin"))
	if err != nil {
		return errors.New("Profil nicht gefunden")
	}
	plain, err := unprotect(enc)
	if err != nil {
		return errors.New("Profil kann nicht entschlüsselt werden")
	}
	c, err := ParseWgConfig(string(plain))
	if err != nil {
		return err
	}
	// The endpoint is resolved via DoH before the tunnel is up (never plain DNS).
	uapi, err := c.UAPI(func(host string) (string, error) { return e.resolveViaDoH(host) })
	if err != nil {
		return err
	}
	w, err := startWG(c, uapi, s.KillSwitch)
	if err != nil {
		return err
	}
	name := ""
	for _, p := range e.Profiles() {
		if p.ID == s.ActiveProfile {
			name = p.Name
		}
	}
	e.mu.Lock()
	e.wg, e.wgName, e.wgSince = w, name, 0
	e.mu.Unlock()
	e.events.add("VPN_CONNECTED", "INFO", "WireGuard-Tunnel gestartet (%s)", name)
	return nil
}

func (e *Engine) resolveViaDoH(host string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	addrs, err := (&net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "udp", "127.0.0.1:53")
	}}).LookupHost(ctx, host)
	if err != nil || len(addrs) == 0 {
		return "", fmt.Errorf("nicht auflösbar")
	}
	return addrs[0], nil
}

func (e *Engine) closeVPN() {
	e.mu.Lock()
	w := e.wg
	e.wg = nil
	e.mu.Unlock()
	if w != nil {
		w.close()
	}
}

// ---- self-test & healing ----

func (e *Engine) Running() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.phase == "RUNNING"
}

// SelfTest checks every layer (SPEC §6, Windows variant).
func (e *Engine) SelfTest(full bool) *Report {
	e.mu.Lock()
	e.testing = true
	e.mu.Unlock()
	defer func() { e.mu.Lock(); e.testing = false; e.mu.Unlock() }()
	r := &Report{Time: time.Now().UnixMilli()}
	add := func(id string, ok *bool, detail string) {
		r.Results = append(r.Results, TestResult{id, ok, detail})
		if ok != nil {
			sev, typ := "INFO", "SELFTEST_PASSED"
			if !*ok {
				sev, typ = "WARNING", "SELFTEST_FAILED"
			}
			e.events.add(typ, sev, "Test %s: %s", id, detail)
		}
	}
	running := e.Running()
	// 1 local filter answers
	if !running {
		add("TUNNEL", boolp(false), "Schutz ist aus")
	} else {
		if err := directQuery(filter.SelftestDomain); err != nil {
			add("TUNNEL", boolp(false), "Lokaler DNS-Filter antwortet nicht: "+err.Error())
		} else {
			add("TUNNEL", boolp(true), "Lokaler DNS-Filter läuft (127.0.0.1:53)")
		}
	}
	// 2 system DNS
	ok, detail := systemDNSPointsToShield()
	add("SYSTEM_DNS", boolp(running && ok), detail)
	// 3 test domain through the real system resolver
	if running {
		start := time.Now().UnixMilli()
		name := fmt.Sprintf("t%d.%s", time.Now().UnixNano()%1000000, filter.SelftestDomain)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		addrs, _ := net.DefaultResolver.LookupHost(ctx, name)
		cancel()
		blocked := true
		for _, a := range addrs {
			if a != "0.0.0.0" && a != "::" {
				blocked = false
			}
		}
		seen := e.res.selftestHit.Load() >= start
		msg := "Test-Domain wurde blockiert"
		if !(seen && blocked) {
			msg = "Test-Domain wurde nicht von Shield beantwortet"
		}
		add("TEST_DOMAIN", boolp(seen && blocked), msg)
	} else {
		add("TEST_DOMAIN", boolp(false), "Schutz ist aus")
	}
	// 4 encrypted upstream
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	ms, err := e.res.probe(ctx)
	cancel()
	if err != nil {
		add("UPSTREAM", boolp(false), "DoH nicht erreichbar: "+err.Error())
	} else {
		add("UPSTREAM", boolp(true), fmt.Sprintf("DoH erreichbar · %d ms", ms))
	}
	// 5 lists
	_, total, oldest, _ := e.lists.status(e.settings.get())
	age := int64(-1)
	if oldest > 0 {
		age = (time.Now().UnixMilli() - oldest) / 3600000
	}
	switch {
	case total == 0:
		add("LISTS", boolp(false), "Keine Blocklisten geladen")
	case age < 0 || age >= 48:
		add("LISTS", boolp(false), fmt.Sprintf("Listen veraltet (%d h)", age))
	default:
		add("LISTS", boolp(true), fmt.Sprintf("%d Regeln · %d h alt", total, age))
	}
	// 6 VPN
	e.mu.Lock()
	wg := e.wg
	e.mu.Unlock()
	if e.settings.get().ActiveProfile == "" {
		add("VPN", nil, "Kein VPN-Profil aktiv")
	} else if wg == nil {
		add("VPN", boolp(false), "VPN-Tunnel läuft nicht")
	} else if a := wg.handshakeAge(); a < 0 || a > 180 {
		add("VPN", boolp(false), "Kein WireGuard-Handshake in den letzten 3 Min")
	} else {
		add("VPN", boolp(true), fmt.Sprintf("Handshake vor %d s", a))
	}
	e.mu.Lock()
	e.report = r
	e.mu.Unlock()
	return r
}

// Heal repairs what the self-test found broken (max. 3x per hour).
func (e *Engine) Heal(r *Report) {
	if r == nil || r.AllPassed() {
		e.heal.Reset()
		return
	}
	s := e.settings.get()
	if !s.AutoHeal || !s.Enabled {
		return
	}
	var failed []string
	for _, t := range r.Results {
		if t.Passed != nil && !*t.Passed {
			failed = append(failed, t.ID)
		}
	}
	action := HealActionFor(failed)
	if action == "" {
		return
	}
	if !e.heal.Allow(time.Now()) {
		e.events.add("SELFTEST_FAILED", "CRITICAL", "Selbstheilung pausiert: Problem besteht weiter (%s)", strings.Join(failed, ", "))
		return
	}
	if action == "UPDATE_LISTS" {
		e.events.add("SELFTEST_FAILED", "WARNING", "Selbstheilung: Blocklisten werden neu geladen")
		e.lists.update(s, true)
	} else {
		e.events.add("SELFTEST_FAILED", "WARNING", "Selbstheilung: Schutz wird neu gestartet (%s)", strings.Join(failed, ", "))
		if err := e.Start(); err != nil {
			return
		}
		time.Sleep(3 * time.Second)
	}
	e.Heal(e.SelfTest(false))
}

// ---- settings helpers ----

func (e *Engine) Settings() Settings { return e.settings.get() }

// UpdateSettings applies a change and restarts what is affected.
func (e *Engine) UpdateSettings(fn func(Settings) Settings) Settings {
	before := e.settings.get()
	after := e.settings.update(fn)
	e.res.noHistory.Store(after.NoHistory)
	e.events.add("SETTINGS_CHANGED", "INFO", "Einstellungen geändert")
	listsChanged := strings.Join(before.Lists, ",") != strings.Join(after.Lists, ",") || before.Level != after.Level
	if listsChanged {
		e.lists.reload(after)
		go e.lists.update(after, false)
	}
	if before.Autostart != after.Autostart {
		if err := setAutostart(after.Autostart); err != nil {
			e.events.add("ERROR", "WARNING", "Autostart: %v", err)
		}
	}
	tunnelChanged := before.Upstream != after.Upstream || before.CustomDoH != after.CustomDoH ||
		before.CustomBootstrap != after.CustomBootstrap || before.ActiveProfile != after.ActiveProfile || before.KillSwitch != after.KillSwitch
	if tunnelChanged && e.Running() {
		go e.Restart()
	}
	return after
}

func (e *Engine) SetPanic(on bool) {
	e.res.panic.Store(on)
	if on {
		e.events.add("PANIC_ON", "CRITICAL", "Panik-Knopf: alle DNS-Anfragen werden verworfen")
	} else {
		e.events.add("PANIC_OFF", "INFO", "Panik-Modus beendet")
	}
}

// directQuery asks the local filter on 127.0.0.1:53 and expects any DNS answer.
func directQuery(name string) error {
	c, err := net.DialTimeout("udp", "127.0.0.1:53", 2*time.Second)
	if err != nil {
		return err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Write(dnsx.BuildQuery(0x5151, name, dnsx.TypeA)); err != nil {
		return err
	}
	buf := make([]byte, 1500)
	n, err := c.Read(buf)
	if err != nil {
		return err
	}
	if n < 12 || buf[0] != 0x51 || buf[1] != 0x51 {
		return errors.New("ungültige Antwort")
	}
	return nil
}

func newToken() string {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
