#!/bin/bash
# Applique `terraform fmt` au dossier terraform/ du dépôt (réécrit les fichiers .tf avec le style officiel).
export PATH="${SUPERVISION_TOOLS:-$HOME/supervision-test/bin}:$PATH"
cd "$(dirname "${BASH_SOURCE[0]}")/../terraform" && terraform fmt -diff
