// Package web embeds the UI of Shield for Windows.
package web

import (
	"embed"
	"io/fs"
)

//go:embed static
var files embed.FS

// Static returns the UI files.
func Static() fs.FS {
	f, _ := fs.Sub(files, "static")
	return f
}
