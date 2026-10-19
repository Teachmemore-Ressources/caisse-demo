#!/bin/bash
#
# enseigne-revoquer.sh : supprime l'identifiant d'une enseigne. Ses caisses sont refusées dès le rechargement de Caddy
# (les autres enseignes ne sont pas touchées). Elles continuent de vendre : seules leurs mesures cessent d'arriver.
#
# Usage :   ./scripts/enseigne-revoquer.sh NOM

set -euo pipefail
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FICHIER="${ENSEIGNES_FILE:-$RACINE/caddy/enseignes.caddy}"
RELOAD_CMD="${CADDY_RELOAD_CMD:-docker compose -f $RACINE/docker-compose.yml exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile}"

NOM="${1:-}"
[ -n "$NOM" ] || { sed -n '3,8p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
[ "$NOM" != "_modele_inutilisable" ] || { echo "Ce compte modèle doit rester : Caddy refuse de démarrer sans aucun compte." >&2; exit 1; }
[ -f "$FICHIER" ] || { echo "Fichier introuvable : $FICHIER" >&2; exit 1; }
grep -Eq "^$NOM[[:space:]]" "$FICHIER" || { echo "Enseigne inconnue : $NOM" >&2; exit 1; }

cp -p "$FICHIER" "$FICHIER.bak"
TMP=$(mktemp "$FICHIER.XXXXXX")
grep -Ev "^$NOM[[:space:]]" "$FICHIER" > "$TMP"
chmod --reference="$FICHIER" "$TMP" 2>/dev/null || chmod 640 "$TMP"
mv -f "$TMP" "$FICHIER"

if [ "$RELOAD_CMD" != ":" ]; then
    # shellcheck disable=SC2086
    if ! $RELOAD_CMD >/dev/null 2>&1; then
        mv -f "$FICHIER.bak" "$FICHIER"
        echo "Rechargement de Caddy impossible : la révocation est annulée (ancien fichier restauré)." >&2
        exit 1
    fi
fi
echo "Enseigne '$NOM' révoquée. Ses caisses sont désormais refusées (code 401)."
