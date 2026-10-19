#!/bin/bash
# Valide le code Terraform HORS LIGNE (aucun compte AWS, aucune création) : formatage, init, validate, syntaxe des scripts embarqués.
# Terraform est téléchargé par : https://releases.hashicorp.com/terraform/ (placé dans ~/supervision-test/bin). Le fournisseur AWS est
# téléchargé par `terraform init`. Limite : sans identifiants AWS, ni `plan` ni `apply` ne sont possibles.
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}:$PATH"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
FAIL=0; ok() { echo "  OK    $*"; }; ko() { echo "  ECHEC $*"; FAIL=1; }
cp -r "$SUP/terraform" "$SUP/serveur" "$T/"; cd "$T/terraform"
terraform fmt -check -diff >"$T/fmt.log" 2>&1 && ok "terraform fmt : formatage conforme" || { ko "terraform fmt"; cat "$T/fmt.log"; }
terraform init -backend=false -input=false >"$T/init.log" 2>&1 && ok "terraform init" || { ko "terraform init"; tail -8 "$T/init.log"; }
terraform validate >"$T/val.log" 2>&1 && ok "terraform validate" || { ko "terraform validate"; cat "$T/val.log"; }
for f in files/*.sh; do bash -n "$f" && ok "syntaxe bash : $f" || ko "syntaxe $f"; done
# l'en-tête généré + bootstrap forment un script valide
bash -c 'sed -E "s/\\$\\{[a-z_]+\\}/x/g" files/user-data-entete.sh.tftpl; cat files/bootstrap.sh' > "$T/ud.sh" && bash -n "$T/ud.sh" && ok "user_data (en-tête + bootstrap) : syntaxe valide" || ko "user_data"
ls="$(wc -c < "$T/ud.sh")"; [ "$ls" -lt 16000 ] && ok "user_data : $ls octets (limite AWS 16 384)" || ko "user_data trop gros : $ls"
command -v shellcheck >/dev/null && { shellcheck -S warning files/*.sh && ok "shellcheck" || ko "shellcheck"; }
# le test d'exigence SSO : sans les variables, la validation métier doit refuser (precondition évaluée au plan, donc contrôlée ici par lecture)
grep -q 'grafana_mode != "sso"' secrets.tf && ok "garde-fou SSO présent (exige domaine, client, secret, domaines)" || ko "garde-fou SSO absent"
# aucun secret en dur / aucun port SSH
grep -nE 'AKIA[0-9A-Z]{16}|BEGIN (RSA|OPENSSH)' *.tf files/* && ko "secret en clair" || ok "aucune clé/secret en clair dans le code"
grep -nE 'from_port *= *22\b' *.tf && ko "port SSH ouvert" || ok "aucun port 22 ouvert dans le groupe de sécurité"
echo; [ "$FAIL" = 0 ] && echo "TERRAFORM : VALIDE (hors ligne)" || { echo "TERRAFORM : DES ECHECS"; exit 1; }
