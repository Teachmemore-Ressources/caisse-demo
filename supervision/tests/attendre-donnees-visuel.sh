#!/bin/bash
# Attend (jusqu'à 5 minutes) que les caisses simulées de l'essai visuel aient envoyé deux collectes et que les règles de santé soient calculées.
P=19390
for i in $(seq 1 150); do
    n=$(curl -fsS --get "http://127.0.0.1:$P/api/v1/query" --data-urlencode "query=count(caisse:cpu_percent)" 2>/dev/null | jq -r '.data.result[0].value[1] // "0"')
    [ "${n:-0}" -ge 9 ] 2>/dev/null && break; sleep 2
done
echo "caisses avec précalcul de santé : ${n:-0}"
curl -fsS --get "http://127.0.0.1:$P/api/v1/query" --data-urlencode "query=count by (enseigne) (up{job=\"caisse\"})" | jq -c '.data.result[] | [.metric.enseigne, .value[1]]'
