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

// Source is a subscribable list.
type Source struct {
	ID       string          `json:"id"`
	Name     string          `json:"name"`
	URL      string          `json:"-"`
	Category filter.Category `json:"category"`
}

var Sources = []Source{
	{"adguard_dns", "AdGuard DNS Filter", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", filter.Ads},
	{"easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", filter.Tracking},
	{"urlhaus", "URLhaus Malware", "https://urlhaus.abuse.ch/downloads/hostfile/", filter.Malware},
	{"phishing_army", "Phishing Army", "https://phishing.army/download/phishing_army_blocklist.txt", filter.Phishing},
	{"phishing_filter", "Phishing Filter (malware-filter)", "https://malware-filter.gitlab.io/malware-filter/phishing-filter-agh.txt", filter.Phishing},
	{"builtin_telemetry", "Shield Telemetrie-Liste (eingebaut)", "builtin", filter.Tracking},
	{"hagezi_pro", "HaGeZi Pro", hagezi + "/pro.txt", filter.Tracking},
	{"hagezi_pro_plus", "HaGeZi Pro++", hagezi + "/pro.plus.txt", filter.Tracking},
	{"hagezi_tif", "HaGeZi Threat Intelligence (Medium)", hagezi + "/tif.medium.txt", filter.Malware},
	{"hagezi_doh", "HaGeZi DNS-Umgehung (DoH/DoT-Server)", hagezi + "/doh.txt", filter.Malware},
	{"hagezi_native_winoffice", "HaGeZi Windows/Office-Telemetrie", hagezi + "/native.winoffice.txt", filter.Tracking},
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
			Parsed: filter.Parse(r, aggressive && src.ID == "easyprivacy")})
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
	req, _ := http.NewRequest(http.MethodGet, src.URL, nil)
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
	if len(filter.Parse(bytes.NewReader(body), true).Blocked) == 0 {
		return fmt.Errorf("Liste ist leer")
	}
	if err := os.WriteFile(filepath.Join(l.dir, src.ID+".txt"), body, 0o600); err != nil {
		return err
	}
	l.meta[src.ID] = listMeta{UpdatedAt: time.Now().UnixMilli(), ETag: resp.Header.Get("ETag")}
	return nil
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
