// Package dnsx is a minimal DNS wire codec for Shield for Windows
// (queries, synthetic blocked answers, TTL and CNAME inspection) plus a TTL cache.
//
// SPDX-License-Identifier: GPL-3.0-or-later
package dnsx

import (
	"encoding/binary"
	"strings"
	"sync"
	"time"
)

const (
	TypeA     = 1
	TypeCNAME = 5
	TypeAAAA  = 28
	RcodeOK   = 0
	RcodeFail = 2
	RcodeNX   = 3
)

// Query is the first question of a request.
type Query struct {
	ID          uint16
	Flags       uint16
	Name        string
	Type        uint16
	QuestionEnd int
}

// ParseQuery returns nil for responses, non-standard opcodes and malformed packets.
func ParseQuery(b []byte) *Query {
	if len(b) < 17 {
		return nil
	}
	flags := binary.BigEndian.Uint16(b[2:])
	if flags&0x8000 != 0 || (flags>>11)&0xF != 0 || binary.BigEndian.Uint16(b[4:]) < 1 {
		return nil
	}
	var sb strings.Builder
	i := 12
	for {
		if i >= len(b) {
			return nil
		}
		l := int(b[i])
		if l == 0 {
			i++
			break
		}
		if l&0xC0 != 0 || l > 63 || i+1+l > len(b) {
			return nil
		}
		if sb.Len() > 0 {
			sb.WriteByte('.')
		}
		sb.WriteString(strings.ToLower(string(b[i+1 : i+1+l])))
		i += l + 1
		if sb.Len() > 253 {
			return nil
		}
	}
	if i+4 > len(b) {
		return nil
	}
	return &Query{ID: binary.BigEndian.Uint16(b), Flags: flags, Name: sb.String(), Type: binary.BigEndian.Uint16(b[i:]), QuestionEnd: i + 4}
}

func header(q []byte, p *Query, rcode uint16, answers uint16) []byte {
	out := make([]byte, p.QuestionEnd)
	copy(out, q[:p.QuestionEnd])
	binary.BigEndian.PutUint16(out[2:], 0x8000|(p.Flags&0x0100)|0x0080|rcode)
	binary.BigEndian.PutUint16(out[4:], 1)
	binary.BigEndian.PutUint16(out[6:], answers)
	binary.BigEndian.PutUint16(out[8:], 0)
	binary.BigEndian.PutUint16(out[10:], 0)
	return out
}

// Blocked builds 0.0.0.0 / :: for A/AAAA and an empty NOERROR answer otherwise.
func Blocked(q []byte, p *Query) []byte {
	var rdata []byte
	switch p.Type {
	case TypeA:
		rdata = make([]byte, 4)
	case TypeAAAA:
		rdata = make([]byte, 16)
	default:
		return header(q, p, RcodeOK, 0)
	}
	out := header(q, p, RcodeOK, 1)
	rr := make([]byte, 12+len(rdata))
	rr[0], rr[1] = 0xC0, 0x0C
	binary.BigEndian.PutUint16(rr[2:], p.Type)
	binary.BigEndian.PutUint16(rr[4:], 1)
	binary.BigEndian.PutUint32(rr[6:], 300)
	binary.BigEndian.PutUint16(rr[10:], uint16(len(rdata)))
	copy(rr[12:], rdata)
	return append(out, rr...)
}

func Servfail(q []byte, p *Query) []byte { return header(q, p, RcodeFail, 0) }

func Rcode(r []byte) int {
	if len(r) < 4 {
		return -1
	}
	return int(r[3] & 0x0F)
}

// WithID returns a copy with the transaction id replaced.
func WithID(r []byte, id uint16) []byte {
	c := append([]byte(nil), r...)
	if len(c) >= 2 {
		binary.BigEndian.PutUint16(c, id)
	}
	return c
}

func skipName(b []byte, i int) int {
	for i < len(b) {
		l := int(b[i])
		switch {
		case l == 0:
			return i + 1
		case l&0xC0 == 0xC0:
			return i + 2
		default:
			i += l + 1
		}
	}
	return -1
}

func readName(b []byte, i int) string {
	var sb strings.Builder
	for jumps := 0; i < len(b); {
		l := int(b[i])
		switch {
		case l == 0:
			return strings.ToLower(sb.String())
		case l&0xC0 == 0xC0:
			if i+1 >= len(b) || jumps > 16 {
				return ""
			}
			jumps++
			i = (l&0x3F)<<8 | int(b[i+1])
		default:
			if i+1+l > len(b) {
				return ""
			}
			if sb.Len() > 0 {
				sb.WriteByte('.')
			}
			sb.Write(b[i+1 : i+1+l])
			i += l + 1
		}
	}
	return ""
}

// walk iterates the answer records.
func walk(r []byte, fn func(typ uint16, ttl uint32, rdStart, rdLen int)) bool {
	if len(r) < 12 {
		return false
	}
	qd, an := int(binary.BigEndian.Uint16(r[4:])), int(binary.BigEndian.Uint16(r[6:]))
	i := 12
	for k := 0; k < qd; k++ {
		if i = skipName(r, i); i < 0 {
			return false
		}
		i += 4
	}
	for k := 0; k < an; k++ {
		if i = skipName(r, i); i < 0 || i+10 > len(r) {
			return false
		}
		typ := binary.BigEndian.Uint16(r[i:])
		ttl := binary.BigEndian.Uint32(r[i+4:])
		rdl := int(binary.BigEndian.Uint16(r[i+8:]))
		if i+10+rdl > len(r) {
			return false
		}
		fn(typ, ttl, i+10, rdl)
		i += 10 + rdl
	}
	return true
}

// MinTTL of the answers, -1 if none.
func MinTTL(r []byte) int {
	min := -1
	walk(r, func(_ uint16, ttl uint32, _, _ int) {
		if min < 0 || int(ttl) < min {
			min = int(ttl)
		}
	})
	return min
}

// CNAMETargets lists the names the answer points to (CNAME cloaking detection).
func CNAMETargets(r []byte) []string {
	var out []string
	walk(r, func(typ uint16, _ uint32, rd, _ int) {
		if typ == TypeCNAME {
			if n := readName(r, rd); n != "" {
				out = append(out, n)
			}
		}
	})
	return out
}

// BuildQuery creates a query packet.
func BuildQuery(id uint16, name string, typ uint16) []byte {
	b := make([]byte, 12, 64)
	binary.BigEndian.PutUint16(b, id)
	binary.BigEndian.PutUint16(b[2:], 0x0100)
	binary.BigEndian.PutUint16(b[4:], 1)
	for _, l := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		b = append(b, byte(len(l)))
		b = append(b, l...)
	}
	b = append(b, 0, byte(typ>>8), byte(typ), 0, 1)
	return b
}

// Cache stores upstream answers respecting the TTL (30 s – 1 h).
type Cache struct {
	mu  sync.Mutex
	m   map[string]entry
	max int
	now func() time.Time
}

type entry struct {
	resp []byte
	exp  time.Time
}

func NewCache(max int) *Cache { return &Cache{m: map[string]entry{}, max: max, now: time.Now} }

func key(name string, typ uint16) string { return name + "|" + string(rune(typ)) }

func (c *Cache) Get(name string, typ, id uint16) []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[key(name, typ)]
	if !ok {
		return nil
	}
	if !c.now().Before(e.exp) {
		delete(c.m, key(name, typ))
		return nil
	}
	return WithID(e.resp, id)
}

func (c *Cache) Put(name string, typ uint16, resp []byte) {
	ttl := 60
	switch Rcode(resp) {
	case RcodeOK:
		if t := MinTTL(resp); t >= 0 {
			ttl = t
		}
		if ttl < 30 {
			ttl = 30
		}
		if ttl > 3600 {
			ttl = 3600
		}
	case RcodeNX:
		ttl = 60
	default:
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.m) >= c.max {
		c.m = map[string]entry{} // simple full flush keeps memory bounded
	}
	c.m[key(name, typ)] = entry{append([]byte(nil), resp...), c.now().Add(time.Duration(ttl) * time.Second)}
}

func (c *Cache) Clear() { c.mu.Lock(); c.m = map[string]entry{}; c.mu.Unlock() }
