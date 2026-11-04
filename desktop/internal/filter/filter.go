// Package filter is the domain filter of Shield for Windows: list parser
// (hosts, plain domains, AdGuard base syntax) and the decision engine.
// It mirrors app/src/main/java/dev/shield/core/filter on Android.
//
// SPDX-License-Identifier: GPL-3.0-or-later
package filter

import (
	"bufio"
	"io"
	"regexp"
	"strings"
	"sync"
)

// Category of a list; phishing is shown separately.
type Category string

const (
	Ads      Category = "ADS"
	Tracking Category = "TRACKING"
	Malware  Category = "MALWARE"
	Phishing Category = "PHISHING"
	Custom   Category = "CUSTOM"
)

// SelftestDomain is always blocked (self-test).
const SelftestDomain = "selftest.shield.invalid"

var labelRe = regexp.MustCompile(`^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$`)

// Normalize lower-cases and validates a domain; "" if invalid.
func Normalize(in string) string {
	d := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(in)), ".")
	if d == "" || len(d) > 253 {
		return ""
	}
	labels := strings.Split(d, ".")
	if len(labels) < 2 {
		return ""
	}
	for _, l := range labels {
		if !labelRe.MatchString(l) {
			return ""
		}
	}
	return d
}

// Parsed is the result of parsing one list.
type Parsed struct {
	Blocked map[string]struct{}
	Allowed map[string]struct{}
	Skipped int
}

var hostsIPs = map[string]bool{"0.0.0.0": true, "127.0.0.1": true, "::": true, "::1": true, "0": true, "::0": true}
var ignored = map[string]bool{
	"localhost": true, "localhost.localdomain": true, "local": true, "broadcasthost": true,
	"ip6-localhost": true, "ip6-loopback": true, "0.0.0.0": true,
}
var allowedMods = map[string]bool{"important": true, "all": true, "dnstype=a": true, "dnstype=aaaa": true}
var aggressiveMods = map[string]bool{"third-party": true, "3p": true}

// Parse reads a list. aggressive also accepts $third-party rules (a DNS filter
// cannot tell first- from third-party requests).
func Parse(r io.Reader, aggressive bool) Parsed {
	p := Parsed{Blocked: map[string]struct{}{}, Allowed: map[string]struct{}{}}
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		kind, d := ParseLine(sc.Text(), aggressive)
		switch kind {
		case 'B':
			p.Blocked[d] = struct{}{}
		case 'A':
			p.Allowed[d] = struct{}{}
		case 'S':
			p.Skipped++
		}
	}
	return p
}

// ParseLine returns 'B' (block), 'A' (allow), 'S' (skipped) or 0 (empty/comment).
func ParseLine(raw string, aggressive bool) (byte, string) {
	line := strings.TrimSpace(raw)
	if line == "" || line[0] == '!' || line[0] == '#' || line[0] == '[' {
		return 0, ""
	}
	if i := strings.Index(line, " #"); i > 0 {
		line = strings.TrimSpace(line[:i])
	}
	if strings.HasPrefix(line, "@@||") {
		if d := adguard(line[4:], false); d != "" {
			return 'A', d
		}
		return 'S', ""
	}
	if strings.HasPrefix(line, "||") {
		if d := adguard(line[2:], aggressive); d != "" {
			return 'B', d
		}
		return 'S', ""
	}
	if strings.HasPrefix(line, "@@") || strings.HasPrefix(line, "/") || strings.Contains(line, "##") || strings.Contains(line, "#@#") {
		return 'S', ""
	}
	parts := strings.Fields(line)
	if len(parts) >= 2 && hostsIPs[parts[0]] {
		if ignored[parts[1]] {
			return 0, ""
		}
		if d := Normalize(parts[1]); d != "" {
			return 'B', d
		}
		return 'S', ""
	}
	if len(parts) == 1 {
		t := strings.TrimPrefix(parts[0], "*.")
		if ignored[t] {
			return 0, ""
		}
		if d := Normalize(t); d != "" {
			return 'B', d
		}
	}
	return 'S', ""
}

func adguard(rest string, aggressive bool) string {
	body := rest
	if i := strings.IndexByte(body, '$'); i >= 0 {
		for _, m := range strings.Split(body[i+1:], ",") {
			m = strings.ToLower(strings.TrimSpace(m))
			if !allowedMods[m] && !(aggressive && aggressiveMods[m]) {
				return ""
			}
		}
		body = body[:i]
	}
	body = strings.TrimSuffix(body, "^|")
	body = strings.TrimSuffix(body, "^")
	body = strings.TrimSuffix(body, "|")
	if strings.ContainsAny(body, "/*^:?=") {
		return ""
	}
	return Normalize(body)
}

// List is a loaded subscription.
type List struct {
	ID, Name string
	Category Category
	Parsed   Parsed
}

// Rule is an own rule: "example.com", "*.example.com" or a glob "ads*.example.com".
type Rule struct {
	Pattern string `json:"pattern"`
	Allow   bool   `json:"allow"`
}

// Decision of the engine.
type Decision struct {
	Blocked  bool
	Reason   string // CUSTOM_ALLOW, CUSTOM_BLOCK, LIST_EXCEPTION, LIST, SELFTEST, NONE
	Rule     string
	Category Category
}

type ruleSet struct {
	full, sub map[string]string
	globs     []globRule
}

type globRule struct {
	re    *regexp.Regexp
	label string
}

type snapshot struct {
	names      []string
	cats       []Category
	blocked    map[string]int
	exceptions map[string]int
	allow      ruleSet
	block      ruleSet
	counts     map[string]int
	total      int
}

// Engine is safe for concurrent use; it swaps immutable snapshots.
type Engine struct {
	mu    sync.Mutex
	lists []List
	rules []Rule
	snap  *snapshot
}

func NewEngine() *Engine {
	e := &Engine{}
	e.snap = build(nil, nil)
	return e
}

func (e *Engine) SetLists(l []List) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.lists = l
	e.snap = build(e.lists, e.rules)
}

func (e *Engine) SetRules(r []Rule) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.rules = r
	e.snap = build(e.lists, e.rules)
}

func (e *Engine) RuleCount() int             { e.mu.Lock(); defer e.mu.Unlock(); return e.snap.total }
func (e *Engine) ListCounts() map[string]int { e.mu.Lock(); defer e.mu.Unlock(); return e.snap.counts }

func suffixes(d string, fn func(string) bool) bool {
	start := 0
	for {
		if fn(d[start:]) {
			return true
		}
		i := strings.IndexByte(d[start:], '.')
		if i < 0 {
			return false
		}
		start += i + 1
	}
}

func (rs ruleSet) match(d string) string {
	hit := ""
	suffixes(d, func(s string) bool {
		if v, ok := rs.full[s]; ok {
			hit = v
			return true
		}
		if s != d {
			if v, ok := rs.sub[s]; ok {
				hit = v
				return true
			}
		}
		return false
	})
	if hit != "" {
		return hit
	}
	for _, g := range rs.globs {
		if g.re.MatchString(d) {
			return g.label
		}
	}
	return ""
}

// Decide: own allow > own block > list exceptions > lists > allow.
func (e *Engine) Decide(domain string) Decision {
	d := strings.TrimSuffix(strings.ToLower(domain), ".")
	if d == SelftestDomain || strings.HasSuffix(d, "."+SelftestDomain) {
		return Decision{Blocked: true, Reason: "SELFTEST", Rule: "Selbsttest"}
	}
	e.mu.Lock()
	s := e.snap
	e.mu.Unlock()
	if r := s.allow.match(d); r != "" {
		return Decision{Reason: "CUSTOM_ALLOW", Rule: r, Category: Custom}
	}
	if r := s.block.match(d); r != "" {
		return Decision{Blocked: true, Reason: "CUSTOM_BLOCK", Rule: r, Category: Custom}
	}
	idx := -1
	suffixes(d, func(x string) bool {
		if i, ok := s.exceptions[x]; ok {
			idx = i
			return true
		}
		return false
	})
	if idx >= 0 {
		return Decision{Reason: "LIST_EXCEPTION", Rule: s.names[idx]}
	}
	suffixes(d, func(x string) bool {
		if i, ok := s.blocked[x]; ok {
			idx = i
			return true
		}
		return false
	})
	if idx >= 0 {
		return Decision{Blocked: true, Reason: "LIST", Rule: s.names[idx], Category: s.cats[idx]}
	}
	return Decision{Reason: "NONE"}
}

func globRe(p string) *regexp.Regexp {
	parts := strings.Split(p, "*")
	for i := range parts {
		parts[i] = regexp.QuoteMeta(parts[i])
	}
	return regexp.MustCompile("^" + strings.Join(parts, "[a-z0-9_.-]*") + "$")
}

func build(lists []List, rules []Rule) *snapshot {
	s := &snapshot{blocked: map[string]int{}, exceptions: map[string]int{}, counts: map[string]int{},
		allow: ruleSet{full: map[string]string{}, sub: map[string]string{}},
		block: ruleSet{full: map[string]string{}, sub: map[string]string{}}}
	for i, l := range lists {
		s.names = append(s.names, l.Name)
		s.cats = append(s.cats, l.Category)
		for d := range l.Parsed.Blocked {
			if _, ok := s.blocked[d]; !ok {
				s.blocked[d] = i
			}
		}
		for d := range l.Parsed.Allowed {
			if _, ok := s.exceptions[d]; !ok {
				s.exceptions[d] = i
			}
		}
		s.counts[l.ID] = len(l.Parsed.Blocked)
	}
	for _, r := range rules {
		p := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(r.Pattern)), ".")
		label := "Eigene Regel: " + p
		rs := &s.block
		if r.Allow {
			rs = &s.allow
		}
		switch {
		case strings.HasPrefix(p, "*.") && !strings.Contains(p[2:], "*"):
			rs.sub[p[2:]] = label
		case strings.Contains(p, "*"):
			rs.globs = append(rs.globs, globRule{globRe(p), label})
		default:
			rs.full[p] = label
		}
	}
	s.total = len(s.blocked) + len(rules)
	return s
}

var patternRe = regexp.MustCompile(`^[a-z0-9*_.-]+$`)

// ValidPattern checks an own rule.
func ValidPattern(p string) bool {
	t := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(p)), ".")
	return t != "" && len(t) <= 253 && patternRe.MatchString(t) && strings.Contains(t, ".") &&
		!strings.HasPrefix(t, ".") && !strings.Contains(t, "..")
}
