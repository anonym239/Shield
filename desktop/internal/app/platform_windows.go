//go:build windows

// SPDX-License-Identifier: GPL-3.0-or-later
package app

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

const createNoWindow = 0x08000000

func powershell(script string) (string, error) {
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	out, err := cmd.CombinedOutput()
	if err != nil {
		return string(out), fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return string(out), nil
}

type adapterDNS struct {
	InterfaceIndex  int      `json:"InterfaceIndex"`
	InterfaceAlias  string   `json:"InterfaceAlias"`
	AddressFamily   int      `json:"AddressFamily"`
	ServerAddresses []string `json:"ServerAddresses"`
}

// currentDNS lists the DNS servers of all connected adapters (except Shield's own tunnel).
func currentDNS() ([]adapterDNS, error) {
	out, err := powershell(`$up = Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and $_.Name -ne 'Shield' } | Select-Object -ExpandProperty ifIndex
if ($up) { @(Get-DnsClientServerAddress -InterfaceIndex $up | Select-Object InterfaceIndex,InterfaceAlias,AddressFamily,ServerAddresses) | ConvertTo-Json -Compress -Depth 3 } else { '[]' }`)
	if err != nil {
		return nil, err
	}
	out = strings.TrimSpace(out)
	if strings.HasPrefix(out, "{") {
		out = "[" + out + "]"
	}
	var list []adapterDNS
	if err := json.Unmarshal([]byte(out), &list); err != nil {
		return nil, fmt.Errorf("DNS-Liste nicht lesbar: %v", err)
	}
	return list, nil
}

// staticDNS returns the statically configured IPv4 DNS servers per adapter
// (empty = DHCP), read from the registry, so restoring never turns DHCP into static.
func staticDNS() (map[int][]string, error) {
	out, err := powershell(`$r = @(foreach ($a in (Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and $_.Name -ne 'Shield' })) {
  $ns = (Get-ItemProperty ("HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\" + $a.InterfaceGuid) -ErrorAction SilentlyContinue).NameServer
  [pscustomobject]@{ i = $a.ifIndex; ns = [string]$ns } })
$r | ConvertTo-Json -Compress`)
	if err != nil {
		return nil, err
	}
	out = strings.TrimSpace(out)
	if out == "" {
		return map[int][]string{}, nil
	}
	if strings.HasPrefix(out, "{") {
		out = "[" + out + "]"
	}
	var rows []struct {
		I  int    `json:"i"`
		NS string `json:"ns"`
	}
	if err := json.Unmarshal([]byte(out), &rows); err != nil {
		return nil, err
	}
	m := map[int][]string{}
	for _, r := range rows {
		var list []string
		for _, x := range strings.FieldsFunc(r.NS, func(c rune) bool { return c == ',' || c == ' ' }) {
			if !isLoopbackDNS(x) {
				list = append(list, x)
			}
		}
		m[r.I] = list
	}
	return m, nil
}

func isLoopbackDNS(s string) bool { return s == "127.0.0.1" || s == "::1" }

// applySystemDNS points every connected adapter to 127.0.0.1 / ::1.
// The original servers are backed up once (a crash never overwrites the backup).
func applySystemDNS(dir string) (changed int, err error) {
	list, err := currentDNS()
	if err != nil {
		return 0, err
	}
	backup := map[int][]string{}
	if b, err := os.ReadFile(backupPath(dir)); err == nil {
		_ = json.Unmarshal(b, &backup)
	}
	ifaces := map[int]bool{}
	pointed := map[int]bool{}
	for _, a := range list {
		ifaces[a.InterfaceIndex] = true
		if a.AddressFamily == 2 && len(a.ServerAddresses) > 0 && a.ServerAddresses[0] == "127.0.0.1" {
			pointed[a.InterfaceIndex] = true
		}
	}
	servers, err := staticDNS()
	if err != nil {
		return 0, err
	}
	for idx := range ifaces {
		if _, ok := backup[idx]; ok {
			continue // keep the first (original) backup
		}
		if pointed[idx] {
			backup[idx] = nil // pointed to Shield without backup: restore = DHCP
		} else {
			backup[idx] = servers[idx]
		}
	}
	b, _ := json.Marshal(backup)
	if err := os.WriteFile(backupPath(dir), b, 0o600); err != nil {
		return 0, err
	}
	var cmds []string
	for idx := range ifaces {
		if !pointed[idx] {
			cmds = append(cmds, fmt.Sprintf("Set-DnsClientServerAddress -InterfaceIndex %d -ServerAddresses @('127.0.0.1','::1')", idx))
		}
	}
	if len(cmds) == 0 {
		return 0, nil
	}
	cmds = append(cmds, "Clear-DnsClientCache")
	if _, err := powershell(strings.Join(cmds, "; ")); err != nil {
		return 0, err
	}
	return len(cmds) - 1, nil
}

// RestoreSystemDNS puts the original DNS servers back (also used by --restore-dns and the uninstaller).
func RestoreSystemDNS(dir string) error {
	b, err := os.ReadFile(backupPath(dir))
	backup := map[int][]string{}
	if err == nil {
		_ = json.Unmarshal(b, &backup)
	}
	// adapters still pointing to Shield without backup entry are reset to DHCP
	if list, err := currentDNS(); err == nil {
		for _, a := range list {
			if len(a.ServerAddresses) > 0 && isLoopbackDNS(a.ServerAddresses[0]) {
				if _, ok := backup[a.InterfaceIndex]; !ok {
					backup[a.InterfaceIndex] = nil
				}
			}
		}
	}
	var cmds []string
	for idx, servers := range backup {
		if len(servers) == 0 {
			cmds = append(cmds, fmt.Sprintf("Set-DnsClientServerAddress -InterfaceIndex %d -ResetServerAddresses -ErrorAction SilentlyContinue", idx))
		} else {
			cmds = append(cmds, fmt.Sprintf("Set-DnsClientServerAddress -InterfaceIndex %d -ServerAddresses @('%s') -ErrorAction SilentlyContinue", idx, strings.Join(servers, "','")))
		}
	}
	cmds = append(cmds, "Clear-DnsClientCache")
	if _, err := powershell(strings.Join(cmds, "; ")); err != nil {
		return err
	}
	_ = os.Remove(backupPath(dir))
	return nil
}

// systemDNSPointsToShield is self-test 2.
func systemDNSPointsToShield() (bool, string) {
	list, err := currentDNS()
	if err != nil {
		return false, err.Error()
	}
	n := 0
	for _, a := range list {
		if a.AddressFamily != 2 {
			continue
		}
		n++
		if len(a.ServerAddresses) == 0 || a.ServerAddresses[0] != "127.0.0.1" {
			return false, "Adapter „" + a.InterfaceAlias + "“ nutzt nicht Shield als DNS"
		}
	}
	if n == 0 {
		return false, "Kein verbundener Netzwerkadapter"
	}
	return true, fmt.Sprintf("%d Netzwerkadapter nutzen Shield", n)
}

// ---- secrets (DPAPI) ----

func protect(plain []byte) ([]byte, error) {
	if len(plain) == 0 {
		return nil, errors.New("leer")
	}
	in := windows.DataBlob{Size: uint32(len(plain)), Data: &plain[0]}
	var out windows.DataBlob
	if err := windows.CryptProtectData(&in, nil, nil, 0, nil, 0x1 /* UI_FORBIDDEN */, &out); err != nil {
		return nil, err
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	return append([]byte(nil), unsafe.Slice(out.Data, out.Size)...), nil
}

func unprotect(enc []byte) ([]byte, error) {
	if len(enc) == 0 {
		return nil, errors.New("leer")
	}
	in := windows.DataBlob{Size: uint32(len(enc)), Data: &enc[0]}
	var out windows.DataBlob
	if err := windows.CryptUnprotectData(&in, nil, nil, 0, nil, 0x1, &out); err != nil {
		return nil, err
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	return append([]byte(nil), unsafe.Slice(out.Data, out.Size)...), nil
}

// ---- autostart (scheduled task with highest privileges at logon) ----

func setAutostart(on bool) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	var cmd *exec.Cmd
	if on {
		cmd = exec.Command("schtasks.exe", "/Create", "/TN", "Shield", "/TR", `"`+exe+`" --background`, "/SC", "ONLOGON", "/RL", "HIGHEST", "/F")
	} else {
		cmd = exec.Command("schtasks.exe", "/Delete", "/TN", "Shield", "/F")
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	if out, err := cmd.CombinedOutput(); err != nil && on {
		return fmt.Errorf("%v: %s", err, out)
	}
	return nil
}

func isAdmin() bool {
	return windows.GetCurrentProcessToken().IsElevated()
}

func openURL(u string) error {
	cmd := exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", u)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return cmd.Start()
}
