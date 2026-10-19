#!/bin/bash
# Lance le SaaS simulé puis les tests d'intégration. Usage : bash tools/run-it.sh
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="$(mktemp -d)"
python3 "$SRC/saas-mock/mock_saas.py" --port 8099 --host 127.0.0.1 --data "$DATA" >"$DATA/server.log" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
for i in $(seq 1 20); do curl -sf http://127.0.0.1:8099/api/v1/health >/dev/null && break; sleep 0.5; done
echo "== smoke curl"
curl -s http://127.0.0.1:8099/api/v1/health; echo
curl -s -o /dev/null -w "activate mauvais code -> %{http_code}\n" -X POST -d '{"code":"X"}' http://127.0.0.1:8099/api/v1/tpv/activate
curl -s -o /dev/null -w "referentiel sans jeton -> %{http_code}\n" http://127.0.0.1:8099/api/v1/sync/referentiel
echo "== tests gradle"
MOCK_SAAS_URL=http://127.0.0.1:8099 bash "$SRC/tools/wsl-build.sh" test --rerun-tasks
echo "== journal du serveur"; tail -n 8 "$DATA/server.log"
echo "== tickets reçus : $(wc -l < "$DATA/tickets.jsonl")"
