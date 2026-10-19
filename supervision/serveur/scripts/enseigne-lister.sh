#!/bin/bash
# enseigne-lister.sh : liste les enseignes déclarées (jamais les empreintes ni les mots de passe).
set -euo pipefail
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FICHIER="${ENSEIGNES_FILE:-$RACINE/caddy/enseignes.caddy}"
[ -f "$FICHIER" ] || { echo "Fichier introuvable : $FICHIER" >&2; exit 1; }
n=0
while read -r nom _; do
    case "$nom" in ''|'#'*|_modele_inutilisable) continue ;; esac
    echo "$nom"; n=$((n + 1))
done < "$FICHIER"
echo "-- $n enseigne(s)" >&2
