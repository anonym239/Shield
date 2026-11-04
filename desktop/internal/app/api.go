// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"crypto/subtle"
	"encoding/json"
	"io/fs"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// StatusOf evaluates green/yellow/red like on Android.
func (e *Engine) StatusOf() (level string, reasons []string) {
	s := e.settings.get()
	e.mu.Lock()
	phase, rep, started, wg := e.phase, e.report, e.startedAt, e.wg
	testing := e.testing
	e.mu.Unlock()
	if phase == "ERROR" {
		return "UNPROTECTED", []string{"TUNNEL_ERROR"}
	}
	if phase != "RUNNING" {
		return "UNPROTECTED", []string{"TUNNEL_OFF"}
	}
	if rep != nil && rep.Time >= started && !rep.AllPassed() && !testing {
		return "UNPROTECTED", []string{"SELFTEST_FAILED"}
	}
	if e.res.panic.Load() {
		reasons = append(reasons, "PANIC")
	}
	_, total, oldest, _ := e.lists.status(s)
	if total == 0 {
		reasons = append(reasons, "LISTS_MISSING")
	} else if oldest > 0 && time.Since(time.UnixMilli(oldest)) > 48*time.Hour {
		reasons = append(reasons, "LISTS_OUTDATED")
	}
	if s.ActiveProfile != "" && (wg == nil || wg.handshakeAge() < 0 || wg.handshakeAge() > 180) {
		reasons = append(reasons, "VPN_DISCONNECTED")
	}
	if rep == nil || rep.Time < started || testing {
		reasons = append(reasons, "SELFTEST_PENDING")
	}
	if len(reasons) > 0 {
		return "LIMITED", reasons
	}
	return "PROTECTED", nil
}

func (e *Engine) state() map[string]any {
	s := e.settings.get()
	level, reasons := e.StatusOf()
	lists, total, oldest, updating := e.lists.status(s)
	e.mu.Lock()
	phase, errMsg, started, rep, wg, wgName, wgSince, testing, ai, aiRunning := e.phase, e.errMsg, e.startedAt, e.report, e.wg, e.wgName, e.wgSince, e.testing, e.ai, e.aiRunning
	e.mu.Unlock()
	hours := e.res.last24h()
	var q, b, ph int64
	now := time.Now()
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.Local)
	for _, h := range hours {
		if time.Unix(h.Hour*3600, 0).After(today) || time.Unix(h.Hour*3600, 0).Equal(today) {
			q += h.Queries
			b += h.Blocked
			ph += h.Phishing
		}
	}
	vpn := map[string]any{"active": s.ActiveProfile, "name": wgName, "running": wg != nil, "handshakeAge": -1, "since": wgSince}
	if wg != nil {
		age := wg.handshakeAge()
		vpn["handshakeAge"] = age
		vpn["connected"] = age >= 0 && age < 180
	}
	_, upLabel := func() (string, string) { u, _, l := s.DoH(); return u, l }()
	return map[string]any{
		"version": Version, "phase": phase, "error": errMsg, "startedAt": started,
		"status": level, "reasons": reasons, "testing": testing, "panic": e.res.panic.Load(),
		"selftest": rep, "settings": s, "upstreams": Upstreams, "upstreamLabel": upLabel,
		"lists": lists, "rules": total, "listsOldest": oldest, "listsUpdating": updating,
		"today": map[string]int64{"queries": q, "blocked": b, "phishing": ph}, "hours": hours,
		"topBlocked": e.res.topBlocked(5), "vpn": vpn, "profiles": e.Profiles(), "ownRules": e.Rules(),
		"ai":    map[string]any{"hasKey": e.HasAIKey(), "verdict": ai, "running": aiRunning},
		"admin": isAdmin(),
	}
}

// OnQuit is set by main: stops the engine (restores DNS) and exits.
var OnQuit func()

// API serves the UI and a token protected JSON API on 127.0.0.1.
type API struct {
	E     *Engine
	Token string
	ln    net.Listener
}

// Listen opens 127.0.0.1:<random port> and writes api.json for other instances.
func (a *API) Listen() (string, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	a.ln = ln
	a.Token = newToken()
	addr := ln.Addr().String()
	b, _ := json.Marshal(map[string]string{"addr": addr, "token": a.Token})
	_ = os.WriteFile(filepath.Join(a.E.dir, "api.json"), b, 0o600)
	return addr, nil
}

// Running instance (single instance): returns the URL of an existing engine, if alive.
func RunningInstanceURL() string {
	b, err := os.ReadFile(filepath.Join(DataDir(), "api.json"))
	if err != nil {
		return ""
	}
	var m map[string]string
	if json.Unmarshal(b, &m) != nil || m["addr"] == "" {
		return ""
	}
	c := http.Client{Timeout: 2 * time.Second}
	req, _ := http.NewRequest(http.MethodGet, "http://"+m["addr"]+"/api/ping", nil)
	req.Header.Set("X-Shield-Token", m["token"])
	resp, err := c.Do(req)
	if err != nil {
		return ""
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		return ""
	}
	return "http://" + m["addr"] + "/#t=" + m["token"]
}

func (a *API) URL() string { return "http://" + a.ln.Addr().String() + "/#t=" + a.Token }

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, err error) {
	w.WriteHeader(http.StatusBadRequest)
	writeJSON(w, map[string]string{"error": err.Error()})
}

func body(r *http.Request, v any) error {
	return json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20)).Decode(v)
}

// Serve blocks.
func (a *API) Serve(static fs.FS) error {
	mux := http.NewServeMux()
	files := http.FileServer(http.FS(static))
	mux.Handle("/", files)
	api := func(path string, h func(w http.ResponseWriter, r *http.Request)) {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			tok := r.Header.Get("X-Shield-Token")
			if subtle.ConstantTimeCompare([]byte(tok), []byte(a.Token)) != 1 {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			if r.Method != http.MethodGet && r.Header.Get("Content-Type") != "application/json" {
				w.WriteHeader(http.StatusUnsupportedMediaType) // blocks simple cross-site form posts
				return
			}
			h(w, r)
		})
	}
	e := a.E
	api("/api/ping", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, "ok") })
	api("/api/state", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, e.state()) })
	api("/api/toggle", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ On bool }
		if err := body(r, &in); err != nil {
			fail(w, err)
			return
		}
		if in.On {
			if err := e.Start(); err != nil {
				fail(w, err)
				return
			}
		} else {
			e.Stop()
		}
		writeJSON(w, "ok")
	})
	api("/api/selftest", func(w http.ResponseWriter, r *http.Request) {
		rep := e.SelfTest(true)
		go e.Heal(rep)
		writeJSON(w, rep)
	})
	api("/api/queries", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, e.res.snapshot(500, r.URL.Query().Get("result"), r.URL.Query().Get("q")))
	})
	api("/api/events", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, e.events.list(500)) })
	api("/api/log", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, logLines()) })
	api("/api/rules", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Pattern string
			Allow   bool
			Remove  bool
		}
		if err := body(r, &in); err != nil {
			fail(w, err)
			return
		}
		if in.Remove {
			e.RemoveRule(in.Pattern)
		} else if err := e.SetRule(in.Pattern, in.Allow); err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, "ok")
	})
	api("/api/settings", func(w http.ResponseWriter, r *http.Request) {
		var in map[string]json.RawMessage
		if err := body(r, &in); err != nil {
			fail(w, err)
			return
		}
		s := e.UpdateSettings(func(s Settings) Settings {
			for k, v := range in {
				switch k {
				case "level":
					var l Level
					if json.Unmarshal(v, &l) == nil {
						s = s.ApplyLevel(l)
					}
				case "upstream":
					_ = json.Unmarshal(v, &s.Upstream)
				case "customDoh":
					var u string
					if json.Unmarshal(v, &u) == nil && (u == "" || strings.HasPrefix(u, "https://")) {
						s.CustomDoH = u
					}
				case "customBootstrap":
					_ = json.Unmarshal(v, &s.CustomBootstrap)
				case "lists":
					_ = json.Unmarshal(v, &s.Lists)
				case "autoHeal":
					_ = json.Unmarshal(v, &s.AutoHeal)
				case "killSwitch":
					_ = json.Unmarshal(v, &s.KillSwitch)
				case "noHistory":
					_ = json.Unmarshal(v, &s.NoHistory)
				case "autostart":
					_ = json.Unmarshal(v, &s.Autostart)
				case "aiEnabled":
					_ = json.Unmarshal(v, &s.AIEnabled)
				case "aiModel":
					var m string
					if json.Unmarshal(v, &m) == nil && m != "" && !strings.ContainsAny(m, "/?#&") {
						s.AIModel = m
					}
				case "activeProfile":
					_ = json.Unmarshal(v, &s.ActiveProfile)
				}
			}
			return s
		})
		writeJSON(w, s)
	})
	api("/api/lists/update", func(w http.ResponseWriter, r *http.Request) {
		go e.lists.update(e.settings.get(), true)
		writeJSON(w, "ok")
	})
	api("/api/vpn/import", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Name, Conf string }
		if err := body(r, &in); err != nil {
			fail(w, err)
			return
		}
		p, err := e.ImportProfile(in.Name, in.Conf)
		if err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, p)
	})
	api("/api/vpn/delete", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ ID string }
		if err := body(r, &in); err != nil {
			fail(w, err)
			return
		}
		e.DeleteProfile(in.ID)
		writeJSON(w, "ok")
	})
	api("/api/ai/key", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Key    string
			Delete bool
		}
		if err := body(r, &in); err != nil {
			fail(w, err)
			return
		}
		if in.Delete {
			e.DeleteAIKey()
		} else if err := e.SetAIKey(in.Key); err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, "ok")
	})
	api("/api/ai/check", func(w http.ResponseWriter, r *http.Request) {
		v, err := e.AICheck()
		if err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, v)
	})
	api("/api/panic", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ On bool }
		if err := body(r, &in); err != nil {
			fail(w, err)
			return
		}
		e.SetPanic(in.On)
		writeJSON(w, "ok")
	})
	api("/api/history/clear", func(w http.ResponseWriter, r *http.Request) {
		e.res.clearHistory()
		e.events.clear()
		writeJSON(w, "ok")
	})
	api("/api/open", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ URL string }
		if err := body(r, &in); err != nil {
			fail(w, err)
			return
		}
		// only well-known help pages, never arbitrary URLs
		for _, ok := range []string{"https://account.protonvpn.com/", "https://aistudio.google.com/", "https://github.com/anonym239/Shield",
			"https://www.mozilla.org/firefox/", "https://addons.mozilla.org/firefox/addon/ublock-origin/",
			"https://chromewebstore.google.com/detail/ublock-origin-lite/"} {
			if strings.HasPrefix(in.URL, ok) {
				_ = openURL(in.URL)
				writeJSON(w, "ok")
				return
			}
		}
		w.WriteHeader(http.StatusForbidden)
	})
	api("/api/quit", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, "ok")
		if OnQuit != nil {
			go OnQuit()
		}
	})
	api("/api/restart", func(w http.ResponseWriter, r *http.Request) {
		if err := e.Restart(); err != nil {
			fail(w, err)
			return
		}
		writeJSON(w, "ok")
	})
	srv := &http.Server{
		Handler:           hostCheck(a.ln.Addr().String(), mux),
		ReadHeaderTimeout: 10 * time.Second,
	}
	return srv.Serve(a.ln)
}

// hostCheck rejects DNS-rebinding requests (Host must be our 127.0.0.1:port).
func hostCheck(addr string, h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != addr {
			w.WriteHeader(http.StatusMisdirectedRequest)
			return
		}
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:")
		h.ServeHTTP(w, r)
	})
}
