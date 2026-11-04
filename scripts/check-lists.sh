#!/usr/bin/env bash
# Checks that every blocklist URL used by the Android and Windows app answers HTTP 200
# and that uBlock Origin's !#include parts resolve. Run in CI (needs internet).
set -u
cd "$(dirname "$0")/.."
urls=$( {
  grep -oE '"https://[^"]+"' app/src/main/java/dev/shield/core/filter/BlocklistRepository.kt
  grep -oE '"https://[^"]+"' desktop/internal/app/lists.go
} | tr -d '"' | sed 's#\$UBO#https://ublockorigin.github.io/uAssets/filters#; s#\$HAGEZI#https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock#' | sort -u)
# expand the uBO / HaGeZi constant-based entries
ubo=https://ublockorigin.github.io/uAssets/filters
for f in filters badware privacy quick-fixes unbreak annoyances annoyances-cookies; do urls+=$'\n'"$ubo/$f.txt"; done
fail=0
for u in $(echo "$urls" | grep -vE '/filters$|/adblock$' | sort -u); do
  code=$(curl -sS -L -o /tmp/list.txt -w '%{http_code}' --max-time 60 "$u" || echo 000)
  lines=$(wc -l < /tmp/list.txt 2>/dev/null || echo 0)
  echo "$code $lines $u"
  if [ "$code" != 200 ] || [ "$lines" -lt 5 ]; then fail=1; continue; fi
  for inc in $(grep -E '^!#include ' /tmp/list.txt | awk '{print $2}'); do
    c=$(curl -sS -L -o /dev/null -w '%{http_code}' --max-time 60 "${u%/*}/$inc" || echo 000)
    echo "  $c include $inc"
    [ "$c" = 200 ] || fail=1
  done
done
exit $fail
