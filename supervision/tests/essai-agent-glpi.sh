#!/bin/bash
# Lance le VRAI agent GLPI (extrait sans root) contre un faux serveur et montre ce qu'il envoie.
H="$HOME/supervision-test/glpi-agent"; T=$(mktemp -d); SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 -I "$SUP/tests/fake_glpi_server.py" 18090 "$T/requetes.jsonl" & SP=$!
trap 'kill $SP 2>/dev/null; rm -rf "$T"' EXIT; sleep 1
P5="$HOME/supervision-test/perl5"
export LD_LIBRARY_PATH="$P5/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export PERL5LIB="$H/usr/share/glpi-agent/lib:$P5/usr/share/perl5:$P5/usr/lib/x86_64-linux-gnu/perl5/5.40:$P5/usr/lib/x86_64-linux-gnu/perl5/5.38:$P5/usr/lib/x86_64-linux-gnu/perl/5.40:${PERL5LIB:-}"
cat > "$T/agent.cfg" <<EOF
server = http://127.0.0.1:18090/
user = demo
password = mot-de-passe-test
tag = demo
no-httpd = 1
logger = stderr
delaytime = 1
debug = 1
EOF
mkdir -p "$T/var"
cd "$H"
timeout 120 perl -I"$H/usr/share/glpi-agent/lib" "$H/usr/bin/glpi-agent" --conf-file="$T/agent.cfg" --force --no-category=software --vardir="$T/var" 2>&1 | tail -25
echo "--- requêtes reçues par le serveur ---"
cat "$T/requetes.jsonl" 2>/dev/null | python3 -c "
import sys, json
for l in sys.stdin:
    r = json.loads(l); print(r['methode'], r['chemin'], '| auth:', r['authorization'][:12], '| type:', r['content_type'], '| taille:', r['corps_taille']); print('    ', r['corps_debut'][:160].replace('\n',' '))"
