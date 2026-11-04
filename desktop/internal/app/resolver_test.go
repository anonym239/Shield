package app

import (
	"context"
	"strings"
	"testing"

	"github.com/anonym239/shield/desktop/internal/dnsx"
	"github.com/anonym239/shield/desktop/internal/filter"
)

func TestResolverBlocksWithoutUpstream(t *testing.T) {
	e := filter.NewEngine()
	e.SetLists([]filter.List{{ID: "t", Name: "Test", Category: filter.Tracking, Parsed: filter.Parse(strings.NewReader("||firebaselogging-pa.googleapis.com^"), false)}})
	r := newResolver(e, &eventLog{path: t.TempDir() + "/e"}, t.TempDir())
	q := dnsx.BuildQuery(9, "x.firebaselogging-pa.googleapis.com", dnsx.TypeA)
	ans := r.resolve(context.Background(), q)
	if ans == nil || dnsx.Rcode(ans) != 0 || dnsx.MinTTL(ans) != 300 {
		t.Fatal("not blocked")
	}
	// no upstream configured -> SERVFAIL for allowed names, recorded as ERROR
	if dnsx.Rcode(r.resolve(context.Background(), dnsx.BuildQuery(1, "ok.org", dnsx.TypeA))) != dnsx.RcodeFail {
		t.Fatal("expected servfail")
	}
	got := r.snapshot(10, "BLOCKED", "")
	if len(got) != 1 || got[0].Rule != "Test" {
		t.Fatalf("log %+v", got)
	}
	r.panic.Store(true)
	if dnsx.Rcode(r.resolve(context.Background(), q)) != dnsx.RcodeFail {
		t.Fatal("panic mode must refuse")
	}
	r.noHistory.Store(true)
	before := len(r.snapshot(100, "", ""))
	r.resolve(context.Background(), q)
	if len(r.snapshot(100, "", "")) != before {
		t.Fatal("no-history stored a query")
	}
}

func TestBuiltinTelemetryParses(t *testing.T) {
	p := filter.Parse(strings.NewReader(string(builtinTelemetry)), false)
	if _, ok := p.Blocked["firebaselogging-pa.googleapis.com"]; !ok || len(p.Blocked) < 50 {
		t.Fatalf("builtin list broken: %d", len(p.Blocked))
	}
}

func TestLevels(t *testing.T) {
	s := DefaultSettings().ApplyLevel(Maximum)
	if !s.KillSwitch || !contains(s.Lists, "hagezi_doh") {
		t.Fatal("maximum preset")
	}
	if u, b, _ := DefaultSettings().DoH(); u == "" || len(b) == 0 {
		t.Fatal("upstream")
	}
	if Redact("PrivateKey = abc AIzaSyA1234567890abcdefghijklmnopqrstuv") != "<redacted> <redacted>" {
		t.Fatal(Redact("PrivateKey = abc AIzaSyA1234567890abcdefghijklmnopqrstuv"))
	}
}

func TestListUpdateQueuedNotLost(t *testing.T) {
	l := newLists(t.TempDir(), filter.NewEngine(), &eventLog{path: t.TempDir() + "/e"})
	cur := DefaultSettings().ApplyLevel(Strict)
	l.current = func() Settings { return cur }
	l.updating = true // simulate a running download
	if l.update(cur, false) {
		t.Fatal("second update should be queued")
	}
	if !l.pending {
		t.Fatal("request lost")
	}
	// built-in list of the current level is applied without network
	l.reload(cur)
	if !l.engine.Decide("firebaselogging-pa.googleapis.com").Blocked {
		t.Fatal("level not applied")
	}
}

func TestFallbackChoice(t *testing.T) {
	s := DefaultSettings()
	if f := s.Fallback(); f == nil || f.ID != "CLOUDFLARE" {
		t.Fatal("quad9 -> cloudflare")
	}
	s.Upstream = "CLOUDFLARE"
	if f := s.Fallback(); f == nil || f.ID != "QUAD9" {
		t.Fatal("cloudflare -> quad9")
	}
	s.Upstream = "CUSTOM"
	if s.Fallback() != nil {
		t.Fatal("custom server must not silently fall back")
	}
}
