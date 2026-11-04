// Shield für Windows: lokaler DNS-Filter, verschlüsseltes DNS, WireGuard-VPN mit Kill-Switch.
//
// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/anonym239/shield/desktop/internal/app"
	"github.com/anonym239/shield/desktop/internal/web"
)

func main() {
	background := flag.Bool("background", false, "ohne Fenster im Hintergrund laufen (Autostart)")
	restore := flag.Bool("restore-dns", false, "DNS-Einstellungen von Windows wiederherstellen und beenden")
	ci := flag.Bool("ci-selftest", false, "Ende-zu-Ende-Test (CI): einschalten, prüfen, ausschalten")
	flag.Parse()

	if *restore {
		if err := app.RestoreSystemDNS(app.DataDir()); err != nil {
			fmt.Fprintln(os.Stderr, "Fehler:", err)
			os.Exit(1)
		}
		fmt.Println("DNS-Einstellungen wiederhergestellt.")
		return
	}

	// Single instance: an engine already runs (e.g. autostart) -> only show its UI.
	if !*background && !*ci {
		if url := app.RunningInstanceURL(); url != "" {
			showWindow(url, nil)
			return
		}
	}

	e := app.NewEngine()
	api := &app.API{E: e}
	if _, err := api.Listen(); err != nil {
		fatal("Bedienoberfläche konnte nicht starten: " + err.Error())
	}
	go func() { _ = api.Serve(web.Static()) }()

	quit := make(chan struct{})
	app.OnQuit = func() { close(quit) }
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		select {
		case <-sig:
		case <-quit:
		}
		e.Shutdown()
		os.Exit(0)
	}()

	if *ci {
		os.Exit(ciSelftest(e))
	}
	e.Init()
	if *background {
		select {}
	}
	showWindow(api.URL(), func() {
		// window closed: keep protecting in the background only if autostart is on
		if e.Settings().Autostart && e.Running() {
			select {}
		}
		e.Shutdown()
	})
}

// ciSelftest is used by the Windows CI job: real DNS changes on a real Windows machine.
func ciSelftest(e *app.Engine) int {
	if err := e.Start(); err != nil {
		fmt.Println("START FAILED:", err)
		return 1
	}
	defer e.Stop()
	if err := e.SetRule("blocked-by-shield-ci.com", false); err != nil {
		fmt.Println(err)
		return 1
	}
	time.Sleep(4 * time.Second)
	fail := 0
	look := func(name string) []string {
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		defer cancel()
		a, _ := net.DefaultResolver.LookupHost(ctx, name)
		return a
	}
	blocked := func(a []string) bool {
		for _, x := range a {
			if x != "0.0.0.0" && x != "::" {
				return false
			}
		}
		return true
	}
	if a := look(fmt.Sprintf("x%d.blocked-by-shield-ci.com", time.Now().Unix())); !blocked(a) {
		fmt.Println("FAIL: own block rule not applied via system resolver:", a)
		fail++
	} else {
		fmt.Println("ok: own rule blocks via Windows resolver")
	}
	if a := look("x.firebaselogging-pa.googleapis.com"); len(a) > 0 && !blocked(a) && e.Settings().Level != app.Standard {
		fmt.Println("FAIL: telemetry not blocked")
		fail++
	}
	if a := look("example.com"); len(a) == 0 || blocked(a) {
		fmt.Println("FAIL: example.com not resolved via DoH:", a)
		fail++
	} else {
		fmt.Println("ok: example.com resolved via DoH:", a)
	}
	rep := e.SelfTest(false)
	b, _ := json.MarshalIndent(rep, "", "  ")
	fmt.Println(string(b))
	for _, r := range rep.Results {
		if r.Passed != nil && !*r.Passed && r.ID != "LISTS" {
			fmt.Println("FAIL: self-test", r.ID, r.Detail)
			fail++
		}
	}
	return min(fail, 1)
}

func fatal(msg string) {
	showError(msg)
	os.Exit(1)
}
