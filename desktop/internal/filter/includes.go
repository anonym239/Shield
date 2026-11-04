// SPDX-License-Identifier: GPL-3.0-or-later
package filter

import (
	"bufio"
	"bytes"
	"net/url"
	"regexp"
	"strings"
)

// MaxIncludes limits how many parts one list may pull in.
const MaxIncludes = 32

var includeName = regexp.MustCompile(`^[A-Za-z0-9._/-]+$`)

// FindIncludes returns the files a uBlock Origin list pulls in with "!#include name".
func FindIncludes(body []byte) []string {
	var out []string
	seen := map[string]bool{}
	sc := bufio.NewScanner(bytes.NewReader(body))
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		l := strings.TrimSpace(sc.Text())
		if !strings.HasPrefix(l, "!#include ") {
			continue
		}
		n := strings.TrimSpace(strings.TrimPrefix(l, "!#include "))
		if n == "" || seen[n] {
			continue
		}
		seen[n] = true
		out = append(out, n)
		if len(out) == MaxIncludes {
			break
		}
	}
	return out
}

// ResolveInclude returns the URL of name next to base, or "" if name is not a
// plain relative file on the same https host.
func ResolveInclude(base, name string) string {
	if strings.Contains(name, "..") || strings.Contains(name, "://") || strings.HasPrefix(name, "/") ||
		strings.Contains(name, `\`) || !includeName.MatchString(name) {
		return ""
	}
	b, err := url.Parse(base)
	if err != nil || b.Scheme != "https" {
		return ""
	}
	r, err := b.Parse(name)
	if err != nil || r.Host != b.Host || r.Scheme != "https" {
		return ""
	}
	return r.String()
}
