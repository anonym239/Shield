// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"time"
)

// doh is an RFC 8484 client that only connects to bootstrap IPs, so it never
// needs (plaintext) DNS – essential because the system resolver is Shield itself.
type doh struct {
	url    string
	client *http.Client
}

func newDoH(rawURL string, bootstrap []string) (*doh, error) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Scheme != "https" {
		return nil, errors.New("ungültige DoH-Adresse")
	}
	host, port := u.Hostname(), u.Port()
	if port == "" {
		port = "443"
	}
	ips := []string{}
	for _, b := range bootstrap {
		if net.ParseIP(b) != nil {
			ips = append(ips, b)
		}
	}
	if net.ParseIP(host) != nil {
		ips = append(ips, host)
	}
	if len(ips) == 0 {
		return nil, errors.New("DoH-Server braucht mindestens eine Bootstrap-IP")
	}
	d := &net.Dialer{Timeout: 6 * time.Second}
	tr := &http.Transport{
		Proxy: nil,
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var last error
			for _, ip := range ips {
				c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, port))
				if err == nil {
					return c, nil
				}
				last = err
			}
			return nil, last
		},
		TLSClientConfig:     &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12},
		ForceAttemptHTTP2:   true,
		MaxIdleConns:        4,
		IdleConnTimeout:     90 * time.Second,
		TLSHandshakeTimeout: 8 * time.Second,
	}
	return &doh{url: rawURL, client: &http.Client{Transport: tr, Timeout: 10 * time.Second}}, nil
}

func (d *doh) query(ctx context.Context, q []byte) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.url, bytes.NewReader(q))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := d.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DoH HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil || len(b) < 12 {
		return nil, errors.New("DoH: kurze Antwort")
	}
	return b, nil
}

func (d *doh) close() { d.client.CloseIdleConnections() }
