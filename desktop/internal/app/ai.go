// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"
)

// AiVerdict is the answer of the optional Gemini health check.
type AiVerdict struct {
	Time    int64    `json:"time"`
	Status  string   `json:"status"` // OK, WARNING, PROBLEM
	Summary string   `json:"summary"`
	Actions []string `json:"actions"`
}

var aiAllowed = map[string]bool{"RESTART_TUNNEL": true, "UPDATE_LISTS": true, "RUN_SELFTEST": true}

const aiSystemPrompt = `Du bist der Diagnose-Assistent der Sicherheits-App "Shield für Windows" (lokaler DNS-Filter, verschlüsseltes DNS, WireGuard-VPN).
Du bekommst eine anonymisierte Zustandsübersicht der letzten 24 Stunden.
Beurteile, ob der Schutz zuverlässig läuft. Antworte NUR als JSON:
{"status": "OK" | "WARNING" | "PROBLEM", "summary": "höchstens 3 kurze Sätze auf Deutsch, verständlich für Laien", "actions": Liste aus "RESTART_TUNNEL", "UPDATE_LISTS", "RUN_SELFTEST" oder leer}
Wähle Aktionen nur, wenn sie ein konkretes erkanntes Problem beheben. Behaupte nie, etwas sei 100 % sicher.`

var (
	aiIP     = regexp.MustCompile(`\b(\d{1,3}\.){3}\d{1,3}\b|\b([0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}\b`)
	aiDomain = regexp.MustCompile(`\b([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b`)
)

// AISanitize removes domains, IPs and secrets.
func AISanitize(s string) string {
	s = aiDomain.ReplaceAllString(aiIP.ReplaceAllString(Redact(s), "<ip>"), "<domain>")
	if len(s) > 300 {
		s = s[:300]
	}
	return s
}

func (e *Engine) aiKeyPath() string { return filepath.Join(e.dir, "gemini.bin") }

func (e *Engine) HasAIKey() bool { _, err := os.Stat(e.aiKeyPath()); return err == nil }

func (e *Engine) SetAIKey(key string) error {
	key = strings.TrimSpace(key)
	if len(key) < 20 {
		return errors.New("API-Key zu kurz")
	}
	enc, err := protect([]byte(key))
	if err != nil {
		return err
	}
	if err := os.WriteFile(e.aiKeyPath(), enc, 0o600); err != nil {
		return err
	}
	e.settings.update(func(s Settings) Settings { s.AIEnabled = true; return s })
	e.events.add("SETTINGS_CHANGED", "INFO", "Gemini-API-Key gespeichert (verschlüsselt)")
	return nil
}

func (e *Engine) DeleteAIKey() {
	_ = os.Remove(e.aiKeyPath())
	e.settings.update(func(s Settings) Settings { s.AIEnabled = false; return s })
	e.events.add("SETTINGS_CHANGED", "INFO", "Gemini-API-Key gelöscht")
}

func (e *Engine) aiPrompt() string {
	s := e.settings.get()
	e.mu.Lock()
	phase, rep := e.phase, e.report
	e.mu.Unlock()
	counts := map[string]int{}
	var problems []string
	for _, ev := range e.events.since(time.Now().Add(-24 * time.Hour)) {
		counts[ev.Type]++
		if ev.Severity != "INFO" && len(problems) < 20 {
			problems = append(problems, AISanitize(ev.Type+": "+ev.Message))
		}
	}
	var tests []map[string]any
	if rep != nil {
		for _, t := range rep.Results {
			tests = append(tests, map[string]any{"test": t.ID, "passed": t.Passed, "detail": AISanitize(t.Detail)})
		}
	}
	_, total, oldest, _ := e.lists.status(s)
	b, _ := json.Marshal(map[string]any{
		"platform": "windows/" + runtime.GOARCH, "app_version": Version, "protection_enabled": s.Enabled,
		"phase": phase, "level": s.Level, "vpn_profile_active": s.ActiveProfile != "", "kill_switch": s.KillSwitch,
		"blocklist_rules": total, "blocklist_age_hours": (time.Now().UnixMilli() - oldest) / 3600000,
		"self_test": tests, "events_24h": counts, "recent_problems": problems,
	})
	return string(b)
}

// ParseAIResponse parses a Gemini generateContent answer; unknown actions are dropped.
func ParseAIResponse(body []byte) (*AiVerdict, error) {
	var root struct {
		Error *struct {
			Message string `json:"message"`
		} `json:"error"`
		Candidates []struct {
			Content struct {
				Parts []struct {
					Text string `json:"text"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
	}
	if err := json.Unmarshal(body, &root); err != nil {
		return nil, err
	}
	if root.Error != nil {
		return nil, errors.New(root.Error.Message)
	}
	if len(root.Candidates) == 0 || len(root.Candidates[0].Content.Parts) == 0 {
		return nil, errors.New("leere KI-Antwort")
	}
	txt := strings.TrimSpace(root.Candidates[0].Content.Parts[0].Text)
	txt = strings.TrimSuffix(strings.TrimPrefix(strings.TrimPrefix(txt, "```json"), "```"), "```")
	var v AiVerdict
	var raw struct {
		Status  string   `json:"status"`
		Summary string   `json:"summary"`
		Actions []string `json:"actions"`
	}
	if err := json.Unmarshal([]byte(strings.TrimSpace(txt)), &raw); err != nil {
		return nil, fmt.Errorf("KI-Antwort nicht lesbar: %v", err)
	}
	v.Time = time.Now().UnixMilli()
	v.Status = strings.ToUpper(raw.Status)
	if v.Status != "OK" && v.Status != "WARNING" && v.Status != "PROBLEM" {
		v.Status = "WARNING"
	}
	v.Summary = strings.NewReplacer("100 %", "voll", "100%", "voll").Replace(raw.Summary)
	for _, a := range raw.Actions {
		a = strings.ToUpper(a)
		if aiAllowed[a] {
			v.Actions = append(v.Actions, a)
		}
	}
	return &v, nil
}

// AICheck runs the Gemini health check and executes the allowed actions.
func (e *Engine) AICheck() (*AiVerdict, error) {
	e.mu.Lock()
	if e.aiRunning {
		e.mu.Unlock()
		return nil, errors.New("läuft bereits")
	}
	e.aiRunning = true
	e.mu.Unlock()
	defer func() { e.mu.Lock(); e.aiRunning = false; e.mu.Unlock() }()
	enc, err := os.ReadFile(e.aiKeyPath())
	if err != nil {
		return nil, errors.New("Kein API-Key gespeichert")
	}
	key, err := unprotect(enc)
	if err != nil {
		return nil, err
	}
	s := e.settings.get()
	reqBody, _ := json.Marshal(map[string]any{
		"systemInstruction": map[string]any{"parts": []any{map[string]string{"text": aiSystemPrompt}}},
		"contents":          []any{map[string]any{"role": "user", "parts": []any{map[string]string{"text": e.aiPrompt()}}}},
		"generationConfig":  map[string]any{"responseMimeType": "application/json", "temperature": 0.2},
	})
	e.events.add("NETWORK_ACCESS", "INFO", "KI-Prüfung: anonymisierte Zusammenfassung an Google Gemini (%s)", s.AIModel)
	req, _ := http.NewRequest(http.MethodPost, "https://generativelanguage.googleapis.com/v1beta/models/"+s.AIModel+":generateContent", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("x-goog-api-key", string(key))
	resp, err := (&http.Client{Timeout: 60 * time.Second}).Do(req)
	if err != nil {
		e.events.add("AI_CHECK", "WARNING", "KI-Prüfung fehlgeschlagen: %v", err)
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	v, err := ParseAIResponse(body)
	if err != nil {
		e.events.add("AI_CHECK", "WARNING", "KI-Prüfung fehlgeschlagen: %v", err)
		return nil, err
	}
	sev := map[string]string{"OK": "INFO", "WARNING": "WARNING", "PROBLEM": "CRITICAL"}[v.Status]
	e.events.add("AI_CHECK", sev, "KI-Prüfung: %s", v.Summary)
	e.mu.Lock()
	e.ai = v
	e.mu.Unlock()
	b, _ := json.Marshal(v)
	_ = os.WriteFile(filepath.Join(e.dir, "ai.json"), b, 0o600)
	for _, a := range v.Actions {
		switch a {
		case "RESTART_TUNNEL":
			if s.Enabled {
				e.events.add("AI_CHECK", "INFO", "KI-Aktion: Schutz wird neu gestartet")
				_ = e.Restart()
			}
		case "UPDATE_LISTS":
			e.events.add("AI_CHECK", "INFO", "KI-Aktion: Blocklisten werden aktualisiert")
			e.lists.update(s, true)
		case "RUN_SELFTEST":
			e.SelfTest(false)
		}
	}
	return v, nil
}
