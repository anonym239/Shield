package dnsx

import (
	"encoding/binary"
	"testing"
	"time"
)

func TestQueryAndBlocked(t *testing.T) {
	q := BuildQuery(0x1234, "Ads.Example.com", TypeA)
	p := ParseQuery(q)
	if p == nil || p.Name != "ads.example.com" || p.Type != TypeA {
		t.Fatalf("parse %+v", p)
	}
	r := Blocked(q, p)
	if binary.BigEndian.Uint16(r) != 0x1234 || Rcode(r) != 0 || MinTTL(r) != 300 {
		t.Fatal("blocked answer wrong")
	}
	for _, b := range r[len(r)-4:] {
		if b != 0 {
			t.Fatal("not 0.0.0.0")
		}
	}
	if ParseQuery(r) != nil {
		t.Fatal("response accepted as query")
	}
	if Rcode(Servfail(q, p)) != RcodeFail {
		t.Fatal("servfail")
	}
}

func cname(q []byte, target string) []byte {
	p := ParseQuery(q)
	out := append([]byte(nil), q[:p.QuestionEnd]...)
	binary.BigEndian.PutUint16(out[2:], 0x8180)
	binary.BigEndian.PutUint16(out[6:], 1)
	rd := BuildQuery(0, target, 0)[12:]
	rd = rd[:len(rd)-4]
	rr := []byte{0xC0, 0x0C, 0, TypeCNAME, 0, 1, 0, 0, 0, 60, 0, byte(len(rd))}
	return append(append(out, rr...), rd...)
}

func TestCNAMEAndCache(t *testing.T) {
	q := BuildQuery(1, "metrics.shop.de", TypeA)
	r := cname(q, "x.tracker.com")
	if got := CNAMETargets(r); len(got) != 1 || got[0] != "x.tracker.com" {
		t.Fatalf("cname %v", got)
	}
	now := time.Unix(0, 0)
	c := NewCache(10)
	c.now = func() time.Time { return now }
	c.Put("metrics.shop.de", TypeA, r)
	if c.Get("metrics.shop.de", TypeA, 7) == nil {
		t.Fatal("cache miss")
	}
	now = now.Add(61 * time.Second)
	if c.Get("metrics.shop.de", TypeA, 7) != nil {
		t.Fatal("ttl ignored")
	}
}
