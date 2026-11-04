// SPDX-License-Identifier: GPL-3.0-or-later
package app

import "bytes"

func bytesReader(b []byte) *bytes.Reader { return bytes.NewReader(b) }
