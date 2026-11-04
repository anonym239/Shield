// SPDX-License-Identifier: GPL-3.0-or-later
package shieldcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	nicID          = 1
	udpIdleTimeout = 60 * time.Second
	dnsIdleTimeout = 10 * time.Second
	tcpDialTimeout = 15 * time.Second
	// handshakeMaxAge is the age after which WireGuard counts as disconnected.
	handshakeMaxAge = 180 * time.Second
)

// Tunnel is a running user-space tunnel.
type Tunnel struct {
	cfg    *Config
	bridge Bridge
	stack  *stack.Stack
	doh    *dohClient
	dohFB  *dohClient
	wg     *wgTunnel

	primaryDownUntil atomic.Int64

	ctx    context.Context
	cancel context.CancelFunc

	panicMode  atomic.Bool
	killSwitch atomic.Bool

	connsMu sync.Mutex
	conns   map[io.Closer]struct{}

	flowsTotal   atomic.Int64
	flowsBlocked atomic.Int64
	dnsQueries   atomic.Int64
	bytesUp      atomic.Int64
	bytesDown    atomic.Int64
	closed       atomic.Bool
}

func newTunnel(cfg *Config, bridge Bridge) (*Tunnel, error) {
	if cfg.Fd <= 0 {
		return nil, errors.New("invalid tun fd")
	}
	mtu := uint32(cfg.Mtu)
	if mtu == 0 {
		mtu = 1500
	}
	ctx, cancel := context.WithCancel(context.Background())
	t := &Tunnel{cfg: cfg, bridge: bridge, ctx: ctx, cancel: cancel, conns: map[io.Closer]struct{}{}}
	t.killSwitch.Store(cfg.KillSwitch)

	if strings.TrimSpace(cfg.WgUapi) != "" {
		wg, err := startWireGuard(cfg, bridge)
		if err != nil {
			cancel()
			return nil, fmt.Errorf("wireguard: %w", err)
		}
		t.wg = wg
	}

	doh, err := newDohClient(cfg.DohURL, cfg.DohBootstrap, t.dialUpstream)
	if err != nil {
		t.shutdownPartial()
		return nil, err
	}
	t.doh = doh
	if cfg.DohFallbackURL != "" {
		if fb, err := newDohClient(cfg.DohFallbackURL, cfg.DohFallbackBootstrap, t.dialUpstream); err == nil {
			t.dohFB = fb
		}
	}

	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
		HandleLocal:        false,
	})
	t.stack = s

	ep, err := fdbased.New(&fdbased.Options{
		FDs:                []int{int(cfg.Fd)},
		MTU:                mtu,
		PacketDispatchMode: fdbased.Readv,
		RXChecksumOffload:  true,
	})
	if err != nil {
		t.shutdownPartial()
		return nil, fmt.Errorf("fdbased: %w", err)
	}
	if e := s.CreateNIC(nicID, ep); e != nil {
		t.shutdownPartial()
		return nil, fmt.Errorf("create nic: %v", e)
	}
	// Accept packets for every destination and answer from every address.
	if e := s.SetPromiscuousMode(nicID, true); e != nil {
		t.shutdownPartial()
		return nil, fmt.Errorf("promiscuous: %v", e)
	}
	if e := s.SetSpoofing(nicID, true); e != nil {
		t.shutdownPartial()
		return nil, fmt.Errorf("spoofing: %v", e)
	}
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)

	tcpFwd := tcp.NewForwarder(s, 0, 2048, t.handleTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(s, t.handleUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	t.log(LevelInfo, fmt.Sprintf("tunnel started (mtu %d, wireguard %v, kill-switch %v)", mtu, t.wg != nil, cfg.KillSwitch))
	return t, nil
}

func (t *Tunnel) shutdownPartial() {
	t.cancel()
	if t.wg != nil {
		t.wg.close()
	}
	if t.doh != nil {
		t.doh.close()
	}
}

func (t *Tunnel) log(level int32, msg string) {
	defer func() { _ = recover() }()
	t.bridge.Log(level, msg)
}

// Close stops the tunnel and all flows. Safe to call more than once.
func (t *Tunnel) Close() {
	if !t.closed.CompareAndSwap(false, true) {
		return
	}
	t.cancel()
	t.closeAllConns()
	if t.stack != nil {
		t.stack.Close()
		t.stack.Wait()
	}
	if t.wg != nil {
		t.wg.close()
	}
	if t.doh != nil {
		t.doh.close()
	}
	if t.dohFB != nil {
		t.dohFB.close()
	}
	mu.Lock()
	if current == t {
		current = nil
	}
	mu.Unlock()
	t.log(LevelInfo, "tunnel stopped")
}

// SetPanic drops every flow (tunnel stays up) while enabled.
func (t *Tunnel) SetPanic(enabled bool) {
	t.panicMode.Store(enabled)
	if enabled {
		t.closeAllConns()
		t.log(LevelWarn, "panic mode: all connections dropped")
	}
}

// SetKillSwitch toggles the kill-switch at runtime.
func (t *Tunnel) SetKillSwitch(enabled bool) { t.killSwitch.Store(enabled) }

// HandshakeAgeSeconds returns the age of the last WireGuard handshake, or -1.
func (t *Tunnel) HandshakeAgeSeconds() int64 {
	if t.wg == nil {
		return -1
	}
	return t.wg.handshakeAge()
}

// WireGuardConnected reports whether WireGuard had a recent handshake.
func (t *Tunnel) WireGuardConnected() bool {
	if t.wg == nil {
		return false
	}
	age := t.wg.handshakeAge()
	return age >= 0 && time.Duration(age)*time.Second < handshakeMaxAge
}

// Stats returns a JSON object with counters for the debug screen.
func (t *Tunnel) Stats() string {
	m := map[string]any{
		"flows":         t.flowsTotal.Load(),
		"flows_blocked": t.flowsBlocked.Load(),
		"dns_queries":   t.dnsQueries.Load(),
		"bytes_up":      t.bytesUp.Load(),
		"bytes_down":    t.bytesDown.Load(),
		"wireguard":     t.wg != nil,
		"handshake_age": t.HandshakeAgeSeconds(),
		"kill_switch":   t.killSwitch.Load(),
		"panic":         t.panicMode.Load(),
		"open_conns":    t.openConns(),
	}
	if t.wg != nil {
		rx, tx := t.wg.transfer()
		m["wg_rx"] = rx
		m["wg_tx"] = tx
	}
	b, _ := json.Marshal(m)
	return string(b)
}

func (t *Tunnel) track(c io.Closer) bool {
	t.connsMu.Lock()
	defer t.connsMu.Unlock()
	if t.closed.Load() {
		return false
	}
	t.conns[c] = struct{}{}
	return true
}

func (t *Tunnel) untrack(c io.Closer) {
	t.connsMu.Lock()
	delete(t.conns, c)
	t.connsMu.Unlock()
}

func (t *Tunnel) openConns() int {
	t.connsMu.Lock()
	defer t.connsMu.Unlock()
	return len(t.conns)
}

func (t *Tunnel) closeAllConns() {
	t.connsMu.Lock()
	list := make([]io.Closer, 0, len(t.conns))
	for c := range t.conns {
		list = append(list, c)
	}
	t.conns = map[io.Closer]struct{}{}
	t.connsMu.Unlock()
	for _, c := range list {
		_ = c.Close()
	}
}

// isDNS reports whether a flow is plain DNS that we must intercept. Every
// port-53 flow is intercepted, not only the virtual resolver, so apps cannot
// bypass the filter by using their own resolver IP.
func isDNS(port uint16) bool { return port == 53 }

// mustUseWireGuard reports whether non-DNS traffic has to go through WG.
func (t *Tunnel) wgBlocked() bool {
	if t.wg == nil {
		return false
	}
	return t.killSwitch.Load() && !t.WireGuardConnected()
}

// isVirtualDNS reports whether addr is one of the resolver addresses announced to Android.
func (t *Tunnel) isVirtualDNS(addr netip.Addr) bool {
	s := addr.String()
	return s == t.cfg.VirtualDNS4 || (t.cfg.VirtualDNS6 != "" && strings.EqualFold(s, t.cfg.VirtualDNS6))
}

func addrString(a tcpip.Address) string {
	ip, ok := netip.AddrFromSlice(a.AsSlice())
	if !ok {
		return ""
	}
	return ip.Unmap().String()
}

func (t *Tunnel) uidFor(proto int32, id stack.TransportEndpointID) int32 {
	// id.Remote* is the app side (source), id.Local* the destination.
	return t.bridge.FindUid(proto, addrString(id.RemoteAddress), int32(id.RemotePort),
		addrString(id.LocalAddress), int32(id.LocalPort))
}

// allow runs the firewall checks for a new non-DNS flow.
func (t *Tunnel) allow(proto int32, uid int32, dst string, port uint16) bool {
	t.flowsTotal.Add(1)
	if t.cfg.BlockDnsBypass && port == 853 {
		t.flowsBlocked.Add(1)
		return false
	}
	if t.panicMode.Load() {
		t.flowsBlocked.Add(1)
		return false
	}
	if t.wgBlocked() {
		t.flowsBlocked.Add(1)
		return false
	}
	if !t.bridge.AllowFlow(uid, proto, dst, int32(port)) {
		t.flowsBlocked.Add(1)
		return false
	}
	return true
}

// protectControl is a net.Dialer Control function that protects the socket.
func (t *Tunnel) protectControl(network, address string, c syscall.RawConn) error {
	var perr error
	err := c.Control(func(fd uintptr) {
		if !t.bridge.Protect(int32(fd)) {
			perr = errors.New("protect failed")
		}
	})
	if err != nil {
		return err
	}
	return perr
}

// dialUpstream dials DoH: through WireGuard when connected, else direct.
func (t *Tunnel) dialUpstream(ctx context.Context, network, address string) (net.Conn, error) {
	if t.wg != nil {
		ap, err := netip.ParseAddrPort(address)
		if err != nil {
			return nil, err
		}
		if t.WireGuardConnected() || t.killSwitch.Load() {
			return t.wg.net.DialContextTCPAddrPort(ctx, ap)
		}
	}
	d := net.Dialer{Timeout: tcpDialTimeout, Control: t.protectControl}
	return d.DialContext(ctx, network, address)
}

func (t *Tunnel) dialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	if t.wg != nil {
		return t.wg.net.DialContextTCPAddrPort(ctx, dst)
	}
	d := net.Dialer{Timeout: tcpDialTimeout, Control: t.protectControl}
	return d.DialContext(ctx, "tcp", dst.String())
}

func (t *Tunnel) dialUDP(dst netip.AddrPort) (net.Conn, error) {
	if t.wg != nil {
		return t.wg.net.DialUDPAddrPort(netip.AddrPort{}, dst)
	}
	d := net.Dialer{Timeout: tcpDialTimeout, Control: t.protectControl}
	return d.Dial("udp", dst.String())
}

func toAddrPort(a tcpip.Address, port uint16) (netip.AddrPort, bool) {
	ip, ok := netip.AddrFromSlice(a.AsSlice())
	if !ok {
		return netip.AddrPort{}, false
	}
	return netip.AddrPortFrom(ip.Unmap(), port), true
}

func (t *Tunnel) handleTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dst, ok := toAddrPort(id.LocalAddress, id.LocalPort)
	if !ok {
		r.Complete(true)
		return
	}
	uid := t.uidFor(ProtoTCP, id)
	if isDNS(id.LocalPort) {
		// DNS over TCP: handled by the DNS proxy.
		var wq waiter.Queue
		ep, e := r.CreateEndpoint(&wq)
		if e != nil {
			r.Complete(true)
			return
		}
		r.Complete(false)
		go t.serveDNSTCP(gonet.NewTCPConn(&wq, ep), uid)
		return
	}
	// The virtual resolver only speaks plain DNS on port 53 (no DoT bypass via 853).
	if t.isVirtualDNS(dst.Addr()) || !t.allow(ProtoTCP, uid, dst.Addr().String(), dst.Port()) {
		r.Complete(true) // RST
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(t.ctx, tcpDialTimeout)
		remote, err := t.dialTCP(ctx, dst)
		cancel()
		if err != nil {
			r.Complete(true)
			return
		}
		var wq waiter.Queue
		ep, e := r.CreateEndpoint(&wq)
		if e != nil {
			remote.Close()
			r.Complete(true)
			return
		}
		r.Complete(false)
		local := gonet.NewTCPConn(&wq, ep)
		t.relayTCP(local, remote)
	}()
}

func (t *Tunnel) relayTCP(local, remote net.Conn) {
	if !t.track(local) {
		local.Close()
		remote.Close()
		return
	}
	t.track(remote)
	defer func() {
		local.Close()
		remote.Close()
		t.untrack(local)
		t.untrack(remote)
	}()
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		n, _ := io.Copy(remote, local)
		t.bytesUp.Add(n)
		if tc, ok := remote.(interface{ CloseWrite() error }); ok {
			_ = tc.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		n, _ := io.Copy(local, remote)
		t.bytesDown.Add(n)
		if tc, ok := local.(interface{ CloseWrite() error }); ok {
			_ = tc.CloseWrite()
		}
	}()
	wg.Wait()
}

func (t *Tunnel) handleUDP(r *udp.ForwarderRequest) {
	id := r.ID()
	dst, ok := toAddrPort(id.LocalAddress, id.LocalPort)
	if !ok {
		return
	}
	uid := t.uidFor(ProtoUDP, id)
	if !isDNS(id.LocalPort) && (t.isVirtualDNS(dst.Addr()) || !t.allow(ProtoUDP, uid, dst.Addr().String(), dst.Port())) {
		return
	}
	var wq waiter.Queue
	ep, e := r.CreateEndpoint(&wq)
	if e != nil {
		return
	}
	local := gonet.NewUDPConn(&wq, ep)
	if isDNS(id.LocalPort) {
		go t.serveDNSUDP(local, uid)
		return
	}
	go func() {
		remote, err := t.dialUDP(dst)
		if err != nil {
			local.Close()
			return
		}
		t.relayUDP(local, remote)
	}()
}

func (t *Tunnel) relayUDP(local *gonet.UDPConn, remote net.Conn) {
	if !t.track(local) {
		local.Close()
		remote.Close()
		return
	}
	t.track(remote)
	defer func() {
		local.Close()
		remote.Close()
		t.untrack(local)
		t.untrack(remote)
	}()
	done := make(chan struct{}, 2)
	go func() {
		buf := make([]byte, 65535)
		for {
			_ = local.SetReadDeadline(time.Now().Add(udpIdleTimeout))
			n, err := local.Read(buf)
			if err != nil {
				break
			}
			t.bytesUp.Add(int64(n))
			if _, err := remote.Write(buf[:n]); err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	go func() {
		buf := make([]byte, 65535)
		for {
			_ = remote.SetReadDeadline(time.Now().Add(udpIdleTimeout))
			n, err := remote.Read(buf)
			if err != nil {
				break
			}
			t.bytesDown.Add(int64(n))
			if _, err := local.Write(buf[:n]); err != nil {
				break
			}
		}
		done <- struct{}{}
	}()
	<-done
}

// resolve runs a DNS query through the Kotlin filter and the DoH upstream.
func (t *Tunnel) resolve(uid int32, q []byte) []byte {
	t.dnsQueries.Add(1)
	if t.panicMode.Load() {
		return servfail(q)
	}
	if ans := t.bridge.FilterDns(uid, q); len(ans) > 0 {
		return ans
	}
	start := time.Now()
	ctx, cancel := context.WithTimeout(t.ctx, 10*time.Second)
	defer cancel()
	ans, err := t.upstreamQuery(ctx, q)
	if err != nil || len(ans) < 12 {
		t.log(LevelWarn, "DoH upstream failed: "+errString(err))
		sf := servfail(q)
		t.bridge.DnsAnswered(uid, q, sf, -1)
		return sf
	}
	// keep the original transaction id
	ans[0], ans[1] = q[0], q[1]
	if repl := t.bridge.DnsAnswered(uid, q, ans, time.Since(start).Milliseconds()); len(repl) >= 12 {
		return repl
	}
	return ans
}

func errString(err error) string {
	if err == nil {
		return "short answer"
	}
	return err.Error()
}

func (t *Tunnel) serveDNSUDP(c *gonet.UDPConn, uid int32) {
	defer c.Close()
	buf := make([]byte, 4096)
	for {
		_ = c.SetReadDeadline(time.Now().Add(dnsIdleTimeout))
		n, err := c.Read(buf)
		if err != nil {
			return
		}
		q := make([]byte, n)
		copy(q, buf[:n])
		go func() {
			if ans := t.resolve(uid, q); len(ans) > 0 {
				_, _ = c.Write(ans)
			}
		}()
	}
}

func (t *Tunnel) serveDNSTCP(c net.Conn, uid int32) {
	defer c.Close()
	for {
		_ = c.SetReadDeadline(time.Now().Add(dnsIdleTimeout))
		var l [2]byte
		if _, err := io.ReadFull(c, l[:]); err != nil {
			return
		}
		n := int(l[0])<<8 | int(l[1])
		q := make([]byte, n)
		if _, err := io.ReadFull(c, q); err != nil {
			return
		}
		ans := t.resolve(uid, q)
		if len(ans) == 0 {
			return
		}
		out := append([]byte{byte(len(ans) >> 8), byte(len(ans))}, ans...)
		if _, err := c.Write(out); err != nil {
			return
		}
	}
}

// ProbeDns sends a query directly to the encrypted upstream (self-test 4) and
// returns the raw answer.
func (t *Tunnel) ProbeDns(q []byte) ([]byte, error) {
	if len(q) < 12 {
		return nil, errors.New("short query")
	}
	ctx, cancel := context.WithTimeout(t.ctx, 10*time.Second)
	defer cancel()
	return t.upstreamQuery(ctx, q)
}

// upstreamQuery asks the primary DoH server and falls back to the second
// encrypted server; a failing primary is skipped for 60 s.
func (t *Tunnel) upstreamQuery(ctx context.Context, q []byte) ([]byte, error) {
	if t.dohFB == nil {
		return t.doh.query(ctx, q)
	}
	if time.Now().UnixMilli() >= t.primaryDownUntil.Load() {
		ans, err := t.doh.query(ctx, q)
		if err == nil {
			return ans, nil
		}
		t.primaryDownUntil.Store(time.Now().Add(60 * time.Second).UnixMilli())
		t.log(LevelWarn, "DoH primary failed, using fallback: "+err.Error())
	}
	ans, err := t.dohFB.query(ctx, q)
	if err != nil {
		t.primaryDownUntil.Store(0)
	}
	return ans, err
}
