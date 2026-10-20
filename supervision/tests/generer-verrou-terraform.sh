#!/bin/bash
# Génère terraform/.terraform.lock.hcl à partir du cache de fournisseurs (sans retélécharger), pour figer les versions de fournisseurs.
pkill -f 'terraform init' 2>/dev/null; pkill -f validate-terraform 2>/dev/null; sleep 1
export PATH="$HOME/supervision-test/bin:$PATH"
export TF_PLUGIN_CACHE_DIR="$HOME/.terraform.d/plugin-cache"
printf 'plugin_cache_may_break_dependency_lock_file = true\nplugin_cache_dir = "%s"\n' "$TF_PLUGIN_CACHE_DIR" > "$HOME/.terraformrc-cache"
export TF_CLI_CONFIG_FILE="$HOME/.terraformrc-cache"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../terraform" && pwd)"
T=$(mktemp -d); cp -r "$SRC" "$T/"; cd "$T/terraform" || exit 1
date; time (timeout 600 terraform init -backend=false -input=false 2>&1 | tail -6)
[ -f .terraform.lock.hcl ] && cp .terraform.lock.hcl "$SRC/.terraform.lock.hcl" && echo "verrou copié dans $SRC" && head -12 "$SRC/.terraform.lock.hcl"
rm -rf "$T"
