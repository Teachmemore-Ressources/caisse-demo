#!/bin/bash
#
# Calcule le mot de passe de prise en main d'une caisse à partir de son ID RustDesk.
#   cd /opt/passerelle && sudo bash scripts/rustdesk-mdp.sh 123456789
#
# Chaque caisse reçoit un mot de passe DIFFÉRENT, dérivé de son ID et d'un secret unique (RUSTDESK_SECRET dans .env, généré par Terraform) :
#   mot de passe = 24 premiers caractères de  base64url( HMAC-SHA256(secret, "rustdesk:" + ID) )
# Aucun mot de passe n'est stocké nulle part : l'administrateur le recalcule quand il en a besoin. La même formule est utilisée par
# storepos-kiosk-remote-enroll sur la caisse. Changer le secret invalide tous les mots de passe (il faut réinscrire les caisses).
set -euo pipefail
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ID="${1:-}"
case "$ID" in ''|*[!0-9]*) echo "Usage : $0 <ID RustDesk de la caisse, chiffres seulement>" >&2; exit 1 ;; esac
[ "${#ID}" -ge 6 ] || { echo "ID trop court : $ID" >&2; exit 1; }
SECRET="${RUSTDESK_SECRET:-}"
[ -n "$SECRET" ] || SECRET=$(grep -E '^RUSTDESK_SECRET=' "$RACINE/.env" 2>/dev/null | head -1 | cut -d= -f2- | sed -e 's/[[:space:]]*#.*$//' -e 's/[[:space:]]*$//' || true)
[ "${#SECRET}" -ge 24 ] || { echo "RUSTDESK_SECRET absent ou trop court (24 caractères minimum) dans .env." >&2; exit 1; }
printf '%s' "rustdesk:$ID" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64 -w0 | tr '+/' '-_' | tr -d '=' | cut -c1-24
echo
