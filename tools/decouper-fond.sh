#!/bin/bash
# Découpe, dans une maquette (webp, png, jpg), la partie PHOTO propre (sans texte) et l'enregistre comme fond de l'écran d'accueil :
#   src/main/resources/fonds/accueil.jpg
# Usage : bash tools/decouper-fond.sh <maquette> [x0,y0,x1,y1]     (défaut : zone droite d'une maquette 1586×992)
# Utilise Skia (déjà dans le projet) via un test Gradle inactif par défaut : aucune autre dépendance à installer.
set -eu
SRC="$(readlink -f "${1:?usage : decouper-fond.sh maquette [x0,y0,x1,y1]}")"
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export DECOUPE_SRC="$SRC" DECOUPE_OUT="$RACINE/src/main/resources/fonds/accueil.jpg" DECOUPE_ZONE="${2:-640,122,1033,992}"
bash "$RACINE/tools/wsl-build.sh" cleanTest test --tests '*DecoupeFondOutil*' -i 2>&1 | grep -E 'maquette :|photo découpée|BUILD'
ls -la "$RACINE/src/main/resources/fonds/accueil.jpg"
