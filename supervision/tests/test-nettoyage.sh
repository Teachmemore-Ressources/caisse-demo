#!/bin/bash
# Teste nettoyer-sans-etat.sh avec un faux « aws » : vérifie la logique (simulation sans effet, ordre, filtres de sécurité).
# Limite : ne prouve PAS que chaque commande AWS réelle fonctionne ; la première exécution reste à surveiller.
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
FAIL=0; ok() { echo "  OK    $*"; }; ko() { echo "  ECHEC $*"; FAIL=1; }
mkdir -p "$T/shim"; export LOG="$T/appels.log"
cat > "$T/shim/aws" <<'EOF'
#!/bin/bash
echo "aws $*" >> "$LOG"
a="$*"
case "$a" in
  *"sts get-caller-identity"*) echo 489470371015;;
  *"ec2 describe-instances"*) echo i-0abc;;
  *"ec2 describe-addresses"*) echo eipalloc-1;;
  *"ec2 describe-volumes"*) echo vol-1;;
  *"ec2 describe-vpcs"*) echo vpc-1;;
  *"describe-security-groups"*) echo sg-1;;
  *"describe-subnets"*) echo subnet-1;;
  *"describe-internet-gateways"*) echo igw-1;;
  *"describe-route-tables --route-table-ids"*) echo rtbassoc-1;;
  *"describe-route-tables"*) echo rtb-1;;
  *"head-bucket --bucket passerelle-caisses-config-371015"*) ;;
  *"head-bucket"*) exit 1;;
  *"get-bucket-tagging --bucket passerelle-caisses-config-371015"*) echo passerelle-caisses;;
  *"list-object-versions"*) echo '{}';;
  *"iam list-roles"*) printf 'passerelle-caisses-aaa\tpasserelle-caisses-autre\n';;
  *"list-role-tags --role-name passerelle-caisses-aaa"*) echo passerelle-caisses;;
  *"list-role-tags"*) echo "";;
  *"list-instance-profiles-for-role"*) echo passerelle-caisses-profil;;
  *"list-role-policies"*) echo passerelle;;
  *"list-attached-role-policies"*) echo arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore;;
esac
exit 0
EOF
chmod +x "$T/shim/aws"
export PATH="$T/shim:$PATH"
bash -n "$SUP/terraform/nettoyer-sans-etat.sh" && ok "syntaxe bash" || ko "syntaxe"

: > "$LOG"; out=$(bash "$SUP/terraform/nettoyer-sans-etat.sh" 2>&1)
echo "$out" | grep -q 'MODE SIMULATION' && ok "simulation par défaut" || ko "pas de simulation par défaut"
grep -qE 'terminate-instances|delete-vpc|delete-bucket|delete-role |delete-volume|release-address|delete-parameters|delete-alarms' "$LOG" && ko "la SIMULATION a lancé une suppression : $(grep -E 'terminate|delete|release' "$LOG" | head -3)" || ok "simulation : aucune commande de suppression lancée"
echo "$out" | grep -q 'passerelle-caisses-autre : étiquette Projet absente : IGNORÉ' && ok "un rôle sans l'étiquette du projet est ignoré" || ko "rôle sans étiquette non ignoré"
echo "$out" | grep -q 'passerelle-caisses-sauvegardes-371015 : absent' && ok "bucket absent signalé" || ko "bucket absent"

: > "$LOG"; out=$(printf 'passerelle-caisses\n' | bash "$SUP/terraform/nettoyer-sans-etat.sh" --supprimer 2>&1)
echo "$out" | grep -q 'MODE SUPPRESSION' && ok "mode suppression accepté après confirmation" || ko "confirmation"
ordre() { grep -n "$1" "$LOG" | head -1 | cut -d: -f1; }
a=$(ordre terminate-instances); b=$(ordre release-address); c=$(ordre delete-volume); d=$(ordre delete-subnet); e=$(ordre detach-internet-gateway); f=$(ordre delete-vpc)
[ -n "$a" ] && [ "$a" -lt "$b" ] && [ "$b" -lt "$e" ] && ok "ordre : instance → IP fixe → passerelle internet" || ko "ordre instance/IP/IGW ($a $b $e)"
[ "$a" -lt "$c" ] && [ "$d" -lt "$f" ] && [ "$e" -lt "$f" ] && ok "ordre : sous-réseau et passerelle internet avant le VPC" || ko "ordre VPC ($d $e $f)"
grep -q 'ec2 wait instance-terminated --instance-ids i-0abc' "$LOG" && ok "attend l'arrêt complet de l'instance" || ko "pas d'attente de l'instance"
grep -q 'delete-bucket --bucket passerelle-caisses-config-371015' "$LOG" && ok "bucket de configuration supprimé" || ko "bucket non supprimé"
grep -q 'delete-role --role-name passerelle-caisses-aaa' "$LOG" && ! grep -q 'delete-role --role-name passerelle-caisses-autre' "$LOG" && ok "seul le rôle étiqueté est supprimé" || ko "mauvais rôle supprimé"
out=$(printf 'autre-nom\n' | bash "$SUP/terraform/nettoyer-sans-etat.sh" --supprimer 2>&1); echo "$out" | grep -q 'Annulé' && ok "mauvaise confirmation → annulé" || ko "pas d'annulation"
echo; [ "$FAIL" = 0 ] && echo "NETTOYAGE : LOGIQUE OK" || { echo "NETTOYAGE : DES ECHECS"; exit 1; }
