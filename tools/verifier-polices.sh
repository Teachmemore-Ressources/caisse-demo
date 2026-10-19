#!/bin/bash
# Vérifie que le .deb embarque bien les polices Inter (dans le jar de l'application).
set -eu
DEB="${1:?usage : verifier-polices.sh fichier.deb}"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
dpkg-deb -x "$DEB" "$T"
J=$(find "$T" -name 'caisse-demo-*.jar' | head -1)
[ -n "$J" ] || { echo "jar de l'application introuvable"; exit 1; }
echo "jar : $(basename "$J")"
n=$(unzip -l "$J" | grep -c 'fonts/Inter-.*\.ttf' || true)
echo "polices Inter dans le jar : $n (6 attendues)"
unzip -l "$J" | grep 'fonts/\|icon.png' || true
[ "$n" = 6 ]
