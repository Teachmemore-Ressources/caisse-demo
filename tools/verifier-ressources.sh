#!/bin/bash
# Vérifie que le .deb embarque les polices Inter et la photo de l'écran d'accueil (dans le jar de l'application).
set -eu
DEB="${1:?usage : verifier-ressources.sh fichier.deb}"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
dpkg-deb -x "$DEB" "$T"
J=$(find "$T" -name 'caisse-demo-*.jar' | head -1)
[ -n "$J" ] || { echo "jar de l'application introuvable"; exit 1; }
echo "jar : $(basename "$J")"
echo "polices Inter : $(unzip -l "$J" | grep -c 'fonts/Inter-.*\.ttf' || true) (6 attendues)"
unzip -l "$J" | grep 'fonds/' || echo "aucune photo d'accueil embarquée (décor dessiné en code utilisé)"
