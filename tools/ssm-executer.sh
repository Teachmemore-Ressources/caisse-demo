#!/bin/bash
#
# Exécute des commandes sur le serveur AWS SANS ouvrir de session (AWS Systems Manager), puis affiche le résultat.
# Évite tous les problèmes de guillemets : les commandes sont lues dans un fichier, une par ligne.
#
#   bash tools/ssm-executer.sh commandes.txt
#   bash tools/ssm-executer.sh - <<'EOF'
#   cd /opt/passerelle && docker compose ps --format '{{.Service}}: {{.Status}}'
#   df -h /srv/data
#   EOF
#
# Les commandes s'exécutent en root, avec « sh » (pas bash) : pas de ${var:0:4}, pas de [[ ]].
# Prérequis : AWS CLI configurée, et être sur la machine où se trouve le terraform.tfstate (le serveur est identifié par `terraform output`).
set -u
RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REGION="${AWS_REGION:-eu-west-3}"
SRC="${1:-}"; [ -n "$SRC" ] || { sed -n '3,13p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
SRC_TMP=""; J=$(mktemp); trap 'rm -f "$J" "$SRC_TMP"' EXIT
[ "$SRC" = "-" ] && { SRC_TMP=$(mktemp); cat > "$SRC_TMP"; SRC="$SRC_TMP"; }
[ -r "$SRC" ] || { echo "Fichier de commandes introuvable : $SRC" >&2; exit 1; }

I=$(cd "$RACINE/supervision/terraform" && terraform output -raw instance_id 2>/dev/null)
[ -n "$I" ] || { echo "Identifiant du serveur introuvable : lancer depuis la machine qui a le terraform.tfstate." >&2; exit 1; }

python3 - "$SRC" > "$J" <<'PY'
import json, sys
print(json.dumps({"commands": [l.rstrip("\n") for l in open(sys.argv[1]) if l.strip()]}))
PY
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$I" --document-name AWS-RunShellScript \
      --timeout-seconds 1500 --parameters "file://$J" --query Command.CommandId --output text) || exit 1
for _ in $(seq 1 300); do
    S=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$I" --query Status --output text 2>/dev/null)
    case "$S" in Success|Failed|TimedOut|Cancelled) break ;; esac
    sleep 4
done
echo "Statut : ${S:-inconnu}"
aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$I" --query StandardOutputContent --output text
aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$I" --query StandardErrorContent --output text | tail -10
[ "${S:-}" = "Success" ]
