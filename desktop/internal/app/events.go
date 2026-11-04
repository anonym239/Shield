// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"
)

// Event is a security event (same types as on Android).
type Event struct {
	Time     int64  `json:"time"`
	Type     string `json:"type"`
	Severity string `json:"severity"` // INFO, WARNING, CRITICAL
	Message  string `json:"message"`
}

var redactRes = []*regexp.Regexp{
	regexp.MustCompile(`(?i)(private_?key|preshared_?key|token|password|secret)\s*[=:]\s*\S+`),
	regexp.MustCompile(`\b[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=`),
	regexp.MustCompile(`\b[0-9a-fA-F]{64}\b`),
	regexp.MustCompile(`AIza[0-9A-Za-z_\-]{30,40}`),
}

// Redact removes secrets from text.
func Redact(s string) string {
	for _, re := range redactRes {
		s = re.ReplaceAllString(s, "<redacted>")
	}
	return s
}

type eventLog struct {
	mu   sync.Mutex
	path string
	ring []Event
}

func newEventLog(dir string) *eventLog {
	l := &eventLog{path: filepath.Join(dir, "events.jsonl")}
	if b, err := os.ReadFile(l.path); err == nil {
		dec := json.NewDecoder(bytesReader(b))
		for dec.More() {
			var e Event
			if dec.Decode(&e) == nil && time.Since(time.UnixMilli(e.Time)) < 30*24*time.Hour {
				l.ring = append(l.ring, e)
			}
		}
	}
	if len(l.ring) > 2000 {
		l.ring = l.ring[len(l.ring)-2000:]
	}
	l.rewrite()
	return l
}

func (l *eventLog) rewrite() {
	f, err := os.Create(l.path)
	if err != nil {
		return
	}
	defer f.Close()
	enc := json.NewEncoder(f)
	for _, e := range l.ring {
		_ = enc.Encode(e)
	}
}

func (l *eventLog) add(typ, sev, format string, a ...any) {
	e := Event{time.Now().UnixMilli(), typ, sev, Redact(fmt.Sprintf(format, a...))}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.ring = append(l.ring, e)
	if len(l.ring) > 2000 {
		l.ring = l.ring[1:]
	}
	if f, err := os.OpenFile(l.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
		_ = json.NewEncoder(f).Encode(e)
		f.Close()
	}
	logf("%s %s: %s", sev, typ, e.Message)
}

func (l *eventLog) list(limit int) []Event {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := len(l.ring)
	out := make([]Event, 0, limit)
	for i := n - 1; i >= 0 && len(out) < limit; i-- {
		out = append(out, l.ring[i])
	}
	return out
}

func (l *eventLog) since(t time.Time) []Event {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []Event
	for _, e := range l.ring {
		if e.Time >= t.UnixMilli() {
			out = append(out, e)
		}
	}
	return out
}

func (l *eventLog) clear() {
	l.mu.Lock()
	l.ring = nil
	l.rewrite()
	l.mu.Unlock()
}

// ---- live log (debug) ----

var (
	logMu   sync.Mutex
	logRing []string
)

func logf(format string, a ...any) {
	line := time.Now().Format("15:04:05.000 ") + Redact(fmt.Sprintf(format, a...))
	logMu.Lock()
	logRing = append(logRing, line)
	if len(logRing) > 1000 {
		logRing = logRing[1:]
	}
	logMu.Unlock()
}

func logLines() []string {
	logMu.Lock()
	defer logMu.Unlock()
	return append([]string(nil), logRing...)
}
