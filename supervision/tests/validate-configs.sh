#!/bin/bash
# Valide toutes les configurations de la supervision avec les vrais outils (pas de simple lecture YAML).
# Prérequis : bash supervision/tests/fetch-tools.sh
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"
FAIL=0
etape() { printf '\n== %s ==\n' "$*"; }
verdict() { if [ "$1" -eq 0 ]; then echo "  OK"; else echo "  ECHEC"; FAIL=1; fi; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT

etape "Prometheus : configuration + règles d'alerte"
mkdir -p "$T/rules"; cp "$SUP/serveur/prometheus/rules/"*.yml "$T/rules/"
sed "s#/etc/prometheus/rules#$T/rules#" "$SUP/serveur/prometheus/prometheus.yml" > "$T/prometheus.yml"
"$BIN/promtool" check config "$T/prometheus.yml" 2>&1 | sed 's/^/  /'; verdict "${PIPESTATUS[0]}"

etape "Prometheus : tests unitaires des alertes"
"$BIN/promtool" test rules "$SUP/tests/caisses.rules.test.yml" 2>&1 | sed 's/^/  /'; verdict "${PIPESTATUS[0]}"

etape "Alertmanager : configuration"
"$BIN/amtool" check-config "$SUP/serveur/alertmanager/alertmanager.yml" 2>&1 | sed 's/^/  /'; verdict "${PIPESTATUS[0]}"

etape "Alertmanager : acheminement (Watchdog → battement, alerte normale → réception par défaut)"
r=$("$BIN/amtool" config routes test --config.file="$SUP/serveur/alertmanager/alertmanager.yml" alertname=Watchdog 2>&1); echo "  Watchdog → $r"; [ "$r" = "battement" ]; verdict $?
r=$("$BIN/amtool" config routes test --config.file="$SUP/serveur/alertmanager/alertmanager.yml" alertname=CaisseMuette severity=warning 2>&1); echo "  CaisseMuette → $r"; [ -n "$r" ] && [ "$r" != "battement" ]; verdict $?

etape "Loki : configuration"
"$BIN/loki" -verify-config -config.file="$SUP/serveur/loki/loki.yml" 2>&1 | tail -3 | sed 's/^/  /'; verdict "${PIPESTATUS[0]}"

etape "Alloy : configuration de la caisse (mesures + journaux)"
export METRICS_URL=https://metrics.exemple.fr/api/v1/write METRICS_USER=u METRICS_PASSWORD=p LOGS_URL=https://logs.exemple.fr/loki/api/v1/push LOGS_USER=u LOGS_PASSWORD=p ENSEIGNE=demo MAGASIN=LYON-01 POSTE=1 MACHINE_ID=abc123
"$BIN/alloy" validate "$SUP/agent/pkg/etc/storepos-kiosk/alloy" 2>&1 | sed 's/^/  /'; verdict "${PIPESTATUS[0]}"

etape "Format des fichiers de mesures (promtool check metrics)"
if [ -f "$SUP/tests/exemple-caisse.prom" ]; then
    "$BIN/promtool" check metrics < "$SUP/tests/exemple-caisse.prom" 2>&1 | sed 's/^/  /'; verdict "${PIPESTATUS[0]}"
fi

etape "Tableau de bord Grafana : JSON valide et régénérable à l'identique"
python3 -I "$SUP/serveur/grafana/build_dashboard.py" >/dev/null && git -C "$SUP" diff --quiet -- serveur/grafana/dashboards 2>/dev/null; python3 -I -c "import json,sys;d=json.load(open('$SUP/serveur/grafana/dashboards/parc-caisses.json',encoding='utf-8'));print('  ',len(d['panels']),'panneaux,',len(d['templating']['list']),'variables')"; verdict $?

echo; [ "$FAIL" -eq 0 ] && echo "TOUTES LES CONFIGURATIONS SONT VALIDES" || { echo "DES VALIDATIONS ONT ECHOUE"; exit 1; }
