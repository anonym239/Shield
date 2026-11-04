// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// WgPeer / WgConfig are a parsed wg-quick .conf (e.g. from Proton).
type WgPeer struct {
	PublicKey, PresharedKey, Endpoint string
	AllowedIPs                        []netip.Prefix
	Keepalive                         int
}

type WgConfig struct {
	PrivateKey string
	Addresses  []netip.Prefix
	MTU        int
	Peers      []WgPeer
}

func keyHex(k, name string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(k))
	if err != nil || len(b) != 32 {
		return "", fmt.Errorf("%s ist ungültig", name)
	}
	return hex.EncodeToString(b), nil
}

func parsePrefix(s string) (netip.Prefix, error) {
	s = strings.TrimSpace(s)
	if p, err := netip.ParsePrefix(s); err == nil {
		return p.Masked(), nil
	}
	a, err := netip.ParseAddr(s)
	if err != nil {
		return netip.Prefix{}, fmt.Errorf("ungültige Adresse %q", s)
	}
	return netip.PrefixFrom(a, a.BitLen()), nil
}

// ParseWgConfig parses a .conf and validates keys, addresses and peers.
func ParseWgConfig(text string) (*WgConfig, error) {
	c := &WgConfig{}
	var cur *WgPeer
	section := ""
	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(strings.SplitN(raw, "#", 2)[0])
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			if section == "peer" {
				c.Peers = append(c.Peers, WgPeer{})
				cur = &c.Peers[len(c.Peers)-1]
			}
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			return nil, errors.New("ungültige Zeile in der Konfiguration")
		}
		k, v = strings.ToLower(strings.TrimSpace(k)), strings.TrimSpace(v)
		switch section {
		case "interface":
			switch k {
			case "privatekey":
				c.PrivateKey = v
			case "address":
				for _, a := range strings.Split(v, ",") {
					p, err := parsePrefix(a)
					if err != nil {
						return nil, err
					}
					c.Addresses = append(c.Addresses, p)
				}
			case "mtu":
				m, err := strconv.Atoi(v)
				if err != nil || m < 576 || m > 9000 {
					return nil, errors.New("ungültige MTU")
				}
				c.MTU = m
			}
		case "peer":
			switch k {
			case "publickey":
				cur.PublicKey = v
			case "presharedkey":
				cur.PresharedKey = v
			case "endpoint":
				if _, _, err := net.SplitHostPort(v); err != nil {
					return nil, errors.New("ungültiger Endpoint")
				}
				cur.Endpoint = v
			case "allowedips":
				for _, a := range strings.Split(v, ",") {
					if strings.TrimSpace(a) == "" {
						continue
					}
					p, err := parsePrefix(a)
					if err != nil {
						return nil, err
					}
					cur.AllowedIPs = append(cur.AllowedIPs, p)
				}
			case "persistentkeepalive":
				n, err := strconv.Atoi(v)
				if err != nil || n < 0 || n > 65535 {
					return nil, errors.New("ungültiges PersistentKeepalive")
				}
				cur.Keepalive = n
			}
		default:
			return nil, errors.New("Eintrag außerhalb von [Interface]/[Peer]")
		}
	}
	if _, err := keyHex(c.PrivateKey, "PrivateKey"); err != nil {
		return nil, err
	}
	if len(c.Addresses) == 0 {
		return nil, errors.New("Address fehlt")
	}
	if len(c.Peers) == 0 {
		return nil, errors.New("Kein [Peer]")
	}
	for _, p := range c.Peers {
		if _, err := keyHex(p.PublicKey, "PublicKey"); err != nil {
			return nil, err
		}
		if len(p.AllowedIPs) == 0 {
			return nil, errors.New("Peer ohne AllowedIPs")
		}
	}
	if c.MTU == 0 {
		c.MTU = 1420
	}
	return c, nil
}

// UAPI builds the wireguard-go configuration. resolve maps host names to IPs.
func (c *WgConfig) UAPI(resolve func(host string) (string, error)) (string, error) {
	var b strings.Builder
	pk, _ := keyHex(c.PrivateKey, "PrivateKey")
	fmt.Fprintf(&b, "private_key=%s\nreplace_peers=true\n", pk)
	for _, p := range c.Peers {
		pub, _ := keyHex(p.PublicKey, "PublicKey")
		fmt.Fprintf(&b, "public_key=%s\n", pub)
		if p.PresharedKey != "" {
			psk, err := keyHex(p.PresharedKey, "PresharedKey")
			if err != nil {
				return "", err
			}
			fmt.Fprintf(&b, "preshared_key=%s\n", psk)
		}
		if p.Endpoint != "" {
			host, port, _ := net.SplitHostPort(p.Endpoint)
			if net.ParseIP(host) == nil {
				ip, err := resolve(host)
				if err != nil {
					return "", fmt.Errorf("Endpoint %s nicht auflösbar", host)
				}
				host = ip
			}
			fmt.Fprintf(&b, "endpoint=%s\n", net.JoinHostPort(host, port))
		}
		if p.Keepalive > 0 {
			fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", p.Keepalive)
		}
		b.WriteString("replace_allowed_ips=true\n")
		for _, a := range p.AllowedIPs {
			fmt.Fprintf(&b, "allowed_ip=%s\n", a)
		}
	}
	return b.String(), nil
}
