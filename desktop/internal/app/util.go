// SPDX-License-Identifier: GPL-3.0-or-later
package app

import "bytes"

func bytesReader(b []byte) *bytes.Reader { return bytes.NewReader(b) }

// extractJSONArray finds the JSON value in (possibly noisy) PowerShell output and
// always returns an array ("{...}" becomes "[{...}]", nothing becomes "[]").
func extractJSONArray(out string) string {
	for _, line := range splitLines(out) {
		t := trimSpace(line)
		if len(t) > 0 && t[0] == '[' {
			return t
		}
		if len(t) > 0 && t[0] == '{' {
			return "[" + t + "]"
		}
	}
	return "[]"
}

func splitLines(s string) []string {
	var out []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == '\n' {
			out = append(out, s[start:i])
			start = i + 1
		}
	}
	return append(out, s[start:])
}

func trimSpace(s string) string {
	i, j := 0, len(s)
	for i < j && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == 0xEF || s[i] == 0xBB || s[i] == 0xBF) {
		i++
	}
	for j > i && (s[j-1] == ' ' || s[j-1] == '\t' || s[j-1] == '\r') {
		j--
	}
	return s[i:j]
}
