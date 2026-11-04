// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/anonym239/shield/desktop/internal/dnsx"
	"github.com/anonym239/shield/desktop/internal/filter"
)

// QueryEntry is one line of the protocol.
type QueryEntry struct {
	Time    int64  `json:"time"`
	Domain  string `json:"domain"`
	Type    string `json:"type"`
	Result  string `json:"result"` // ALLOWED, BLOCKED, ERROR
	Rule    string `json:"rule,omitempty"`
	Latency int64  `json:"latency"`
}

// HourStat aggregates counters per hour (no domains).
type HourStat struct {
	Hour     int64 `json:"hour"`
	Queries  int64 `json:"queries"`
	Blocked  int64 `json:"blocked"`
	Phishing int64 `json:"phishing"`
}

type resolver struct {
	engine *filter.Engine
	cache  *dnsx.Cache
	events *eventLog

	mu       sync.Mutex
	upstream *doh
	fallback *doh
	downTill time.Time // primary skipped until then
	ring     []QueryEntry
	hours    map[int64]*HourStat
	blockedD map[string]int64 // today's blocked domains (top list)
	day      string
	statsDir string

	noHistory   atomic.Bool
	panic       atomic.Bool
	selftestHit atomic.Int64
	queries     atomic.Int64

	conns []io.Closer
}

func newResolver(engine *filter.Engine, ev *eventLog, dir string) *resolver {
	r := &resolver{engine: engine, cache: dnsx.NewCache(4096), events: ev, hours: map[int64]*HourStat{},
		blockedD: map[string]int64{}, statsDir: dir}
	if b, err := os.ReadFile(filepath.Join(dir, "stats.json")); err == nil {
		var hs []HourStat
		if json.Unmarshal(b, &hs) == nil {
			for i := range hs {
				h := hs[i]
				r.hours[h.Hour] = &h
			}
		}
	}
	return r
}

func typeName(t uint16) string {
	switch t {
	case 1:
		return "A"
	case 28:
		return "AAAA"
	case 5:
		return "CNAME"
	case 65:
		return "HTTPS"
	case 12:
		return "PTR"
	case 16:
		return "TXT"
	case 33:
		return "SRV"
	}
	return "TYPE"
}

func (r *resolver) setUpstream(d, fallback *doh) {
	r.mu.Lock()
	old, oldFB := r.upstream, r.fallback
	r.upstream, r.fallback, r.downTill = d, fallback, time.Time{}
	r.mu.Unlock()
	if old != nil {
		old.close()
	}
	if oldFB != nil {
		oldFB.close()
	}
	r.cache.Clear()
}

// query asks the primary DoH server, falling back to the second encrypted server.
func (r *resolver) query(ctx context.Context, q []byte) ([]byte, error) {
	r.mu.Lock()
	up, fb, down := r.upstream, r.fallback, r.downTill
	r.mu.Unlock()
	if up == nil {
		return nil, errors.New("kein Upstream")
	}
	if fb == nil {
		return up.query(ctx, q)
	}
	if time.Now().After(down) {
		// a hanging primary must not exceed the Windows resolver timeout
		pctx, cancel := context.WithTimeout(ctx, 2500*time.Millisecond)
		ans, err := up.query(pctx, q)
		cancel()
		if err == nil {
			return ans, nil
		}
		r.mu.Lock()
		r.downTill = time.Now().Add(60 * time.Second)
		r.mu.Unlock()
		logf("DoH-Hauptserver gestört, nutze Ausweichserver: %v", err)
	}
	ans, err := fb.query(ctx, q)
	if err != nil {
		r.mu.Lock()
		r.downTill = time.Time{}
		r.mu.Unlock()
	}
	return ans, err
}

func (r *resolver) record(name string, typ uint16, result, rule string, cat filter.Category, latency int64) {
	r.queries.Add(1)
	if name == filter.SelftestDomain || len(name) > len(filter.SelftestDomain) && name[len(name)-len(filter.SelftestDomain)-1:] == "."+filter.SelftestDomain {
		r.selftestHit.Store(time.Now().UnixMilli())
	}
	now := time.Now()
	h := now.Unix() / 3600
	r.mu.Lock()
	defer r.mu.Unlock()
	st := r.hours[h]
	if st == nil {
		st = &HourStat{Hour: h}
		r.hours[h] = st
	}
	st.Queries++
	if result == "BLOCKED" {
		st.Blocked++
		if cat == filter.Phishing {
			st.Phishing++
		}
		if day := now.Format("20060102"); day != r.day {
			r.day, r.blockedD = day, map[string]int64{}
		}
		if !r.noHistory.Load() {
			r.blockedD[name]++
		}
	}
	if r.noHistory.Load() {
		return
	}
	r.ring = append(r.ring, QueryEntry{now.UnixMilli(), name, typeName(typ), result, rule, latency})
	if len(r.ring) > 3000 {
		r.ring = r.ring[len(r.ring)-3000:]
	}
}

// resolve answers one DNS query.
func (r *resolver) resolve(ctx context.Context, q []byte) []byte {
	p := dnsx.ParseQuery(q)
	if p == nil {
		return nil
	}
	if r.panic.Load() {
		r.record(p.Name, p.Type, "BLOCKED", "Panik-Modus", "", 0)
		return dnsx.Servfail(q, p)
	}
	d := r.engine.Decide(p.Name)
	if d.Blocked {
		r.record(p.Name, p.Type, "BLOCKED", d.Rule, d.Category, 0)
		return dnsx.Blocked(q, p)
	}
	if c := r.cache.Get(p.Name, p.Type, p.ID); c != nil {
		r.record(p.Name, p.Type, "ALLOWED", "Cache", "", 0)
		return c
	}
	start := time.Now()
	ans, err := r.query(ctx, q)
	lat := time.Since(start).Milliseconds()
	if err != nil {
		r.record(p.Name, p.Type, "ERROR", err.Error(), "", lat)
		return dnsx.Servfail(q, p)
	}
	ans = dnsx.WithID(ans, p.ID)
	for _, t := range dnsx.CNAMETargets(ans) {
		if cd := r.engine.Decide(t); cd.Blocked {
			r.record(p.Name, p.Type, "BLOCKED", "CNAME → "+t+" ("+cd.Rule+")", cd.Category, lat)
			return dnsx.Blocked(q, p)
		}
	}
	r.cache.Put(p.Name, p.Type, ans)
	rule := ""
	if d.Reason == "CUSTOM_ALLOW" || d.Reason == "LIST_EXCEPTION" {
		rule = d.Rule
	}
	r.record(p.Name, p.Type, "ALLOWED", rule, "", lat)
	return ans
}

// listen starts UDP+TCP servers on 127.0.0.1:53 and [::1]:53.
func (r *resolver) listen(ctx context.Context, addrs []string) error {
	var started int
	var lastErr error
	for _, a := range addrs {
		pc, err := net.ListenPacket("udp", a)
		if err != nil {
			lastErr = err
			continue
		}
		ln, err := net.Listen("tcp", a)
		if err != nil {
			pc.Close()
			lastErr = err
			continue
		}
		r.conns = append(r.conns, pc, ln)
		go r.serveUDP(ctx, pc)
		go r.serveTCP(ctx, ln)
		started++
	}
	if started == 0 {
		return errors.New("DNS-Port 53 ist belegt: " + errString(lastErr))
	}
	return nil
}

func errString(e error) string {
	if e == nil {
		return ""
	}
	return e.Error()
}

func (r *resolver) close() {
	for _, c := range r.conns {
		_ = c.Close()
	}
	r.conns = nil
	r.saveStats()
}

func (r *resolver) serveUDP(ctx context.Context, pc net.PacketConn) {
	buf := make([]byte, 4096)
	for {
		n, addr, err := pc.ReadFrom(buf)
		if err != nil {
			return
		}
		q := append([]byte(nil), buf[:n]...)
		go func() {
			c, cancel := context.WithTimeout(ctx, 10*time.Second)
			defer cancel()
			if ans := r.resolve(c, q); ans != nil {
				_, _ = pc.WriteTo(ans, addr)
			}
		}()
	}
}

func (r *resolver) serveTCP(ctx context.Context, ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			for {
				_ = c.SetDeadline(time.Now().Add(10 * time.Second))
				var l [2]byte
				if _, err := io.ReadFull(c, l[:]); err != nil {
					return
				}
				q := make([]byte, binary.BigEndian.Uint16(l[:]))
				if _, err := io.ReadFull(c, q); err != nil {
					return
				}
				cc, cancel := context.WithTimeout(ctx, 10*time.Second)
				ans := r.resolve(cc, q)
				cancel()
				if ans == nil {
					return
				}
				if _, err := c.Write(append([]byte{byte(len(ans) >> 8), byte(len(ans))}, ans...)); err != nil {
					return
				}
			}
		}()
	}
}

// probe sends a query directly to the upstream (self-test 4).
func (r *resolver) probe(ctx context.Context) (int64, error) {
	start := time.Now()
	ans, err := r.query(ctx, dnsx.BuildQuery(uint16(time.Now().UnixNano()), "example.com", dnsx.TypeA))
	if err != nil {
		return 0, err
	}
	if dnsx.Rcode(ans) != 0 {
		return 0, errors.New("Upstream-Fehler")
	}
	return time.Since(start).Milliseconds(), nil
}

func (r *resolver) saveStats() {
	r.mu.Lock()
	cut := time.Now().Unix()/3600 - 90*24
	var hs []HourStat
	for h, s := range r.hours {
		if h < cut {
			delete(r.hours, h)
			continue
		}
		hs = append(hs, *s)
	}
	r.mu.Unlock()
	b, _ := json.Marshal(hs)
	_ = os.WriteFile(filepath.Join(r.statsDir, "stats.json"), b, 0o600)
}

func (r *resolver) snapshot(limit int, filterResult, search string) []QueryEntry {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := []QueryEntry{}
	for i := len(r.ring) - 1; i >= 0 && len(out) < limit; i-- {
		e := r.ring[i]
		if filterResult != "" && e.Result != filterResult {
			continue
		}
		if search != "" && !containsFold(e.Domain, search) {
			continue
		}
		out = append(out, e)
	}
	return out
}

func containsFold(s, sub string) bool {
	return len(sub) == 0 || indexFold(s, sub) >= 0
}

func indexFold(s, sub string) int {
	ls, lsub := []rune(toLower(s)), []rune(toLower(sub))
	for i := 0; i+len(lsub) <= len(ls); i++ {
		if string(ls[i:i+len(lsub)]) == string(lsub) {
			return i
		}
	}
	return -1
}

func toLower(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + 32
		}
	}
	return string(b)
}

func (r *resolver) last24h() []HourStat {
	now := time.Now().Unix() / 3600
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]HourStat, 0, 24)
	for h := now - 23; h <= now; h++ {
		if s := r.hours[h]; s != nil {
			out = append(out, *s)
		} else {
			out = append(out, HourStat{Hour: h})
		}
	}
	return out
}

type DomainCount struct {
	Domain string `json:"domain"`
	Count  int64  `json:"count"`
}

func (r *resolver) topBlocked(n int) []DomainCount {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := []DomainCount{}
	for d, c := range r.blockedD {
		out = append(out, DomainCount{d, c})
	}
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].Count > out[i].Count {
				out[i], out[j] = out[j], out[i]
			}
		}
		if i >= n {
			break
		}
	}
	if len(out) > n {
		out = out[:n]
	}
	return out
}

func (r *resolver) clearHistory() {
	r.mu.Lock()
	r.ring = nil
	r.blockedD = map[string]int64{}
	r.hours = map[int64]*HourStat{}
	r.mu.Unlock()
	r.saveStats()
}
