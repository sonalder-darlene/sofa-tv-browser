#!/usr/bin/env bash
# Refreshes the bundled pop-up and redirect domain list from HaGeZi's DNS blocklists (GPL-3.0, see NOTICE.md).
# Sofa adds it to whatever main blocklist is active. Usage: tools/update-popup-list.sh (from the repository root)
set -euo pipefail
src="https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt"
out="app/src/main/assets/blocklist-popups.txt.gz"
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
curl -fsSL "$src" -o "$tmp"
{
  echo "# Source: $src"
  grep -m1 '^# Version:' "$tmp" || true
  echo "# License: GPL-3.0, HaGeZi (https://github.com/hagezi/dns-blocklists)"
  grep -v '^#' "$tmp" | grep -v '^$'
} | gzip -9n > "$out"
echo "$(zcat "$out" | grep -vc '^#') domains -> $out ($(stat -c %s "$out") bytes)"
