//go:build !windows

// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"errors"
	"os"
	"path/filepath"
)

// Non-Windows builds exist only for unit tests and local development.

func applySystemDNS(string) (int, error)      { return 0, nil }
func RestoreSystemDNS(string) error           { return nil }
func systemDNSPointsToShield() (bool, string) { return true, "nicht Windows (Test)" }
func protect(b []byte) ([]byte, error)        { return append([]byte("T"), b...), nil }
func unprotect(b []byte) ([]byte, error) {
	if len(b) == 0 || b[0] != 'T' {
		return nil, errors.New("invalid")
	}
	return b[1:], nil
}
func setAutostart(bool) error { return nil }
func isAdmin() bool           { return true }
func openURL(string) error    { return nil }

func downloadsDir() string {
	h, _ := os.UserHomeDir()
	return filepath.Join(h, "Downloads")
}
