#!/bin/bash
# Synchronise /opt/passerelle avec la configuration publiée par Terraform dans S3, régénère .env, puis (re)démarre la pile.
# Ne touche JAMAIS aux identifiants des enseignes (caddy/enseignes.caddy) ni aux sauvegardes locales.
# Lancement : sudo passerelle-sync     (ou à distance : aws ssm send-command ... "passerelle-sync")
set -euo pipefail
export PATH="$PATH:/snap/bin"
# shellcheck disable=SC1091
. /etc/passerelle.env
D=/opt/passerelle
mkdir -p "$D"

aws s3 sync --region "$REGION" --delete --only-show-errors \
    --exclude "caddy/enseignes.caddy" --exclude ".env" --exclude "sauvegardes/*" \
    "s3://$CONFIG_BUCKET/serveur/" "$D/"
# modèle inutilisable, copié seulement s'il n'existe pas encore
[ -f "$D/caddy/enseignes.caddy" ] || { mkdir -p "$D/caddy"; aws s3 cp --region "$REGION" --only-show-errors "s3://$CONFIG_BUCKET/serveur/caddy/enseignes.caddy" "$D/caddy/enseignes.caddy"; }
mkdir -p "$D/caddy/sites"
ln -sfn /srv/data/sauvegardes "$D/sauvegardes"
chmod +x "$D"/scripts/*.sh

# Mode de protection de Grafana
FICHIERS=(-f docker-compose.yml)
if [ "$GRAFANA_MODE" = "sso" ]; then
    FICHIERS+=(-f docker-compose.sso.yml)
    cp -f "$D/caddy/sites/grafana-public.caddy.exemple" "$D/caddy/sites/grafana.caddy"
else
    FICHIERS+=(-f docker-compose.prive.yml)
    rm -f "$D/caddy/sites/grafana.caddy"
fi

# Inventaire GLPI (facultatif, activé par Terraform : activer_glpi)
GLPI_ENABLED="${GLPI_ENABLED:-0}"; GLPI_DOMAIN="${GLPI_DOMAIN:-}"
if [ "$GLPI_ENABLED" = "1" ]; then
    FICHIERS+=(-f docker-compose.glpi.yml)
    cp -f "$D/caddy/sites/glpi.caddy.exemple" "$D/caddy/sites/glpi.caddy"
else
    rm -f "$D/caddy/sites/glpi.caddy"
fi

# .env régénéré à chaque synchronisation (valeurs Terraform + secrets lus dans Parameter Store)
secret() { aws ssm get-parameter --region "$REGION" --name "/$NOM/$1" --with-decryption --query Parameter.Value --output text; }
umask 077
# lus AVANT l'écriture : un échec (droits, réseau) arrête le script au lieu d'écrire un mot de passe vide
MDP_GRAFANA=$(secret grafana_admin_password); [ -n "$MDP_GRAFANA" ]
if [ "$GRAFANA_MODE" = "sso" ]; then SECRET_SSO=$(secret sso_client_secret); [ -n "$SECRET_SSO" ]; fi
if [ "$GLPI_ENABLED" = "1" ]; then
    GLPI_DB_PW=$(secret glpi_db_password); [ -n "$GLPI_DB_PW" ]
    GLPI_ROOT_PW=$(secret glpi_db_root_password); [ -n "$GLPI_ROOT_PW" ]
    GLPI_ADMIN_PW=$(secret glpi_admin_password); [ -n "$GLPI_ADMIN_PW" ]
fi
# COMPOSE_FILE : les commandes « docker compose » lancées à la main dans /opt/passerelle (scripts, sauvegardes) voient la même pile
LISTE_COMPOSE=""; for f in "${FICHIERS[@]}"; do [ "$f" != "-f" ] && LISTE_COMPOSE="${LISTE_COMPOSE:+$LISTE_COMPOSE:}$f"; done
{
    echo "COMPOSE_FILE=$LISTE_COMPOSE"
    echo "ACME_EMAIL=$ACME_EMAIL"
    echo "METRICS_DOMAIN=$METRICS_DOMAIN"
    echo "LOGS_DOMAIN=$LOGS_DOMAIN"
    [ -n "$GRAFANA_DOMAIN" ] && echo "GRAFANA_DOMAIN=$GRAFANA_DOMAIN"
    echo "GRAFANA_ADMIN_USER=$GRAFANA_ADMIN_USER"
    echo "GRAFANA_ADMIN_PASSWORD=$MDP_GRAFANA"
    if [ "$GRAFANA_MODE" = "sso" ]; then
        echo "SSO_CLIENT_ID=$SSO_CLIENT_ID"
        echo "SSO_CLIENT_SECRET=$SECRET_SSO"
        echo "SSO_DOMAINES=$SSO_DOMAINES"
    fi
    if [ "$GLPI_ENABLED" = "1" ]; then
        echo "GLPI_DOMAIN=$GLPI_DOMAIN"
        echo "GLPI_DB_PASSWORD=$GLPI_DB_PW"
        echo "GLPI_DB_ROOT_PASSWORD=$GLPI_ROOT_PW"
        echo "GLPI_ADMIN_PASSWORD=$GLPI_ADMIN_PW"
    fi
} > "$D/.env.nouveau"
mv -f "$D/.env.nouveau" "$D/.env"
chmod 600 "$D/.env"

cd "$D"
docker compose "${FICHIERS[@]}" config -q    # refuse de continuer si la configuration est invalide
docker compose "${FICHIERS[@]}" pull --quiet
docker compose "${FICHIERS[@]}" up -d --remove-orphans
# Caddy relit ses fichiers montés (identifiants des enseignes, sites)
docker compose "${FICHIERS[@]}" exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile 2>/dev/null || true
docker compose "${FICHIERS[@]}" ps
echo "passerelle-sync terminé ($(date -u +%FT%TZ))"
