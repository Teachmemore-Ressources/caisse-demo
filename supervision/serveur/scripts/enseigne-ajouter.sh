#!/bin/bash
#
# enseigne-ajouter.sh : crée l'identifiant d'une enseigne (client) pour l'envoi des mesures et des journaux.
#
# Usage :   ./scripts/enseigne-ajouter.sh NOM
#   NOM : 3 à 32 caractères, minuscules, chiffres, tiret ou souligné (ex. leclerc, boulangerie-martin)
#
# Le mot de passe est généré (aléatoire, 40 caractères), affiché UNE SEULE FOIS, puis seule son empreinte est conservée.
# Un identifiant par enseigne permet d'en révoquer un sans toucher aux autres (voir enseigne-revoquer.sh).

set -euo pipefail
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FICHIER="${ENSEIGNES_FILE:-$RACINE/caddy/enseignes.caddy}"
# Réglables pour les tests : commande de calcul d'empreinte et de rechargement de Caddy
HASH_CMD="${CADDY_HASH_CMD:-docker run --rm caddy:2.11.7 caddy hash-password --plaintext}"
RELOAD_CMD="${CADDY_RELOAD_CMD:-docker compose -f $RACINE/docker-compose.yml exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile}"

NOM="${1:-}"
[ -n "$NOM" ] || { sed -n '3,9p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
if ! printf '%s' "$NOM" | grep -Eq '^[a-z0-9][a-z0-9_-]{2,31}$'; then
    echo "Nom invalide : '$NOM' (3 à 32 caractères : minuscules, chiffres, - ou _, sans commencer par - ou _)." >&2
    exit 1
fi
[ -f "$FICHIER" ] || { echo "Fichier introuvable : $FICHIER" >&2; exit 1; }
if grep -Eq "^$NOM[[:space:]]" "$FICHIER"; then
    echo "L'enseigne '$NOM' existe déjà. Pour changer son mot de passe : enseigne-revoquer.sh puis enseigne-ajouter.sh." >&2
    exit 1
fi

# lettres et chiffres seulement : pas de caractère que l'inscription de la caisse refuserait (quote, antislash)
MOT_DE_PASSE=$(openssl rand -base64 60 | tr -dc 'A-Za-z0-9' | cut -c1-40)
[ "${#MOT_DE_PASSE}" -eq 40 ] || { echo "Génération du mot de passe impossible (openssl)." >&2; exit 1; }

# shellcheck disable=SC2086
EMPREINTE=$($HASH_CMD "$MOT_DE_PASSE" | tail -n 1)
case "$EMPREINTE" in '$2'*) ;; *) echo "Calcul de l'empreinte impossible (reçu : '${EMPREINTE:0:20}')." >&2; exit 1 ;; esac

cp -p "$FICHIER" "$FICHIER.bak"
TMP=$(mktemp "$FICHIER.XXXXXX")
cp "$FICHIER" "$TMP"
printf '%s %s\n' "$NOM" "$EMPREINTE" >> "$TMP"
chmod --reference="$FICHIER" "$TMP" 2>/dev/null || chmod 640 "$TMP"
mv -f "$TMP" "$FICHIER"

if [ "$RELOAD_CMD" != ":" ]; then
    # shellcheck disable=SC2086
    if ! $RELOAD_CMD >/dev/null 2>&1; then
        mv -f "$FICHIER.bak" "$FICHIER"
        echo "Rechargement de Caddy impossible : l'ajout est annulé (ancien fichier restauré)." >&2
        exit 1
    fi
fi

# noms de domaine pour l'exemple d'inscription
METRICS="metrics.exemple.fr"; LOGS="logs.exemple.fr"
if [ -f "$RACINE/.env" ]; then
    # valeur seule : sans le commentaire de fin de ligne ni les espaces
    valeur() { grep -E "^$1=" "$RACINE/.env" | head -1 | cut -d= -f2- | sed -e 's/[[:space:]]*#.*$//' -e 's/[[:space:]]*$//'; }
    METRICS=$(valeur METRICS_DOMAIN); LOGS=$(valeur LOGS_DOMAIN)
    [ -n "$METRICS" ] || METRICS="metrics.exemple.fr"; [ -n "$LOGS" ] || LOGS="logs.exemple.fr"
fi

cat <<EOF

Enseigne créée : $NOM

  Identifiant  : $NOM
  Mot de passe : $MOT_DE_PASSE        <-- noter MAINTENANT dans votre gestionnaire de mots de passe : il ne sera plus affiché

Sur chaque caisse de cette enseigne :

  sudo storepos-kiosk-monitoring-enroll --enseigne $NOM --magasin <MAGASIN> --poste <N> \\
       --metrics-url https://$METRICS/api/v1/write --logs-url https://$LOGS/loki/api/v1/push \\
       --user $NOM

Le mot de passe est demandé au clavier (saisie masquée) : il ne reste ni dans l'historique du shell ni dans la liste des processus.
Pour scripter : --password-file /chemin/fichier (une ligne, fichier à effacer ensuite). L'inscription vérifie l'identifiant auprès du serveur
avant d'écrire quoi que ce soit : un mot de passe faux ou une adresse incorrecte est signalé tout de suite.

EOF
