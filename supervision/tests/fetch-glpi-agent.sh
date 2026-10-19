#!/bin/bash
# Télécharge le paquet officiel de l'agent GLPI (GitHub, version figée) et l'extrait SANS root dans ~/supervision-test/glpi-agent
# pour pouvoir lancer le vrai agent en test. Aucune installation système.
set -eu
V="${GLPI_AGENT_VERSION:-1.21}"
D="$HOME/supervision-test"; mkdir -p "$D/dl" "$D/glpi-agent"
F="$D/dl/glpi-agent_${V}-1_all.deb"
[ -s "$F" ] || curl -fsSL -o "$F" "https://github.com/glpi-project/glpi-agent/releases/download/${V}/glpi-agent_${V}-1_all.deb"
sha256sum "$F"
dpkg-deb -x "$F" "$D/glpi-agent"
# Test sans root : les chemins en dur de setup.pm (/usr/share/...) sont redirigés vers la copie extraite
sed -i "s#\"/usr/share/glpi-agent#\"$D/glpi-agent/usr/share/glpi-agent#" "$D/glpi-agent/usr/share/glpi-agent/lib/setup.pm"
dpkg-deb -f "$F" Version Depends | head -5
ls "$D/glpi-agent/usr/bin" "$D/glpi-agent/etc/glpi-agent" 2>/dev/null
