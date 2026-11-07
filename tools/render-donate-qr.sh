#!/usr/bin/env bash
# Renders the QR codes of the donation addresses: one PNG per address for the app (one pixel per module, no margin;
# Settings scales it up by a whole number, without smoothing, on a white quiet zone) and one SVG for the README.
# Each code holds the bare address, exactly as Settings and the README show it. Keep the list below the same as
# app/src/main/java/app/sofabrowser/ui/Donations.kt (DonationsTest checks it).
# Needs qrencode, and zbarimg to read every code back. Usage: tools/render-donate-qr.sh   (run from the repository root)
set -euo pipefail
png=app/src/main/res/drawable-nodpi
svg=docs/donate
mkdir -p "$svg"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
render() {
    local id=$1 address=$2
    qrencode -l M -t PNG -s 1 -m 0 -o "$png/donate_qr_$id.png" "$address"
    qrencode -l M -t SVG -s 8 -m 4 --svg-path -o "$svg/$id.svg" "$address"
    # Read back a larger render of the same code: zbarimg can't read a one-pixel-per-module image.
    qrencode -l M -t PNG -s 8 -m 4 -o "$tmp/$id.png" "$address"
    [ "$(zbarimg -q --raw "$tmp/$id.png")" = "$address" ] || { echo "$id: the code doesn't read back" >&2; exit 1; }
    echo "$id ok"
}
render btc sp1qqglkmsmm6fkxzmnjtzzlyfshur2w80zlmaddvq497lesc569aahekqe9h5fvpjn53yz9hfjgc5f872acxx8pqf2jp8pypl3vth4ten4a3s5p9jrv
render lightning sonalder@cake.cash
render xmr 469dA6keA9w9EfensKZJnaZCmqQY4foXedwnN98Sm7QVeJeXTMR7EAU913oPqzFRVJes8uyYQWixm6oqH3i37Vyf1NbomdG
render zec u1paq3l7dms04sml8r4gp40hdh8wu73hlj70rafmyay23rx2rr6kynx2t0dgge2m6nsu6rcsl4xnhp255t3qv062jfrrw3jx4uygkasts8
render eth 0xC88455aa2b489Ba83Ed9606EA9627466C37C4D5a
