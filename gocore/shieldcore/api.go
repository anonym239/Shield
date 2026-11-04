// Package shieldcore is the user-space network core of Shield (tunnel stage B).
//
// It reads every IP packet from the Android TUN interface into a gVisor
// netstack, applies the per-app firewall (via callbacks into Kotlin), filters
// DNS (via callbacks into Kotlin) and forwards allowed flows either directly
// through protected sockets or through a WireGuard tunnel (wireguard-go with
// its own netstack). The package is exported to Android with gomobile.
//
// SPDX-License-Identifier: GPL-3.0-or-later
package shieldcore

import (
	"errors"
	"sync"
)

// Protocol numbers passed to Bridge.FindUid / AllowFlow (same as IPPROTO_*).
const (
	ProtoTCP int32 = 6
	ProtoUDP int32 = 17
)

// Log levels for Bridge.Log (match android.util.Log priorities).
const (
	LevelDebug int32 = 3
	LevelInfo  int32 = 4
	LevelWarn  int32 = 5
	LevelError int32 = 6
)

// Bridge is implemented on the Kotlin side.
type Bridge interface {
	// Protect excludes the socket fd from the VPN (VpnService.protect).
	Protect(fd int32) bool
	// FindUid resolves the owning app uid of a connection, or -1.
	FindUid(proto int32, srcAddr string, srcPort int32, dstAddr string, dstPort int32) int32
	// AllowFlow returns true if the app may open this flow (firewall).
	AllowFlow(uid int32, proto int32, dstAddr string, dstPort int32) bool
	// FilterDns returns a complete DNS response (blocked/cached) or an empty
	// slice when the query must be forwarded to the encrypted upstream.
	FilterDns(uid int32, query []byte) []byte
	// DnsAnswered reports an upstream answer (for logging and the cache).
	// latencyMs < 0 means the upstream failed and answer is SERVFAIL.
	// A non-empty return value replaces the answer (e.g. CNAME cloaking blocked).
	DnsAnswered(uid int32, query []byte, answer []byte, latencyMs int64) []byte
	// Log writes into the Shield logging facade.
	Log(level int32, msg string)
}

// Config describes a tunnel. All strings are plain values; WireGuard keys are
// passed only in WgUapi and never logged.
type Config struct {
	Fd     int32
	Mtu    int32
	DohURL string
	// DohBootstrap is a comma separated list of IPs for the DoH host.
	DohBootstrap string
	// DohFallbackURL / DohFallbackBootstrap: second encrypted upstream used
	// when the primary fails (empty = none). Never plaintext.
	DohFallbackURL       string
	DohFallbackBootstrap string
	// WgUapi is the wireguard-go UAPI configuration (empty = no WireGuard).
	WgUapi string
	// WgAddresses is a comma separated list of interface addresses (CIDR or IP).
	WgAddresses string
	// WgMtu is the MTU of the WireGuard netstack (0 = 1280).
	WgMtu int32
	// KillSwitch drops all non-DNS flows while WireGuard is not connected.
	KillSwitch bool
	// BlockDnsBypass rejects DNS-over-TLS / DNS-over-QUIC (port 853), so apps
	// cannot resolve around the filter (DoH servers are blocked by list).
	BlockDnsBypass bool
	// VirtualDNS4 / VirtualDNS6 are the DNS addresses announced to Android.
	VirtualDNS4 string
	VirtualDNS6 string
}

// NewConfig creates an empty configuration (gomobile cannot construct structs
// with fields from Java directly in a convenient way).
func NewConfig() *Config { return &Config{} }

var (
	mu      sync.Mutex
	current *Tunnel
)

// Start starts the tunnel. Only one tunnel can run at a time.
func Start(cfg *Config, bridge Bridge) (*Tunnel, error) {
	if cfg == nil || bridge == nil {
		return nil, errors.New("config and bridge are required")
	}
	mu.Lock()
	defer mu.Unlock()
	if current != nil {
		current.Close()
		current = nil
	}
	t, err := newTunnel(cfg, bridge)
	if err != nil {
		return nil, err
	}
	current = t
	return t, nil
}

// Version of the core, shown in the debug screen.
func Version() string { return "shieldcore 1.0.0 (wireguard-go + gVisor netstack)" }
