// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"regexp"
	"sort"
	"strings"
	"unicode"
)

// Country of a VPN server (data: scripts/countries.tsv → countries_data.go).
type Country struct {
	Code    string   `json:"code"`
	NameDe  string   `json:"nameDe"`
	NameEn  string   `json:"nameEn"`
	Aliases []string `json:"-"`
}

var countryByCode = func() map[string]Country {
	m := map[string]Country{}
	for _, c := range countryData {
		m[c.Code] = c
	}
	return m
}()

// CountriesSorted is the picker list, sorted by German name.
func CountriesSorted() []Country {
	out := append([]Country(nil), countryData...)
	sort.Slice(out, func(i, j int) bool { return out[i].NameDe < out[j].NameDe })
	return out
}

func countryOf(code string) (Country, bool) {
	c, ok := countryByCode[strings.ToUpper(code)]
	return c, ok
}

var nonWord = regexp.MustCompile(`[^\p{L}\p{N}]+`)

func words(s string) string {
	return " " + strings.TrimSpace(nonWord.ReplaceAllString(strings.ToLower(s), " ")) + " "
}

type countryName struct {
	name string
	c    Country
}

var countryNames = func() []countryName {
	var out []countryName
	for _, c := range countryData {
		for _, n := range append([]string{c.NameDe, c.NameEn}, c.Aliases...) {
			out = append(out, countryName{words(n), c})
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return len(out[i].name) > len(out[j].name) })
	return out
}()

// DetectCountry finds the country in "Niederlande #1", "Netherlands", "wg-NL-FREE-12" or "NL".
// Two-letter codes only count in capitals or as parts of a file name.
func DetectCountry(text string) (Country, bool) {
	if strings.TrimSpace(text) == "" {
		return Country{}, false
	}
	w := words(text)
	for _, n := range countryNames {
		if strings.Contains(w, n.name) {
			return n.c, true
		}
	}
	fileLike := strings.ContainsAny(text, "-_") || strings.HasSuffix(strings.ToLower(text), ".conf")
	for _, t := range strings.FieldsFunc(text, func(r rune) bool { return !(unicode.IsLetter(r) && r < 128 || unicode.IsDigit(r)) }) {
		if len(t) == 2 && (t == strings.ToUpper(t) || fileLike) {
			if c, ok := countryOf(t); ok {
				return c, true
			}
		}
	}
	return Country{}, false
}

var protonServer = regexp.MustCompile(`^#\s*([A-Z]{2})(?:-([A-Z]{2}))?(-FREE)?#(\d+)\s*$`)
var secureCoreEntries = map[string]bool{"CH": true, "IS": true, "SE": true}
var genericNames = map[string]bool{"document": true, "dokument": true, "download": true, "wireguard": true, "wg0": true,
	"config": true, "profile": true, "vpn": true, "proton": true}

// GuessServer returns country code and a readable name for a new profile: Proton's server
// comment ("# NL-FREE#12"), then the file name, then the endpoint. Meaningless file names are never used.
func GuessServer(fileName, conf, endpoint string) (code, name string) {
	for _, line := range strings.Split(conf, "\n") {
		m := protonServer.FindStringSubmatch(strings.TrimSpace(line))
		if m == nil {
			continue
		}
		first, second, free, num := m[1], m[2], m[3], m[4]
		_, secondOK := countryOf(second)
		secure := second != "" && secureCoreEntries[first] && secondOK
		cc := first
		if secure {
			cc = second
		}
		c, ok := countryOf(cc)
		if !ok {
			continue
		}
		suffix := ""
		if secure {
			suffix = " (Secure Core)"
		} else if free != "" {
			suffix = " (Free)"
		}
		return c.Code, c.NameDe + " #" + num + suffix
	}
	fn := strings.TrimSpace(fileName)
	allDigits := fn != "" && strings.Trim(fn, "0123456789") == ""
	useful := fn != "" && !genericNames[strings.ToLower(fn)] && !allDigits
	c, ok := DetectCountry(fn)
	switch {
	case useful:
		if ok {
			return c.Code, fn
		}
		return "", fn
	case ok:
		return c.Code, c.NameDe
	default:
		host := endpoint
		if i := strings.LastIndex(host, ":"); i > 0 {
			host = host[:i]
		}
		if host == "" {
			host = "VPN"
		}
		return "", host
	}
}
