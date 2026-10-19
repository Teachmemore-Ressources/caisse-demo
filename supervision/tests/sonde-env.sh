#!/bin/bash
for c in php mysqld mariadbd mariadb-install-db docker podman perl dpkg-deb; do printf '%-18s %s\n' "$c" "$(command -v $c || echo absent)"; done
echo "sudo sans mot de passe : $(sudo -n true 2>&1 && echo oui || echo non)"
echo "perl modules: $(perl -MLWP::UserAgent -e 'print "LWP ok"' 2>&1 | head -1)"
lsb_release -ds 2>/dev/null; nproc; free -m | sed -n 2p; df -h ~ | tail -1
