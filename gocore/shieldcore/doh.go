// SPDX-License-Identifier: GPL-3.0-or-later
package shieldcore

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// dialFunc dials a TCP connection to a host:port.
type dialFunc func(ctx context.Context, network, address string) (net.Conn, error)

// dohClient sends RFC 8484 POST requests. The host name of the upstream is
// mapped to bootstrap IPs, so no plaintext DNS lookup is ever made.
type dohClient struct {
	url        string
	host       string
	port       string
	bootstrap  []string
	httpClient *http.Client
}

func newDohClient(rawURL, bootstrap string, dial dialFunc) (*dohClient, error) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" {
		return nil, fmt.Errorf("invalid DoH url")
	}
	port := u.Port()
	if port == "" {
		port = "443"
	}
	var ips []string
	for _, s := range strings.Split(bootstrap, ",") {
		s = strings.TrimSpace(s)
		if net.ParseIP(s) != nil {
			ips = append(ips, s)
		}
	}
	if ip := net.ParseIP(u.Hostname()); ip != nil {
		ips = append(ips, u.Hostname())
	}
	if len(ips) == 0 {
		return nil, errors.New("DoH upstream needs at least one bootstrap IP")
	}
	c := &dohClient{url: rawURL, host: u.Hostname(), port: port, bootstrap: ips}
	tr := &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			var lastErr error
			start := rand.Intn(len(c.bootstrap))
			for i := range c.bootstrap {
				ip := c.bootstrap[(start+i)%len(c.bootstrap)]
				conn, err := dial(ctx, "tcp", net.JoinHostPort(ip, c.port))
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
		TLSClientConfig:     &tls.Config{ServerName: c.host, MinVersion: tls.VersionTLS12},
		ForceAttemptHTTP2:   true,
		MaxIdleConns:        4,
		IdleConnTimeout:     60 * time.Second,
		TLSHandshakeTimeout: 8 * time.Second,
	}
	c.httpClient = &http.Client{Transport: tr, Timeout: 10 * time.Second}
	return c, nil
}

func (c *dohClient) query(ctx context.Context, q []byte) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url, bytes.NewReader(q))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DoH status %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 65535))
}

func (c *dohClient) close() { c.httpClient.CloseIdleConnections() }

// questionEnd returns the offset after the first question, or -1.
func questionEnd(q []byte) int {
	if len(q) < 12 {
		return -1
	}
	i := 12
	for i < len(q) {
		l := int(q[i])
		if l == 0 {
			i++
			break
		}
		if l&0xC0 != 0 {
			return -1
		}
		i += l + 1
	}
	if i+4 > len(q) {
		return -1
	}
	return i + 4
}

// servfail builds a SERVFAIL answer for a query (question section only).
func servfail(q []byte) []byte {
	end := questionEnd(q)
	if end < 0 {
		return nil
	}
	r := make([]byte, end)
	copy(r, q[:end])
	r[2] = 0x80 | (q[2] & 0x79) // QR=1, keep opcode + RD
	r[3] = 0x80 | 0x02          // RA=1, RCODE=SERVFAIL
	r[4], r[5] = 0, 1
	r[6], r[7], r[8], r[9], r[10], r[11] = 0, 0, 0, 0, 0, 0
	return r
}
