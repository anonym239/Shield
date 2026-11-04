// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const protonConf = `[Interface]
PrivateKey = aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI=
Address = 10.2.0.2/32
DNS = 10.2.0.1

[Peer]
PublicKey = d29ybGQtd29ybGQtd29ybGQtd29ybGQtd29ybGQtMTI=
AllowedIPs = 0.0.0.0/0
Endpoint = 185.107.56.1:51820
`

func TestLooksLikeWgConfig(t *testing.T) {
	if !LooksLikeWgConfig(protonConf) {
		t.Fatal("config not recognised")
	}
	for _, s := range []string{"", "PrivateKey vergessen", "[Interface]\nPrivateKey = x", protonConf + strings.Repeat(" ", MaxConfSize)} {
		if LooksLikeWgConfig(s) {
			t.Fatalf("false positive: %.30q", s)
		}
	}
}

func TestFindNewConfig(t *testing.T) {
	dir := t.TempDir()
	write := func(name, body string, mod time.Time) {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := os.Chtimes(p, mod, mod); err != nil {
			t.Fatal(err)
		}
	}
	since := time.Now()
	write("old.conf", protonConf, since.Add(-time.Hour))     // before the button was pressed
	write("notes.conf", "hallo", since.Add(time.Second))     // not a WireGuard config
	write("wg-NL-1.txt", protonConf, since.Add(time.Second)) // wrong extension
	if p, _ := findNewConfig(dir, since); p != "" {
		t.Fatalf("found %s", p)
	}
	write("wg-NL-FREE-1.conf", protonConf, since.Add(2*time.Second))
	write("wg-DE-FREE-2.CONF", protonConf, since.Add(3*time.Second))
	p, text := findNewConfig(dir, since)
	if filepath.Base(p) != "wg-DE-FREE-2.CONF" || text != protonConf {
		t.Fatalf("got %s", p)
	}
}
