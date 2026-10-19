#!/bin/bash
# Reproduit l'environnement du conteneur Caddy du docker-compose : PROMETHEUS_UPSTREAM et LOKI_UPSTREAM NON définies.
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
cp -r "$SUP/serveur/caddy" "$D/"; sed -i "s#/etc/caddy/#$D/caddy/#g" "$D/caddy/Caddyfile"
env -i PATH="$PATH" ACME_EMAIL=a@teachmemore.fr METRICS_DOMAIN=metrics.teachmemore.fr LOGS_DOMAIN=logs.teachmemore.fr GRAFANA_DOMAIN=grafana.invalide \
    "$BIN/caddy" validate --config "$D/caddy/Caddyfile" --adapter caddyfile 2>&1 | tail -5
echo "--- upstream résolu :"
env -i PATH="$PATH" ACME_EMAIL=a@b.fr METRICS_DOMAIN=m.exemple.fr LOGS_DOMAIN=l.exemple.fr "$BIN/caddy" adapt --config "$D/caddy/Caddyfile" --adapter caddyfile 2>/dev/null | grep -o '"dial":"[^"]*"' | sort -u
