// SPDX-License-Identifier: GPL-3.0-or-later
package shieldcore

import (
	"bufio"
	"errors"
	"fmt"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type wgTunnel struct {
	dev *device.Device
	net *netstack.Net
}

func parseAddrs(list string) ([]netip.Addr, error) {
	var out []netip.Addr
	for _, s := range strings.Split(list, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if p, err := netip.ParsePrefix(s); err == nil {
			out = append(out, p.Addr())
			continue
		}
		a, err := netip.ParseAddr(s)
		if err != nil {
			return nil, fmt.Errorf("invalid address %q", s)
		}
		out = append(out, a)
	}
	if len(out) == 0 {
		return nil, errors.New("no interface address")
	}
	return out, nil
}

func startWireGuard(cfg *Config, bridge Bridge) (*wgTunnel, error) {
	addrs, err := parseAddrs(cfg.WgAddresses)
	if err != nil {
		return nil, err
	}
	mtu := int(cfg.WgMtu)
	if mtu == 0 {
		mtu = 1280
	}
	tdev, tnet, err := netstack.CreateNetTUN(addrs, nil, mtu)
	if err != nil {
		return nil, err
	}
	logger := &device.Logger{
		Verbosef: func(format string, args ...any) {},
		Errorf: func(format string, args ...any) {
			defer func() { _ = recover() }()
			bridge.Log(LevelWarn, "wireguard: "+fmt.Sprintf(format, args...))
		},
	}
	bind := conn.NewDefaultBind()
	dev := device.NewDevice(tdev, bind, logger)
	if err := dev.IpcSet(cfg.WgUapi); err != nil {
		dev.Close()
		return nil, errors.New("invalid WireGuard configuration")
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, err
	}
	// Protect the UDP sockets of the bind so WireGuard traffic itself does
	// not loop back into the VPN.
	if p, ok := bind.(conn.PeekLookAtSocketFd); ok {
		if fd, err := p.PeekLookAtSocketFd4(); err == nil && fd >= 0 {
			bridge.Protect(int32(fd))
		}
		if fd, err := p.PeekLookAtSocketFd6(); err == nil && fd >= 0 {
			bridge.Protect(int32(fd))
		}
	}
	return &wgTunnel{dev: dev, net: tnet}, nil
}

// handshakeAge returns seconds since the most recent handshake of any peer.
func (w *wgTunnel) handshakeAge() int64 {
	st, err := w.dev.IpcGet()
	if err != nil {
		return -1
	}
	var newest int64 = -1
	sc := bufio.NewScanner(strings.NewReader(st))
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), "=")
		if ok && k == "last_handshake_time_sec" {
			if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > newest {
				newest = n
			}
		}
	}
	if newest <= 0 {
		return -1
	}
	return time.Now().Unix() - newest
}

func (w *wgTunnel) transfer() (rx, tx int64) {
	st, err := w.dev.IpcGet()
	if err != nil {
		return 0, 0
	}
	sc := bufio.NewScanner(strings.NewReader(st))
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), "=")
		if !ok {
			continue
		}
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "rx_bytes":
			rx += n
		case "tx_bytes":
			tx += n
		}
	}
	return rx, tx
}

func (w *wgTunnel) close() { w.dev.Close() }
