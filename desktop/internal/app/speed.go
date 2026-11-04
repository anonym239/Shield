// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"
)

// SpeedResult of a user-triggered speed test through the active VPN server.
type SpeedResult struct {
	DownMbps float64 `json:"down"`
	UpMbps   float64 `json:"up"`
	PingMs   int64   `json:"ping"`
	TestedAt int64   `json:"at"`
}

const speedBase = "https://speed.cloudflare.com"

// mbps converts bytes in a duration to megabit per second.
func mbps(bytes int64, d time.Duration) float64 {
	if d <= 0 {
		return 0
	}
	return float64(bytes) * 8 / d.Seconds() / 1e6
}

func medianDur(ds []time.Duration) time.Duration {
	s := append([]time.Duration(nil), ds...)
	sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })
	if len(s)%2 == 1 {
		return s[len(s)/2]
	}
	return (s[len(s)/2-1] + s[len(s)/2]) / 2
}

// runSpeedTest measures ping, download (max 25 MB / 8 s) and upload (10 MB) against Cloudflare.
func runSpeedTest(c *http.Client) (SpeedResult, error) {
	var pings []time.Duration
	for i := 0; i < 5; i++ {
		t0 := time.Now()
		resp, err := c.Get(speedBase + "/__down?bytes=0")
		if err != nil {
			return SpeedResult{}, err
		}
		_, _ = io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return SpeedResult{}, fmt.Errorf("HTTP %d", resp.StatusCode)
		}
		pings = append(pings, time.Since(t0))
	}
	resp, err := c.Get(speedBase + "/__down?bytes=25000000")
	if err != nil {
		return SpeedResult{}, err
	}
	t0 := time.Now()
	var total int64
	buf := make([]byte, 64*1024)
	for time.Since(t0) < 8*time.Second {
		n, err := resp.Body.Read(buf)
		total += int64(n)
		if err != nil {
			break
		}
	}
	down := mbps(total, time.Since(t0))
	resp.Body.Close()

	const upBytes = 10_000_000
	chunk := make([]byte, 64*1024)
	_, _ = rand.Read(chunk)
	pr, pw := io.Pipe()
	go func() {
		left := upBytes
		for left > 0 {
			n := min(left, len(chunk))
			if _, err := pw.Write(chunk[:n]); err != nil {
				return
			}
			left -= n
		}
		pw.Close()
	}()
	req, _ := http.NewRequest(http.MethodPost, speedBase+"/__up", pr)
	req.ContentLength = upBytes
	req.Header.Set("Content-Type", "application/octet-stream")
	t1 := time.Now()
	up, err := c.Do(req)
	if err != nil {
		return SpeedResult{}, err
	}
	_, _ = io.Copy(io.Discard, up.Body)
	up.Body.Close()
	if up.StatusCode != http.StatusOK {
		return SpeedResult{}, fmt.Errorf("HTTP %d", up.StatusCode)
	}
	return SpeedResult{DownMbps: down, UpMbps: mbps(upBytes, time.Since(t1)),
		PingMs: medianDur(pings).Milliseconds(), TestedAt: time.Now().UnixMilli()}, nil
}

func (e *Engine) saveProfiles(ps []Profile) {
	b, _ := json.Marshal(ps)
	if err := os.WriteFile(e.profilesPath(), b, 0o600); err != nil {
		e.events.add("ERROR", "WARNING", "Profile konnten nicht gespeichert werden: %v", err)
	}
}

func (e *Engine) updateProfile(id string, fn func(*Profile)) error {
	ps := e.Profiles()
	for i := range ps {
		if ps[i].ID == id {
			fn(&ps[i])
			e.saveProfiles(ps)
			return nil
		}
	}
	return errors.New("Profil nicht gefunden")
}

// EditProfile sets the name and country of a server.
func (e *Engine) EditProfile(id, name, country string) error {
	name = strings.TrimSpace(name)
	if name == "" {
		return errors.New("Name fehlt")
	}
	if len([]rune(name)) > 40 {
		name = string([]rune(name)[:40])
	}
	if _, ok := countryOf(country); !ok {
		country = ""
	}
	err := e.updateProfile(id, func(p *Profile) { p.Name, p.Country = name, strings.ToUpper(country) })
	if err == nil {
		e.events.add("SETTINGS_CHANGED", "INFO", "VPN-Server umbenannt: %s", name)
		e.mu.Lock()
		if e.settings.get().ActiveProfile == id && e.wg != nil {
			e.wgName = name
		}
		e.mu.Unlock()
	}
	return err
}

func (e *Engine) wgConnectedTo(id string) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.wg == nil || e.settings.get().ActiveProfile != id {
		return false
	}
	age := e.wg.handshakeAge()
	return age >= 0 && age < 180
}

// SpeedTest switches to the server if needed, waits for the handshake and measures.
func (e *Engine) SpeedTest(id string) (SpeedResult, error) {
	if !e.Running() {
		return SpeedResult{}, errors.New("Zum Messen zuerst den Schutz einschalten")
	}
	if e.settings.get().ActiveProfile != id {
		e.settings.update(func(s Settings) Settings { s.ActiveProfile = id; return s })
		if err := e.Restart(); err != nil {
			return SpeedResult{}, err
		}
	}
	deadline := time.Now().Add(30 * time.Second)
	for !e.wgConnectedTo(id) {
		if time.Now().After(deadline) {
			return SpeedResult{}, errors.New("Der Server hat sich nicht verbunden")
		}
		time.Sleep(500 * time.Millisecond)
	}
	time.Sleep(1500 * time.Millisecond)
	e.events.add("NETWORK_ACCESS", "INFO", "Geschwindigkeitstest (speed.cloudflare.com)")
	r, err := runSpeedTest(&http.Client{Timeout: 40 * time.Second})
	if err != nil {
		e.events.add("CHECK_RESULT", "WARNING", "Geschwindigkeitstest fehlgeschlagen: %v", err)
		return SpeedResult{}, err
	}
	_ = e.updateProfile(id, func(p *Profile) { p.Speed = &r })
	return r, nil
}
