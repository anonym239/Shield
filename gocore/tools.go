//go:build tools

// Package tools pins golang.org/x/mobile, which gomobile bind needs in the module.
package tools

import (
	_ "golang.org/x/mobile/bind"
)
