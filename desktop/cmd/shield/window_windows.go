//go:build windows

package main

import (
	"os"
	"path/filepath"
	"syscall"
	"unsafe"

	webview2 "github.com/jchv/go-webview2"
)

func showWindow(url string, onClose func()) {
	data := filepath.Join(os.Getenv("LOCALAPPDATA"), "Shield", "WebView2")
	w := webview2.NewWithOptions(webview2.WebViewOptions{
		DataPath:      data,
		AutoFocus:     true,
		WindowOptions: webview2.WindowOptions{Title: "Shield", Width: 1120, Height: 820, IconId: 1, Center: true},
	})
	if w == nil {
		// WebView2 runtime missing: use the default browser instead
		openBrowser(url)
		if onClose != nil {
			select {}
		}
		return
	}
	w.SetSize(1120, 820, webview2.HintNone)
	w.Navigate(url)
	w.Run()
	w.Destroy()
	if onClose != nil {
		onClose()
	}
}

func openBrowser(url string) {
	shell32 := syscall.NewLazyDLL("shell32.dll")
	p, _ := syscall.UTF16PtrFromString(url)
	op, _ := syscall.UTF16PtrFromString("open")
	shell32.NewProc("ShellExecuteW").Call(0, uintptr(unsafe.Pointer(op)), uintptr(unsafe.Pointer(p)), 0, 0, 1)
}

func showError(msg string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	t, _ := syscall.UTF16PtrFromString(msg)
	c, _ := syscall.UTF16PtrFromString("Shield")
	user32.NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10)
}
