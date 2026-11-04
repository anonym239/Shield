package shieldcore

import "testing"

type fakeBridge struct{ allow bool }

func (f *fakeBridge) Protect(fd int32) bool                             { return true }
func (f *fakeBridge) FindUid(int32, string, int32, string, int32) int32 { return 10100 }
func (f *fakeBridge) AllowFlow(int32, int32, string, int32) bool        { return f.allow }
func (f *fakeBridge) FilterDns(int32, []byte) []byte                    { return nil }
func (f *fakeBridge) DnsAnswered(int32, []byte, []byte, int64) []byte   { return nil }
func (f *fakeBridge) Log(int32, string)                                 {}

func TestAllowRules(t *testing.T) {
	tun := &Tunnel{cfg: &Config{BlockDnsBypass: true}, bridge: &fakeBridge{allow: true}}
	if tun.allow(ProtoTCP, 1, "1.1.1.1", 853) {
		t.Fatal("DoT must be blocked when BlockDnsBypass is set")
	}
	if !tun.allow(ProtoTCP, 1, "1.1.1.1", 443) {
		t.Fatal("HTTPS must pass")
	}
	tun.panicMode.Store(true)
	if tun.allow(ProtoTCP, 1, "1.1.1.1", 443) {
		t.Fatal("panic mode must drop everything")
	}
	tun.panicMode.Store(false)
	tun.bridge = &fakeBridge{allow: false}
	if tun.allow(ProtoUDP, 1, "8.8.8.8", 443) {
		t.Fatal("firewall verdict ignored")
	}
	if n := tun.flowsBlocked.Load(); n != 3 {
		t.Fatalf("blocked counter %d", n)
	}
}

func TestVirtualDNS(t *testing.T) {
	tun := &Tunnel{cfg: &Config{VirtualDNS4: "10.111.222.3", VirtualDNS6: "fd00:5:1::3"}}
	a, _ := parseAddrs("10.111.222.3, fd00:5:1::3, 9.9.9.9")
	if !tun.isVirtualDNS(a[0]) || !tun.isVirtualDNS(a[1]) || tun.isVirtualDNS(a[2]) {
		t.Fatal("virtual DNS detection wrong")
	}
}
