//go:build windows

// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"bufio"
	"fmt"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/windows/tunnel/firewall"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

// wgTunnel is a WireGuard interface "Shield" (wintun) with routes, DNS -> 127.0.0.1
// and – with the kill switch – the WFP firewall of WireGuard for Windows.
type wgTunnel struct {
	dev      *device.Device
	tun      tun.Device
	luid     winipcfg.LUID
	bind     conn.Bind
	firewall bool
	stop     chan struct{}
	once     sync.Once
}

func startWG(c *WgConfig, uapi string, killSwitch bool) (*wgTunnel, error) {
	t, err := tun.CreateTUN("Shield", c.MTU)
	if err != nil {
		return nil, fmt.Errorf("WireGuard-Adapter (wintun.dll) konnte nicht erstellt werden: %w", err)
	}
	luid := winipcfg.LUID(t.(*tun.NativeTun).LUID())
	bind := conn.NewDefaultBind()
	logger := &device.Logger{
		Verbosef: func(string, ...any) {},
		Errorf:   func(f string, a ...any) { logf("wireguard: "+f, a...) },
	}
	dev := device.NewDevice(t, bind, logger)
	w := &wgTunnel{dev: dev, tun: t, luid: luid, bind: bind, stop: make(chan struct{})}
	fail := func(e error) (*wgTunnel, error) { w.close(); return nil, e }
	if err := dev.IpcSet(uapi); err != nil {
		return fail(fmt.Errorf("ungültige WireGuard-Konfiguration"))
	}
	if err := dev.Up(); err != nil {
		return fail(err)
	}
	if err := luid.SetIPAddresses(c.Addresses); err != nil {
		return fail(fmt.Errorf("Adresse setzen: %w", err))
	}
	var routes []*winipcfg.RouteData
	seen := map[netip.Prefix]bool{}
	for _, p := range c.Peers {
		for _, a := range p.AllowedIPs {
			if seen[a] {
				continue
			}
			seen[a] = true
			nh := netip.IPv4Unspecified()
			if a.Addr().Is6() {
				nh = netip.IPv6Unspecified()
			}
			routes = append(routes, &winipcfg.RouteData{Destination: a, NextHop: nh, Metric: 0})
		}
	}
	if err := luid.SetRoutes(routes); err != nil {
		return fail(fmt.Errorf("Routen setzen: %w", err))
	}
	for _, fam := range []winipcfg.AddressFamily{windows.AF_INET, windows.AF_INET6} {
		if iface, err := luid.IPInterface(fam); err == nil {
			iface.UseAutomaticMetric = false
			iface.Metric = 0
			iface.NLMTU = uint32(c.MTU)
			_ = iface.Set()
		}
	}
	// DNS of the tunnel interface is Shield's own filter.
	_ = luid.SetDNS(windows.AF_INET, []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil)
	_ = luid.SetDNS(windows.AF_INET6, []netip.Addr{netip.MustParseAddr("::1")}, nil)
	w.bindDefault()
	go w.watchDefaultRoute()
	if killSwitch {
		if err := firewall.EnableFirewall(uint64(luid), false, []netip.Addr{netip.MustParseAddr("127.0.0.1"), netip.MustParseAddr("::1")}); err != nil {
			return fail(fmt.Errorf("Kill-Switch (Firewall) konnte nicht aktiviert werden: %w", err))
		}
		w.firewall = true
	}
	return w, nil
}

// defaultInterface returns the interface index of the best default route that is not the tunnel.
func (w *wgTunnel) defaultInterface(fam winipcfg.AddressFamily) uint32 {
	rows, err := winipcfg.GetIPForwardTable2(fam)
	if err != nil {
		return 0
	}
	type cand struct{ idx, metric uint32 }
	var cs []cand
	for _, r := range rows {
		if r.DestinationPrefix.PrefixLength != 0 || r.InterfaceLUID == w.luid {
			continue
		}
		ifrow, err := r.InterfaceLUID.Interface()
		if err != nil || ifrow.OperStatus != winipcfg.IfOperStatusUp {
			continue
		}
		m := r.Metric
		if ipif, err := r.InterfaceLUID.IPInterface(fam); err == nil {
			m += ipif.Metric
		}
		cs = append(cs, cand{r.InterfaceIndex, m})
	}
	sort.Slice(cs, func(i, j int) bool { return cs[i].metric < cs[j].metric })
	if len(cs) == 0 {
		return 0
	}
	return cs[0].idx
}

// bindDefault binds the WireGuard UDP socket to the physical interface so the
// encrypted traffic itself never loops into the tunnel.
func (w *wgTunnel) bindDefault() {
	b, ok := w.bind.(conn.BindSocketToInterface)
	if !ok {
		return
	}
	if i := w.defaultInterface(windows.AF_INET); i != 0 {
		_ = b.BindSocketToInterface4(i, false)
	}
	if i := w.defaultInterface(windows.AF_INET6); i != 0 {
		_ = b.BindSocketToInterface6(i, false)
	}
}

func (w *wgTunnel) watchDefaultRoute() {
	tk := time.NewTicker(5 * time.Second)
	defer tk.Stop()
	for {
		select {
		case <-w.stop:
			return
		case <-tk.C:
			w.bindDefault()
		}
	}
}

func (w *wgTunnel) handshakeAge() int64 {
	st, err := w.dev.IpcGet()
	if err != nil {
		return -1
	}
	var newest int64
	sc := bufio.NewScanner(strings.NewReader(st))
	for sc.Scan() {
		if k, v, ok := strings.Cut(sc.Text(), "="); ok && k == "last_handshake_time_sec" {
			if n, _ := strconv.ParseInt(v, 10, 64); n > newest {
				newest = n
			}
		}
	}
	if newest == 0 {
		return -1
	}
	return time.Now().Unix() - newest
}

func (w *wgTunnel) close() {
	w.once.Do(func() {
		close(w.stop)
		if w.firewall {
			firewall.DisableFirewall()
		}
		w.dev.Close()
	})
}
