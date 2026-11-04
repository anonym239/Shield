// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/anonym239/shield/desktop/internal/filter"
)

//go:embed telemetry.txt
var builtinTelemetry []byte

const hagezi = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock"

const ubo = "https://ublockorigin.github.io/uAssets/filters"
const uboMirror = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters"

// Source is a subscribable list.
type Source struct {
	ID       string          `json:"id"`
	Name     string          `json:"name"`
	URL      string          `json:"-"`
	Category filter.Category `json:"category"`
	ABP      bool            `json:"-"` // adblock syntax (uBlock Origin, EasyList)
	Mirror   string          `json:"-"` // same content, tried if URL fails
}

// aggressiveIDs: lists whose $third-party rules are taken from level STRICT on.
var aggressiveIDs = map[string]bool{
	"easyprivacy": true, "easylist": true, "easylist_germany": true, "ublock_filters": true, "ublock_badware": true,
	"ublock_privacy": true, "ublock_quick_fixes": true, "ublock_annoyances": true, "ublock_cookies": true,
}

var Sources = []Source{
	{"adguard_dns", "AdGuard DNS Filter", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", filter.Ads, false, ""},
	{"easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", filter.Tracking, true, ""},
	// the old hostfile URL answers HTTP 410; this is the feed uBlock Origin uses
	{"urlhaus", "URLhaus Malware (Online-URLs)", "https://malware-filter.gitlab.io/urlhaus-filter/urlhaus-filter-agh-online.txt", filter.Malware, false, ""},
	{"phishing_army", "Phishing Army", "https://phishing.army/download/phishing_army_blocklist.txt", filter.Phishing, false, ""},
	{"phishing_filter", "Phishing Filter (malware-filter)", "https://malware-filter.gitlab.io/malware-filter/phishing-filter-agh.txt", filter.Phishing, false, ""},
	// uBlock Origin default lists – DNS-usable rules only
	{"ublock_filters", "uBlock Origin – Werbung", ubo + "/filters.txt", filter.Ads, true, uboMirror + "/filters.txt"},
	{"ublock_badware", "uBlock Origin – Gefährliche Seiten", ubo + "/badware.txt", filter.Malware, true, uboMirror + "/badware.txt"},
	{"ublock_privacy", "uBlock Origin – Privatsphäre", ubo + "/privacy.txt", filter.Tracking, true, uboMirror + "/privacy.txt"},
	{"ublock_quick_fixes", "uBlock Origin – Schnellkorrekturen", ubo + "/quick-fixes.txt", filter.Ads, true, uboMirror + "/quick-fixes.txt"},
	{"ublock_unbreak", "uBlock Origin – Reparaturen (Ausnahmen)", ubo + "/unbreak.txt", filter.Ads, true, uboMirror + "/unbreak.txt"},
	{"easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", filter.Ads, true, ""},
	{"easylist_germany", "EasyList Germany", "https://easylist.to/easylistgermany/easylistgermany.txt", filter.Ads, true, ""},
	{"peter_lowe", "Peter Lowe – Werbe- und Tracking-Server", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", filter.Ads, false, ""},
	{"ublock_annoyances", "uBlock Origin – Störelemente", ubo + "/annoyances.txt", filter.Ads, true, uboMirror + "/annoyances.txt"},
	{"ublock_cookies", "uBlock Origin – Cookie-Hinweise", ubo + "/annoyances-cookies.txt", filter.Ads, true, uboMirror + "/annoyances-cookies.txt"},
	{"builtin_telemetry", "Shield Telemetrie-Liste (eingebaut)", "builtin", filter.Tracking, false, ""},
	{"hagezi_pro", "HaGeZi Pro", hagezi + "/pro.txt", filter.Tracking, false, ""},
	{"hagezi_pro_plus", "HaGeZi Pro++", hagezi + "/pro.plus.txt", filter.Tracking, false, ""},
	{"hagezi_tif", "HaGeZi Threat Intelligence (Medium)", hagezi + "/tif.medium.txt", filter.Malware, false, ""},
	{"hagezi_doh", "HaGeZi DNS-Umgehung (DoH/DoT-Server)", hagezi + "/doh.txt", filter.Malware, false, ""},
	{"hagezi_native_winoffice", "HaGeZi Windows/Office-Telemetrie", hagezi + "/native.winoffice.txt", filter.Tracking, false, ""},
}

// ListStatus is shown in the UI.
type ListStatus struct {
	Source
	Enabled   bool   `json:"enabled"`
	Rules     int    `json:"rules"`
	UpdatedAt int64  `json:"updatedAt"`
	Error     string `json:"error,omitempty"`
}

type listMeta struct {
	UpdatedAt int64  `json:"updatedAt"`
	ETag      string `json:"etag"`
	Error     string `json:"error,omitempty"`
}

type lists struct {
	mu       sync.Mutex
	dir      string
	meta     map[string]listMeta
	engine   *filter.Engine
	events   *eventLog
	updating bool
	pending  bool // another update was requested while one was running
	http     *http.Client
	current  func() Settings
}

func newLists(dir string, engine *filter.Engine, ev *eventLog) *lists {
	l := &lists{dir: filepath.Join(dir, "lists"), meta: map[string]listMeta{}, engine: engine, events: ev,
		http: &http.Client{Timeout: 120 * time.Second}}
	_ = os.MkdirAll(l.dir, 0o700)
	if b, err := os.ReadFile(filepath.Join(l.dir, "meta.json")); err == nil {
		_ = json.Unmarshal(b, &l.meta)
	}
	return l
}

func (l *lists) saveMeta() {
	b, _ := json.Marshal(l.meta)
	_ = os.WriteFile(filepath.Join(l.dir, "meta.json"), b, 0o600)
}

func contains(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}

// reload parses the stored lists into the engine (no network).
func (l *lists) reload(s Settings) {
	var loaded []filter.List
	aggressive := s.Level != Standard
	for _, src := range Sources {
		if !contains(s.Lists, src.ID) {
			continue
		}
		var r io.Reader
		if src.URL == "builtin" {
			r = bytes.NewReader(builtinTelemetry)
		} else {
			f, err := os.Open(filepath.Join(l.dir, src.ID+".txt"))
			if err != nil {
				continue
			}
			defer f.Close()
			r = f
		}
		loaded = append(loaded, filter.List{ID: src.ID, Name: src.Name, Category: src.Category,
			Parsed: filter.ParseList(r, aggressive && aggressiveIDs[src.ID], src.ABP)})
	}
	l.engine.SetLists(loaded)
	logf("Lists: %d geladen, %d Regeln", len(loaded), l.engine.RuleCount())
}

// update downloads all enabled lists (If-None-Match) and reloads. A request
// during a running update is queued, so a level change is never lost.
func (l *lists) update(s Settings, force bool) bool {
	l.mu.Lock()
	if l.updating {
		l.pending = true
		l.mu.Unlock()
		return false
	}
	l.updating = true
	l.mu.Unlock()
	ok := l.updateOnce(s, force)
	for {
		l.mu.Lock()
		again := l.pending
		l.pending = false
		if !again {
			l.updating = false
			l.mu.Unlock()
			return ok
		}
		l.mu.Unlock()
		ok = l.updateOnce(l.settingsNow(s), force)
	}
}

func (l *lists) settingsNow(fallback Settings) Settings {
	if l.current != nil {
		return l.current()
	}
	return fallback
}

func (l *lists) updateOnce(s Settings, force bool) bool {
	ok := true
	for _, src := range Sources {
		if !contains(s.Lists, src.ID) || src.URL == "builtin" {
			continue
		}
		if err := l.download(src, force); err != nil {
			ok = false
			m := l.meta[src.ID]
			m.Error = err.Error()
			l.meta[src.ID] = m
			l.events.add("LISTS_UPDATE_FAILED", "WARNING", "%s: %v", src.Name, err)
		}
	}
	l.saveMeta()
	l.reload(l.settingsNow(s)) // the selection may have changed during the download
	if ok {
		l.events.add("LISTS_UPDATED", "INFO", "Blocklisten aktualisiert: %d Regeln", l.engine.RuleCount())
	}
	return ok
}

func (l *lists) download(src Source, force bool) error {
	l.events.add("NETWORK_ACCESS", "INFO", "Blockliste laden: %s", src.Name)
	err := l.downloadFrom(src, src.URL, force)
	if err != nil && src.Mirror != "" {
		logf("Lists: %s via %s failed (%v), trying mirror", src.ID, src.URL, err)
		err = l.downloadFrom(src, src.Mirror, force)
	}
	return err
}

func (l *lists) downloadFrom(src Source, url string, force bool) error {
	req, _ := http.NewRequest(http.MethodGet, url, nil)
	m := l.meta[src.ID]
	if !force && m.ETag != "" {
		req.Header.Set("If-None-Match", m.ETag)
	}
	resp, err := l.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotModified {
		m.UpdatedAt, m.Error = time.Now().UnixMilli(), ""
		l.meta[src.ID] = m
		return nil
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return err
	}
	includes := filter.FindIncludes(body)
	for _, name := range includes {
		part, err := l.fetchInclude(url, name)
		if err != nil {
			return err
		}
		body = append(append(body, '\n'), part...)
	}
	p := filter.ParseList(bytes.NewReader(body), true, src.ABP)
	if len(p.Blocked) == 0 && len(p.Allowed) == 0 {
		return fmt.Errorf("Liste ist leer")
	}
	if err := os.WriteFile(filepath.Join(l.dir, src.ID+".txt"), body, 0o600); err != nil {
		return err
	}
	etag := resp.Header.Get("ETag")
	if len(includes) > 0 {
		etag = "" // parts may change without the main file changing
	}
	l.meta[src.ID] = listMeta{UpdatedAt: time.Now().UnixMilli(), ETag: etag}
	return nil
}

// fetchInclude downloads one "!#include" part of a uBlock Origin list (same host only).
func (l *lists) fetchInclude(base, name string) ([]byte, error) {
	u := filter.ResolveInclude(base, name)
	if u == "" {
		return nil, fmt.Errorf("unzulässiger Listenteil: %s", name)
	}
	resp, err := l.http.Get(u)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Listenteil %s: HTTP %d", name, resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 32<<20))
}

// missing reports whether an enabled list has never been downloaded.
func (l *lists) missing(s Settings) bool {
	for _, src := range Sources {
		if contains(s.Lists, src.ID) && src.URL != "builtin" {
			if _, err := os.Stat(filepath.Join(l.dir, src.ID+".txt")); err != nil {
				return true
			}
		}
	}
	return false
}

func (l *lists) status(s Settings) (out []ListStatus, total int, oldest int64, updating bool) {
	counts := l.engine.ListCounts()
	l.mu.Lock()
	updating = l.updating
	l.mu.Unlock()
	for _, src := range Sources {
		m := l.meta[src.ID]
		st := ListStatus{Source: src, Enabled: contains(s.Lists, src.ID), Rules: counts[src.ID], UpdatedAt: m.UpdatedAt, Error: m.Error}
		if src.URL == "builtin" {
			st.UpdatedAt = time.Now().UnixMilli()
		}
		if st.Enabled && st.Rules > 0 && (oldest == 0 || st.UpdatedAt < oldest) {
			oldest = st.UpdatedAt
		}
		out = append(out, st)
	}
	return out, l.engine.RuleCount(), oldest, updating
}
