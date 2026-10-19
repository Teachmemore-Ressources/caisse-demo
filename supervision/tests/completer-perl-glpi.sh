#!/bin/bash
# Complète automatiquement les modules Perl manquants pour le protocole d'inventaire natif de l'agent GLPI (tests sans root).
D="$HOME/supervision-test"; P="$D/perl5"; H="$D/glpi-agent"; cd "$D/dl-perl" || exit 1
export LD_LIBRARY_PATH="$P/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export PERL5LIB="$H/usr/share/glpi-agent/lib:$P/usr/share/perl5:$P/usr/lib/x86_64-linux-gnu/perl5/5.40:$P/usr/lib/x86_64-linux-gnu/perl/5.40"
for i in $(seq 1 40); do
    sortie=$(perl -e 'use GLPI::Agent::Protocol::Inventory; print "ok\n"' 2>&1 | head -3)
    echo "$sortie" | grep -q '^ok$' && { echo "OK après $i essai(s)"; exit 0; }
    mod=$(echo "$sortie" | sed -n "s/^Can't locate \([A-Za-z0-9_\/]*\)\.pm.*/\1/p" | head -1 | sed 's#/#::#g')
    [ -n "$mod" ] || { echo "Erreur inattendue : $sortie"; exit 1; }
    pkg="lib$(echo "$mod" | tr 'A-Z' 'a-z' | sed 's/::/-/g; s/_/-/g')-perl"
    echo "  manquant : $mod -> $pkg"
    apt-get download "$pkg" >/dev/null 2>&1 || { echo "  paquet introuvable : $pkg"; exit 1; }
    dpkg-deb -x "$pkg"_*.deb "$P"
done
echo "trop d'essais"; exit 1
