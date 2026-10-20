#!/bin/bash
#
# Affiche la CLÉ PUBLIQUE du serveur RustDesk et la commande d'inscription d'une caisse.
#   cd /opt/passerelle && sudo bash scripts/rustdesk-cle.sh
#
# La clé publique n'est pas secrète (c'est elle qui permet aux caisses de reconnaître LEUR serveur), mais ne la changez jamais :
# toutes les caisses inscrites devraient être réinscrites. La clé PRIVÉE (id_ed25519) reste dans le volume et dans les sauvegardes.
set -euo pipefail
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=${COMPOSE_CMD:-docker compose}
lire_env() { grep -E "^$1=" "$RACINE/.env" 2>/dev/null | head -1 | cut -d= -f2- | sed -e 's/[[:space:]]*#.*$//' -e 's/[[:space:]]*$//' || true; }

if [ -n "${RUSTDESK_DATA_DIR:-}" ]; then            # tests uniquement : lit le fichier directement
    CLE=$(cat "$RUSTDESK_DATA_DIR/id_ed25519.pub" 2>/dev/null || true)
else
    CLE=$(cd "$RACINE" && $COMPOSE exec -T hbbs cat /root/id_ed25519.pub 2>/dev/null || true)
fi
CLE=$(printf '%s' "$CLE" | tr -d '[:space:]')
case "$CLE" in
    ????????????????????????????????????????????) ;;   # 44 caractères
    *) echo "Clé introuvable : le serveur RustDesk est-il démarré ? (docker compose ps ; docker compose logs hbbs)" >&2; exit 1 ;;
esac
printf '%s' "$CLE" | grep -Eq '^[A-Za-z0-9+/]{43}=$' || { echo "Clé au format inattendu : $CLE" >&2; exit 1; }

DOMAINE=$(lire_env RUSTDESK_DOMAIN); DOMAINE=${DOMAINE:-rustdesk.exemple.fr}
cat <<EOF

Serveur RustDesk : $DOMAINE
Clé publique     : $CLE

Sur chaque caisse (après avoir installé les paquets rustdesk et storepos-kiosk-remote) :

  sudo storepos-kiosk-remote-enroll --server $DOMAINE --key '$CLE' --secret-file /chemin/du/secret

Le « secret » sert à calculer le mot de passe de prise en main de CHAQUE caisse (voir scripts/rustdesk-mdp.sh) : ne le tapez jamais
sur la ligne de commande.
EOF
