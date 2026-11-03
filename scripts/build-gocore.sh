#!/usr/bin/env bash
# Builds the Go network core (wireguard-go + gVisor netstack) into app/libs/shieldcore.aar.
# Requires: Go (see gocore/go.mod), Android SDK + NDK (ANDROID_HOME, ANDROID_NDK_HOME).
set -euo pipefail
cd "$(dirname "$0")/../gocore"
export PATH="$(go env GOPATH)/bin:$PATH"
if ! command -v gomobile >/dev/null; then
  go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867
  go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
fi
gomobile init
mkdir -p ../app/libs
gomobile bind \
  -target=android/arm64,android/arm,android/amd64 \
  -androidapi 29 \
  -trimpath -ldflags="-s -w -buildid=" \
  -o ../app/libs/shieldcore.aar \
  ./shieldcore
ls -la ../app/libs/shieldcore.aar
