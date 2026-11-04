// SPDX-License-Identifier: GPL-3.0-or-later
package filter

import (
	"reflect"
	"strings"
	"testing"
)

const uboSample = `! Title: uBlock filters
!#if env_mobile
||ads.example^
!#endif
||popads.example^$doc
||tracker.example^$3p
||cdn.example^$script,3p
||site.example/ads/*
example.org##.ad-banner
example.org##+js(set, adsEnabled, false)
@@||unbreak.example^
@@||page.example^$doc
@@||frame.example^$ghide
-ad-banner.
ads.js
/banner/*$image
`

func keys(m map[string]struct{}) map[string]bool {
	out := map[string]bool{}
	for k := range m {
		out[k] = true
	}
	return out
}

func TestUblockSyntax(t *testing.T) {
	p := ParseList(strings.NewReader(uboSample), false, true)
	if want := map[string]bool{"ads.example": true, "popads.example": true}; !reflect.DeepEqual(keys(p.Blocked), want) {
		t.Fatalf("blocked = %v", keys(p.Blocked))
	}
	if want := map[string]bool{"unbreak.example": true}; !reflect.DeepEqual(keys(p.Allowed), want) {
		t.Fatalf("allowed = %v", keys(p.Allowed))
	}
	a := ParseList(strings.NewReader(uboSample), true, true)
	if _, ok := a.Blocked["tracker.example"]; !ok {
		t.Fatal("aggressive should take $3p")
	}
	if _, ok := a.Blocked["cdn.example"]; ok {
		t.Fatal("$script must be skipped")
	}
	plain := Parse(strings.NewReader("ads.js\n"), false)
	if _, ok := plain.Blocked["ads.js"]; !ok {
		t.Fatal("plain lists keep bare hosts")
	}
}

func TestIncludes(t *testing.T) {
	got := FindIncludes([]byte("! x\n!#include filters-2024.txt\n!#include filters-2024.txt\n  !#include part.txt \n"))
	if !reflect.DeepEqual(got, []string{"filters-2024.txt", "part.txt"}) {
		t.Fatalf("includes = %v", got)
	}
	base := "https://ublockorigin.github.io/uAssets/filters/filters.txt"
	if r := ResolveInclude(base, "filters-2024.txt"); r != "https://ublockorigin.github.io/uAssets/filters/filters-2024.txt" {
		t.Fatalf("resolve = %q", r)
	}
	for _, bad := range []string{"../x.txt", "https://evil.example/x", "//evil.example/x", "/etc/passwd", "a b.txt"} {
		if r := ResolveInclude(base, bad); r != "" {
			t.Fatalf("%q resolved to %q", bad, r)
		}
	}
	if ResolveInclude("http://insecure.example/l.txt", "a.txt") != "" {
		t.Fatal("http base must be rejected")
	}
}
