//go:build !windows

// SPDX-License-Identifier: GPL-3.0-or-later
package app

import "errors"

type wgTunnel struct{}

func startWG(*WgConfig, string, bool) (*wgTunnel, error) {
	return nil, errors.New("WireGuard ist nur unter Windows verfügbar")
}
func (w *wgTunnel) handshakeAge() int64 { return -1 }
func (w *wgTunnel) close()              {}
