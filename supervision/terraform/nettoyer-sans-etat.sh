#!/bin/bash
#
# Supprime TOUT ce que ce projet a créé sur AWS, SANS passer par l'état Terraform (utile si terraform.tfstate est perdu).
# Les ressources sont retrouvées par leur étiquette  Projet=<nom>  (posée par Terraform sur tout ce qui peut en porter une)
# ou par leur nom (buckets, alarmes, paramètres). Rien d'autre n'est touché.
#
#   bash nettoyer-sans-etat.sh                 SIMULATION : liste ce qui serait supprimé, ne supprime RIEN
#   bash nettoyer-sans-etat.sh --supprimer     supprime, après que vous avez retapé le nom du projet
#
# Variables : NOM (défaut passerelle-caisses)   REGION (défaut eu-west-3)
# ATTENTION : irréversible. Le disque de données, les sauvegardes S3 et les secrets sont détruits.
set -uo pipefail
NOM="${NOM:-passerelle-caisses}"; REGION="${REGION:-eu-west-3}"
APPLIQUER=0; [ "${1:-}" = "--supprimer" ] && APPLIQUER=1
A=(aws --region "$REGION")

ACCOUNT=$(aws sts get-caller-identity --query Account --output text 2>/dev/null) || { echo "Identifiants AWS invalides (aws sts get-caller-identity a échoué)." >&2; exit 1; }
SUFFIXE="${ACCOUNT: -6}"
echo "Compte AWS : $ACCOUNT | région : $REGION | projet : $NOM"
[ "$APPLIQUER" = 1 ] && echo "MODE SUPPRESSION" || echo "MODE SIMULATION (rien n'est supprimé)"

if [ "$APPLIQUER" = 1 ]; then
    read -r -p "Tapez le nom du projet ($NOM) pour confirmer la suppression DÉFINITIVE : " saisie
    [ "$saisie" = "$NOM" ] || { echo "Annulé."; exit 1; }
fi

faire() {  # faire <description> <commande...> : n'exécute qu'en mode suppression
    local d="$1"; shift
    if [ "$APPLIQUER" = 1 ]; then echo "  - $d"; "$@" >/dev/null 2>&1 || echo "    (échec ou déjà supprimé : $*)"
    else echo "  [simulation] $d"; fi
}
etape() { printf '\n== %s ==\n' "$*"; }

etape "Alarmes CloudWatch, budget, notifications"
faire "alarmes" "${A[@]}" cloudwatch delete-alarms --alarm-names "$NOM-panne-materielle" "$NOM-instance-bloquee"
faire "rubrique SNS $NOM-alertes" "${A[@]}" sns delete-topic --topic-arn "arn:aws:sns:$REGION:$ACCOUNT:$NOM-alertes"
faire "budget $NOM-mensuel" aws budgets delete-budget --account-id "$ACCOUNT" --budget-name "$NOM-mensuel"

etape "Instantanés automatiques (DLM)"
for p in $("${A[@]}" dlm get-lifecycle-policies --query 'Policies[].PolicyId' --output text 2>/dev/null); do
    t=$("${A[@]}" dlm get-lifecycle-policy --policy-id "$p" --query 'Policy.Tags.Projet' --output text 2>/dev/null)
    [ "$t" = "$NOM" ] && faire "politique DLM $p" "${A[@]}" dlm delete-lifecycle-policy --policy-id "$p"
done

etape "Instance EC2"
IDS=$("${A[@]}" ec2 describe-instances --filters "Name=tag:Projet,Values=$NOM" "Name=instance-state-name,Values=pending,running,stopping,stopped" --query 'Reservations[].Instances[].InstanceId' --output text 2>/dev/null)
echo "  trouvée(s) : ${IDS:-aucune}"
for i in $IDS; do
    faire "levée de la protection contre la suppression ($i)" "${A[@]}" ec2 modify-instance-attribute --instance-id "$i" --no-disable-api-termination
    faire "arrêt définitif de $i" "${A[@]}" ec2 terminate-instances --instance-ids "$i"
done
if [ "$APPLIQUER" = 1 ] && [ -n "$IDS" ]; then echo "  attente de l'arrêt complet…"; "${A[@]}" ec2 wait instance-terminated --instance-ids $IDS; fi

etape "Adresses IP fixes"
for a in $("${A[@]}" ec2 describe-addresses --filters "Name=tag:Projet,Values=$NOM" --query 'Addresses[].AllocationId' --output text 2>/dev/null); do
    faire "libération de $a" "${A[@]}" ec2 release-address --allocation-id "$a"
done

etape "Disques de données"
for v in $("${A[@]}" ec2 describe-volumes --filters "Name=tag:Projet,Values=$NOM" --query 'Volumes[].VolumeId' --output text 2>/dev/null); do
    [ "$APPLIQUER" = 1 ] && "${A[@]}" ec2 wait volume-available --volume-ids "$v" 2>/dev/null
    faire "suppression du disque $v" "${A[@]}" ec2 delete-volume --volume-id "$v"
done

etape "Réseau (VPC)"
for vpc in $("${A[@]}" ec2 describe-vpcs --filters "Name=tag:Projet,Values=$NOM" --query 'Vpcs[].VpcId' --output text 2>/dev/null); do
    echo "  VPC $vpc"
    for sg in $("${A[@]}" ec2 describe-security-groups --filters "Name=vpc-id,Values=$vpc" --query "SecurityGroups[?GroupName!='default'].GroupId" --output text 2>/dev/null); do
        faire "groupe de sécurité $sg" "${A[@]}" ec2 delete-security-group --group-id "$sg"
    done
    for rt in $("${A[@]}" ec2 describe-route-tables --filters "Name=vpc-id,Values=$vpc" --query "RouteTables[?length(Associations[?Main==\`true\`])==\`0\`].RouteTableId" --output text 2>/dev/null); do
        for assoc in $("${A[@]}" ec2 describe-route-tables --route-table-ids "$rt" --query 'RouteTables[].Associations[].RouteTableAssociationId' --output text 2>/dev/null); do
            faire "dissociation $assoc" "${A[@]}" ec2 disassociate-route-table --association-id "$assoc"
        done
    done
    for s in $("${A[@]}" ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc" --query 'Subnets[].SubnetId' --output text 2>/dev/null); do
        faire "sous-réseau $s" "${A[@]}" ec2 delete-subnet --subnet-id "$s"
    done
    for rt in $("${A[@]}" ec2 describe-route-tables --filters "Name=vpc-id,Values=$vpc" --query "RouteTables[?length(Associations[?Main==\`true\`])==\`0\`].RouteTableId" --output text 2>/dev/null); do
        faire "table de routage $rt" "${A[@]}" ec2 delete-route-table --route-table-id "$rt"
    done
    for igw in $("${A[@]}" ec2 describe-internet-gateways --filters "Name=attachment.vpc-id,Values=$vpc" --query 'InternetGateways[].InternetGatewayId' --output text 2>/dev/null); do
        faire "détachement de la passerelle internet $igw" "${A[@]}" ec2 detach-internet-gateway --internet-gateway-id "$igw" --vpc-id "$vpc"
        faire "suppression de la passerelle internet $igw" "${A[@]}" ec2 delete-internet-gateway --internet-gateway-id "$igw"
    done
    faire "VPC $vpc" "${A[@]}" ec2 delete-vpc --vpc-id "$vpc"
done

etape "Paramètres secrets (Parameter Store)"
faire "/$NOM/grafana_admin_password et /$NOM/sso_client_secret" "${A[@]}" ssm delete-parameters --names "/$NOM/grafana_admin_password" "/$NOM/sso_client_secret"

vider_bucket() {  # supprime toutes les versions et marqueurs de suppression
    local b="$1" json del
    while :; do
        json=$(aws s3api list-object-versions --bucket "$b" --max-items 1000 --output json 2>/dev/null) || break
        del=$(printf '%s' "$json" | python3 -c 'import sys,json; d=json.load(sys.stdin); o=[{"Key":v["Key"],"VersionId":v["VersionId"]} for v in (d.get("Versions") or [])+(d.get("DeleteMarkers") or [])]; print(json.dumps({"Objects":o,"Quiet":True}) if o else "")')
        [ -z "$del" ] && break
        aws s3api delete-objects --bucket "$b" --delete "$del" >/dev/null || break
    done
}

etape "Buckets S3"
for b in "$NOM-config-$SUFFIXE" "$NOM-sauvegardes-$SUFFIXE"; do
    if ! aws s3api head-bucket --bucket "$b" 2>/dev/null; then echo "  $b : absent"; continue; fi
    t=$(aws s3api get-bucket-tagging --bucket "$b" --query "TagSet[?Key=='Projet'].Value" --output text 2>/dev/null)
    if [ "$t" != "$NOM" ]; then echo "  $b : étiquette Projet absente ou différente ('$t') : IGNORÉ par sécurité"; continue; fi
    if [ "$APPLIQUER" = 1 ]; then echo "  - vidage puis suppression de $b"; vider_bucket "$b"; aws s3api delete-bucket --bucket "$b" || echo "    échec de suppression de $b"
    else echo "  [simulation] vidage (toutes versions) puis suppression de $b"; fi
done

etape "Rôles IAM"
for r in $(aws iam list-roles --query "Roles[?starts_with(RoleName, '$NOM-')].RoleName" --output text 2>/dev/null); do
    t=$(aws iam list-role-tags --role-name "$r" --query "Tags[?Key=='Projet'].Value" --output text 2>/dev/null)
    if [ "$t" != "$NOM" ]; then echo "  $r : étiquette Projet absente : IGNORÉ par sécurité"; continue; fi
    echo "  rôle $r"
    for ip in $(aws iam list-instance-profiles-for-role --role-name "$r" --query 'InstanceProfiles[].InstanceProfileName' --output text 2>/dev/null); do
        faire "profil d'instance $ip" aws iam remove-role-from-instance-profile --instance-profile-name "$ip" --role-name "$r"
        faire "suppression du profil $ip" aws iam delete-instance-profile --instance-profile-name "$ip"
    done
    for p in $(aws iam list-role-policies --role-name "$r" --query 'PolicyNames[]' --output text 2>/dev/null); do
        faire "politique en ligne $p" aws iam delete-role-policy --role-name "$r" --policy-name "$p"
    done
    for p in $(aws iam list-attached-role-policies --role-name "$r" --query 'AttachedPolicies[].PolicyArn' --output text 2>/dev/null); do
        faire "détachement de $p" aws iam detach-role-policy --role-name "$r" --policy-arn "$p"
    done
    faire "rôle $r" aws iam delete-role --role-name "$r"
done

echo
if [ "$APPLIQUER" = 1 ]; then
    echo "Terminé. Relancez la commande en simulation pour vérifier qu'il ne reste rien :  bash $0"
    echo "Pensez aussi à supprimer les enregistrements DNS metrics/logs chez votre gestionnaire de domaine."
else
    echo "Simulation terminée. Pour supprimer réellement :  bash $0 --supprimer"
fi
