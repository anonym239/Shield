//go:build !windows

package main

import "fmt"

// Development builds on Linux/macOS: open the printed URL in a browser.
func showWindow(url string, onClose func()) {
	fmt.Println("Shield UI:", url)
	select {}
}

func showError(msg string) { fmt.Println(msg) }
