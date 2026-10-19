#!/bin/bash
# Télécharge (une fois, en cache) les binaires utilisés par les tests de bout en bout : aucun droit root requis.
set -euo pipefail
BIN="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}"
mkdir -p "$BIN" /tmp/sup-dl
cd /tmp/sup-dl

PROM=3.15.0; ALLOY=1.20.1; AM=0.34.1; LOKI=3.7.8; GRAFANA=13.2.4; CADDY=2.11.7

dl() {  # dl <url> <fichier>
    [ -s "$2" ] || { echo "  téléchargement : $1"; curl -fSL --retry 3 -o "$2" "$1"; }
}

[ -x "$BIN/prometheus" ] || {
    dl "https://github.com/prometheus/prometheus/releases/download/v$PROM/prometheus-$PROM.linux-amd64.tar.gz" prom.tgz
    tar -xzf prom.tgz && cp "prometheus-$PROM.linux-amd64/prometheus" "prometheus-$PROM.linux-amd64/promtool" "$BIN/"
}
[ -x "$BIN/alloy" ] || {
    dl "https://github.com/grafana/alloy/releases/download/v$ALLOY/alloy-linux-amd64.zip" alloy.zip
    unzip -qo alloy.zip && cp alloy-linux-amd64 "$BIN/alloy" && chmod +x "$BIN/alloy"
}
[ -x "$BIN/amtool" ] || {
    dl "https://github.com/prometheus/alertmanager/releases/download/v$AM/alertmanager-$AM.linux-amd64.tar.gz" am.tgz
    tar -xzf am.tgz && cp "alertmanager-$AM.linux-amd64/alertmanager" "alertmanager-$AM.linux-amd64/amtool" "$BIN/"
}
[ -x "$BIN/caddy" ] || {
    dl "https://github.com/caddyserver/caddy/releases/download/v$CADDY/caddy_${CADDY}_linux_amd64.tar.gz" caddy.tgz
    tar -xzf caddy.tgz caddy && cp caddy "$BIN/caddy" && chmod +x "$BIN/caddy"
}
[ -x "$BIN/loki" ] || {
    dl "https://github.com/grafana/loki/releases/download/v$LOKI/loki-linux-amd64.zip" loki.zip
    unzip -qo loki.zip && cp loki-linux-amd64 "$BIN/loki" && chmod +x "$BIN/loki"
}
[ -x "$BIN/grafana/bin/grafana" ] || {
    dl "https://dl.grafana.com/oss/release/grafana-$GRAFANA.linux-amd64.tar.gz" grafana.tgz
    mkdir -p "$BIN/grafana" && tar -xzf grafana.tgz -C "$BIN/grafana" --strip-components=1
}
echo "Outils prêts dans $BIN :"; ls "$BIN"
"$BIN/prometheus" --version 2>&1 | head -1; "$BIN/alloy" --version 2>&1 | head -1; "$BIN/loki" --version 2>&1 | head -1; "$BIN/amtool" --version 2>&1 | head -1; "$BIN/caddy" version 2>&1 | head -1
"$BIN/grafana/bin/grafana" --version 2>&1 | head -1 || true
