#!/usr/bin/env bash
# Refreshes the bundled ad/tracker domain list from StevenBlack/hosts (MIT, see NOTICE.md).
# Usage: tools/update-blocklist.sh   (run from the repository root)
set -euo pipefail
src="https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
out="app/src/main/assets/blocklist-hosts.txt.gz"
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
curl -fsSL "$src" -o "$tmp"
{
  echo "# Source: $src"
  grep -m1 '^# Date:' "$tmp" || true
  echo "# License: MIT, Copyright (c) Steven Black"
  awk '$1 == "0.0.0.0" && $2 != "0.0.0.0" { print $2 }' "$tmp"
} | gzip -9n > "$out"
echo "$(zcat "$out" | grep -vc '^#') domains -> $out ($(stat -c %s "$out") bytes)"
