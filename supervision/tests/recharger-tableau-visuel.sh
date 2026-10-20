#!/bin/bash
# Copie le tableau de bord régénéré dans le Grafana de l'essai visuel (qui le relit seul, toutes les 10 s).
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
d=$(find /tmp -maxdepth 4 -name parc-caisses.json -path '*g/dash*' 2>/dev/null | head -1)
[ -n "$d" ] || { echo "Grafana de l'essai visuel introuvable (lancer essai-visuel-grafana.sh)"; exit 1; }
cp "$SUP/serveur/grafana/dashboards/parc-caisses.json" "$d" && echo "copié dans $d"
