// Package app is the engine of Shield for Windows.
//
// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// Level presets – same as on Android.
type Level string

const (
	Standard Level = "STANDARD"
	Strict   Level = "STRICT"
	Maximum  Level = "MAXIMUM"
)

var levelLists = map[Level][]string{
	Standard: {"adguard_dns", "easyprivacy", "urlhaus", "phishing_army"},
	Strict:   {"adguard_dns", "easyprivacy", "urlhaus", "phishing_army", "builtin_telemetry", "hagezi_pro"},
	Maximum: {"adguard_dns", "easyprivacy", "urlhaus", "phishing_army", "phishing_filter", "builtin_telemetry",
		"hagezi_pro_plus", "hagezi_tif", "hagezi_doh", "hagezi_native_winoffice"},
}

// Upstream is a DNS-over-HTTPS server with bootstrap IPs.
type Upstream struct {
	ID, Label, URL string
	Bootstrap      []string
}

var Upstreams = []Upstream{
	{"QUAD9", "Quad9", "https://dns.quad9.net/dns-query", []string{"9.9.9.9", "149.112.112.112"}},
	{"CLOUDFLARE", "Cloudflare", "https://cloudflare-dns.com/dns-query", []string{"1.1.1.1", "1.0.0.1"}},
	{"MULLVAD", "Mullvad", "https://dns.mullvad.net/dns-query", []string{"194.242.2.2"}},
	{"ADGUARD", "AdGuard", "https://dns.adguard-dns.com/dns-query", []string{"94.140.14.14", "94.140.15.15"}},
}

// Settings are stored in %ProgramData%\Shield\settings.json (no secrets).
type Settings struct {
	Enabled         bool     `json:"enabled"`
	Level           Level    `json:"level"`
	Upstream        string   `json:"upstream"`
	CustomDoH       string   `json:"customDoh"`
	CustomBootstrap string   `json:"customBootstrap"`
	Lists           []string `json:"lists"`
	AutoHeal        bool     `json:"autoHeal"`
	KillSwitch      bool     `json:"killSwitch"`
	ActiveProfile   string   `json:"activeProfile"`
	NoHistory       bool     `json:"noHistory"`
	AIEnabled       bool     `json:"aiEnabled"`
	AIModel         string   `json:"aiModel"`
	Autostart       bool     `json:"autostart"`
}

func DefaultSettings() Settings {
	return Settings{Level: Standard, Upstream: "QUAD9", Lists: levelLists[Standard], AutoHeal: true, KillSwitch: true, AIModel: "gemini-flash-latest"}
}

// ApplyLevel sets the list preset (and the kill switch for Maximum).
func (s Settings) ApplyLevel(l Level) Settings {
	if _, ok := levelLists[l]; !ok {
		l = Standard
	}
	s.Level = l
	s.Lists = append([]string(nil), levelLists[l]...)
	if l == Maximum {
		s.KillSwitch = true
	}
	return s
}

func (s Settings) DoH() (string, []string, string) {
	if s.Upstream == "CUSTOM" && strings.HasPrefix(s.CustomDoH, "https://") {
		var b []string
		for _, x := range strings.FieldsFunc(s.CustomBootstrap, func(r rune) bool { return r == ',' || r == ' ' || r == ';' }) {
			b = append(b, x)
		}
		return s.CustomDoH, b, s.CustomDoH
	}
	for _, u := range Upstreams {
		if u.ID == s.Upstream {
			return u.URL, u.Bootstrap, u.Label
		}
	}
	return Upstreams[0].URL, Upstreams[0].Bootstrap, Upstreams[0].Label
}

// DataDir is %ProgramData%\Shield (machine wide, the engine runs elevated).
func DataDir() string {
	base := os.Getenv("ProgramData")
	if base == "" {
		base, _ = os.UserConfigDir()
	}
	d := filepath.Join(base, "Shield")
	_ = os.MkdirAll(d, 0o700)
	return d
}

type settingsStore struct {
	mu   sync.Mutex
	path string
	s    Settings
}

func loadSettings(dir string) *settingsStore {
	st := &settingsStore{path: filepath.Join(dir, "settings.json"), s: DefaultSettings()}
	if b, err := os.ReadFile(st.path); err == nil {
		_ = json.Unmarshal(b, &st.s)
	}
	if st.s.AIModel == "" {
		st.s.AIModel = "gemini-flash-latest"
	}
	return st
}

func (st *settingsStore) get() Settings {
	st.mu.Lock()
	defer st.mu.Unlock()
	s := st.s
	s.Lists = append([]string(nil), st.s.Lists...)
	return s
}

func (st *settingsStore) update(fn func(Settings) Settings) Settings {
	st.mu.Lock()
	defer st.mu.Unlock()
	st.s = fn(st.s)
	b, _ := json.MarshalIndent(st.s, "", "  ")
	tmp := st.path + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		_ = os.Rename(tmp, st.path)
	}
	return st.s
}

// Fallback returns a second encrypted upstream (none for a custom server).
func (s Settings) Fallback() *Upstream {
	if s.Upstream == "CUSTOM" {
		return nil
	}
	id := "CLOUDFLARE"
	if s.Upstream == "CLOUDFLARE" {
		id = "QUAD9"
	}
	for i := range Upstreams {
		if Upstreams[i].ID == id {
			return &Upstreams[i]
		}
	}
	return nil
}
