// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// ProtonDownloads is Proton's page with the WireGuard configuration generator.
const ProtonDownloads = "https://account.protonvpn.com/downloads"

// MaxConfSize: a WireGuard config is a few hundred bytes; anything big is not one.
const MaxConfSize = 64 * 1024

// LooksLikeWgConfig recognises a WireGuard configuration.
func LooksLikeWgConfig(text string) bool {
	if len(text) == 0 || len(text) > MaxConfSize {
		return false
	}
	t := strings.ToLower(text)
	return strings.Contains(t, "[interface]") && strings.Contains(t, "privatekey") &&
		strings.Contains(t, "[peer]") && strings.Contains(t, "publickey")
}

// ProtonStatus is shown while Shield waits for the downloaded config.
type ProtonStatus struct {
	State   string `json:"state"` // "", WAITING, DONE, ERROR, TIMEOUT
	Profile string `json:"profile,omitempty"`
	ID      string `json:"id,omitempty"`
	Message string `json:"message,omitempty"`
}

type protonWatch struct {
	mu     sync.Mutex
	status ProtonStatus
	stop   chan struct{}
}

// findNewConfig returns the newest *.conf in dir modified after since that is a WireGuard config.
func findNewConfig(dir string, since time.Time) (path, text string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "", ""
	}
	var best time.Time
	for _, en := range entries {
		if en.IsDir() || !strings.EqualFold(filepath.Ext(en.Name()), ".conf") {
			continue
		}
		info, err := en.Info()
		if err != nil || info.Size() > MaxConfSize || !info.ModTime().After(since) || info.ModTime().Before(best) {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, en.Name()))
		if err != nil || !LooksLikeWgConfig(string(b)) {
			continue
		}
		best, path, text = info.ModTime(), filepath.Join(dir, en.Name()), string(b)
	}
	return path, text
}

// QuickSetup imports a config, makes it the active profile and turns protection on.
func (e *Engine) QuickSetup(name, conf string) (Profile, error) {
	p, err := e.ImportProfile(name, conf)
	if err != nil {
		return Profile{}, err
	}
	e.settings.update(func(s Settings) Settings { s.ActiveProfile = p.ID; s.Enabled = true; return s })
	e.events.add("SETTINGS_CHANGED", "INFO", "VPN-Profil aktiviert: %s", p.Name)
	go func() {
		if err := e.Start(); err != nil {
			e.events.add("TUNNEL_ERROR", "ERROR", "Start nach VPN-Einrichtung fehlgeschlagen: %v", err)
		}
	}()
	return p, nil
}

// ProtonConnect opens Proton's page and watches the Downloads folder for the new config
// (up to 15 minutes). The file is deleted after the import because it holds the private key.
func (e *Engine) ProtonConnect() ProtonStatus {
	w := &e.proton
	w.mu.Lock()
	if w.stop != nil {
		close(w.stop)
	}
	stop := make(chan struct{})
	w.stop = stop
	w.status = ProtonStatus{State: "WAITING"}
	w.mu.Unlock()

	if err := openURL(ProtonDownloads); err != nil {
		e.events.add("ERROR", "WARNING", "Browser konnte nicht geöffnet werden: %v", err)
	}
	dir := downloadsDir()
	since := time.Now().Add(-5 * time.Second)
	e.events.add("SETTINGS_CHANGED", "INFO", "Warte auf Proton-Konfiguration in %s", dir)
	go func() {
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		deadline := time.After(15 * time.Minute)
		for {
			select {
			case <-stop:
				return
			case <-deadline:
				e.setProton(stop, ProtonStatus{State: "TIMEOUT", Message: "Keine neue Konfiguration im Download-Ordner gefunden"})
				return
			case <-t.C:
				path, text := findNewConfig(dir, since)
				if path == "" {
					continue
				}
				name := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
				p, err := e.QuickSetup(name, text)
				if err != nil {
					e.events.add("ERROR", "WARNING", "Proton-Konfiguration ungültig: %v", err)
					e.setProton(stop, ProtonStatus{State: "ERROR", Message: err.Error()})
					return
				}
				if err := os.Remove(path); err != nil {
					e.events.add("ERROR", "WARNING", "Heruntergeladene Konfiguration konnte nicht gelöscht werden: %v", err)
				}
				e.setProton(stop, ProtonStatus{State: "DONE", Profile: p.Name, ID: p.ID})
				return
			}
		}
	}()
	return ProtonStatus{State: "WAITING"}
}

func (e *Engine) setProton(owner chan struct{}, s ProtonStatus) {
	e.proton.mu.Lock()
	defer e.proton.mu.Unlock()
	if e.proton.stop == owner { // a newer request replaced this one
		e.proton.status = s
		e.proton.stop = nil
	}
}

// ProtonState returns the current waiting status.
func (e *Engine) ProtonState() ProtonStatus {
	e.proton.mu.Lock()
	defer e.proton.mu.Unlock()
	return e.proton.status
}
