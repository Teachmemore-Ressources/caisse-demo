#!/bin/bash
# Télécharge la police Inter (licence SIL OFL 1.1, https://rsms.me/inter/) et copie les graisses utilisées par l'application
# dans src/main/resources/fonts/. À relancer seulement pour changer de version.
set -eu
V="${INTER_VERSION:-4.1}"
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/src/main/resources/fonts"
mkdir -p "$OUT"
curl -fsSL -o "$D/inter.zip" "https://github.com/rsms/inter/releases/download/v${V}/Inter-${V}.zip"
unzip -q "$D/inter.zip" -d "$D/x"
sha256sum "$D/inter.zip"
for w in Light Regular Medium SemiBold Bold Black; do
    f=$(find "$D/x" -name "Inter-$w.ttf" | grep -v -i "italic" | head -1)
    [ -n "$f" ] || { echo "Inter-$w.ttf introuvable dans l'archive" >&2; exit 1; }
    cp "$f" "$OUT/Inter-$w.ttf"
done
l=$(find "$D/x" -iname "LICENSE*" | head -1); [ -n "$l" ] && cp "$l" "$OUT/Inter-LICENSE.txt"
ls -la "$OUT"
