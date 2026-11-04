// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"testing"
	"time"
)

func TestDetectCountry(t *testing.T) {
	cases := map[string]string{
		"Niederlande #1": "NL", "netherlands 3": "NL", "Südkorea": "KR", "Schweiz Zürich": "CH",
		"USA New York": "US", "UK London": "GB", "wg-NL-FREE-12": "NL", "de-1.conf": "DE", "NL": "NL",
		"mein vpn": "", "Dokument": "", "": "",
	}
	for in, want := range cases {
		c, ok := DetectCountry(in)
		got := ""
		if ok {
			got = c.Code
		}
		if got != want {
			t.Errorf("%q: got %q, want %q", in, got, want)
		}
	}
}

func TestGuessServer(t *testing.T) {
	type tc struct{ file, conf, code, name string }
	for _, c := range []tc{
		{"Dokument", "[Peer]\n# NL-FREE#12\n", "NL", "Niederlande #12 (Free)"},
		{"x", "# NL#3", "NL", "Niederlande #3"},
		{"x", "# CH-NL#1", "NL", "Niederlande #1 (Secure Core)"},
		{"x", "# US-CA#40", "US", "USA #40"},
		{"wg-DE-1", "", "DE", "wg-DE-1"},
		{"Dokument", "", "", "1.2.3.4"},
		{"1234", "", "", "1.2.3.4"},
		{"Mein Server", "", "", "Mein Server"},
	} {
		code, name := GuessServer(c.file, c.conf, "1.2.3.4:51820")
		if code != c.code || name != c.name {
			t.Errorf("%q/%q: got %q %q", c.file, c.conf, code, name)
		}
	}
}

func TestSpeedMath(t *testing.T) {
	if got := mbps(10_000_000, time.Second); got < 79.99 || got > 80.01 {
		t.Fatalf("mbps = %v", got)
	}
	if mbps(1, 0) != 0 {
		t.Fatal("zero duration")
	}
	if m := medianDur([]time.Duration{5, 1, 3}); m != 3 {
		t.Fatalf("median = %v", m)
	}
	if m := medianDur([]time.Duration{10, 40, 20, 30}); m != 25 {
		t.Fatalf("median = %v", m)
	}
}
