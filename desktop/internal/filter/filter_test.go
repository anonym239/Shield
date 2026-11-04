package filter

import (
	"strings"
	"testing"
)

func engine() *Engine {
	e := NewEngine()
	e.SetLists([]List{
		{ID: "ads", Name: "Werbung", Category: Ads, Parsed: Parse(strings.NewReader("||ads.com^\n@@||ok.ads.com^\n0.0.0.0 tracker.net\n"), false)},
		{ID: "ph", Name: "Phishing", Category: Phishing, Parsed: Parse(strings.NewReader("bad-bank.com"), false)},
	})
	return e
}

func TestParseFormats(t *testing.T) {
	p := Parse(strings.NewReader("! c\n# c\n127.0.0.1 localhost\n0.0.0.0 a.com # x\n||B.org^$important\n||c.net^$third-party\n||path.com/x\nd.de\n@@||e.com^\n"), false)
	for _, d := range []string{"a.com", "b.org", "d.de"} {
		if _, ok := p.Blocked[d]; !ok {
			t.Errorf("%s not blocked", d)
		}
	}
	if _, ok := p.Blocked["c.net"]; ok {
		t.Error("third-party rule accepted in normal mode")
	}
	if _, ok := p.Allowed["e.com"]; !ok {
		t.Error("exception missing")
	}
	agg := Parse(strings.NewReader("||c.net^$third-party\n"), true)
	if _, ok := agg.Blocked["c.net"]; !ok {
		t.Error("aggressive mode should accept third-party")
	}
}

func TestDecideOrder(t *testing.T) {
	e := engine()
	if !e.Decide("x.ads.com").Blocked || e.Decide("notads.com").Blocked {
		t.Fatal("suffix matching wrong")
	}
	if d := e.Decide("ok.ads.com"); d.Blocked || d.Reason != "LIST_EXCEPTION" {
		t.Fatal("exception should win")
	}
	if e.Decide("login.bad-bank.com").Category != Phishing {
		t.Fatal("category lost")
	}
	e.SetRules([]Rule{{"ads.com", true}, {"*.google.com", false}, {"tracker*.example.org", false}, {"ok.ads.com", false}})
	if d := e.Decide("a.ads.com"); d.Blocked || d.Reason != "CUSTOM_ALLOW" {
		t.Fatal("own allow must win")
	}
	if !e.Decide("mail.google.com").Blocked || e.Decide("google.com").Blocked {
		t.Fatal("*. rule wrong")
	}
	if !e.Decide("tracker7.example.org").Blocked {
		t.Fatal("glob rule wrong")
	}
	if !e.Decide(SelftestDomain).Blocked {
		t.Fatal("selftest domain")
	}
}

func TestValidPattern(t *testing.T) {
	for p, want := range map[string]bool{"*.a.com": true, "a.com": true, "a": false, "a b.com": false, "..com": false} {
		if ValidPattern(p) != want {
			t.Errorf("%q", p)
		}
	}
}
