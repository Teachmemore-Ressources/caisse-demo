#!/bin/bash
# Teste les scripts de l'agent dans un bac à sable (aucun fichier système touché) :
# inscription (storepos-kiosk-monitoring-enroll) et mesures système (kiosk-metrics.sh).
set -u
SUP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENROLL="$SUP/agent/pkg/usr/sbin/storepos-kiosk-monitoring-enroll"
METR="$SUP/agent/pkg/usr/lib/storepos-kiosk/kiosk-metrics.sh"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
FAIL=0
ok() { echo "  OK    $*"; }
ko() { echo "  ECHEC $*"; FAIL=1; }

export STOREPOS_TEST=1 STOREPOS_MONITORING_CONF="$T/conf" STOREPOS_MACHINE_ID_FILE="$T/machine-id"
cp "$SUP/agent/pkg/etc/default/storepos-kiosk-monitoring" "$T/conf"
echo "0123456789abcdef0123456789abcdef" > "$T/machine-id"

echo "== inscription : cas nominal"
bash "$ENROLL" --enseigne LECLERC --magasin LYON-01 --poste 2 --metrics-url https://metrics.client.fr/api/v1/write \
    --logs-url https://logs.client.fr/loki/api/v1/push --user agent-lyon --password 'S3cret-ok' >"$T/out" 2>&1 \
    && ok "commande acceptée" || { ko "commande refusée"; cat "$T/out"; }
grep -q '^ENSEIGNE=LECLERC$' "$T/conf" && grep -q '^MAGASIN=LYON-01$' "$T/conf" && grep -q '^POSTE=2$' "$T/conf" && ok "étiquettes écrites" || ko "étiquettes absentes"
grep -q '^MACHINE_ID=0123456789ab$' "$T/conf" && ok "identifiant machine dérivé de machine-id (12 caractères)" || ko "identifiant machine : $(grep MACHINE_ID "$T/conf")"
grep -q '^METRICS_USER=agent-lyon$' "$T/conf" && grep -q '^LOGS_USER=agent-lyon$' "$T/conf" && ok "identifiant repris pour les mesures et les journaux" || ko "identifiants"
[ "$(stat -c %a "$T/conf")" = "640" ] && ok "fichier en 0640 (identifiants protégés)" || ko "droits du fichier : $(stat -c %a "$T/conf")"
bash -c ". '$T/conf'; [ \"\$METRICS_PASSWORD\" = 'S3cret-ok' ]" && ok "le fichier est relu sans erreur par un shell (format sûr)" || ko "fichier non relisible"

echo "== inscription : jeton de service Cloudflare"
bash "$ENROLL" --cf-access-id abcd1234.access --cf-access-secret 's3cr3t-token-value' >"$T/out" 2>&1 && ok "jetons acceptés" || { ko "jetons refusés"; cat "$T/out"; }
grep -q '^CF_ACCESS_ID=abcd1234.access$' "$T/conf" && grep -q '^CF_ACCESS_SECRET=s3cr3t-token-value$' "$T/conf" && ok "jetons écrits" || ko "jetons absents du fichier"
bash -c ". '$T/conf'; [ \"\$CF_ACCESS_SECRET\" = 's3cr3t-token-value' ] && [ \"\$METRICS_PASSWORD\" = 'S3cret-ok' ]" && ok "le fichier reste relisible et les autres valeurs sont conservées" || ko "fichier altéré"
# sur un fichier neuf (sans jeton déjà enregistré) : un seul des deux n'a pas de sens
cp "$SUP/agent/pkg/etc/default/storepos-kiosk-monitoring" "$T/conf.neuf"
if STOREPOS_MONITORING_CONF="$T/conf.neuf" bash "$ENROLL" --cf-access-id seul >"$T/out" 2>&1; then ko "identifiant sans secret accepté à tort"; else ok "identifiant sans secret refusé ($(head -1 "$T/out" | cut -c1-60))"; fi
if STOREPOS_MONITORING_CONF="$T/conf.neuf" bash "$ENROLL" --cf-access-secret seul >"$T/out" 2>&1; then ko "secret sans identifiant accepté à tort"; else ok "secret sans identifiant refusé ($(head -1 "$T/out" | cut -c1-60))"; fi

echo "== inscription : on conserve les valeurs existantes et on ne touche que ce qui est demandé"
bash "$ENROLL" --poste 3 >/dev/null 2>&1
grep -q '^POSTE=3$' "$T/conf" && grep -q '^MAGASIN=LYON-01$' "$T/conf" && grep -q '^METRICS_PASSWORD=S3cret-ok$' "$T/conf" && ok "seul le poste a changé" || ko "valeurs perdues"

echo "== inscription : refus des valeurs dangereuses"
refus() { # refus <description> <arguments...>
    d="$1"; shift
    if bash "$ENROLL" "$@" >"$T/out" 2>&1; then ko "$d : accepté à tort"
    elif grep -q "Exécuter avec sudo" "$T/out"; then ko "$d : refusé pour la mauvaise raison (sudo), pas par validation"
    else ok "$d : refusé ($(head -1 "$T/out" | cut -c1-70))"; fi
}
refus "URL en http:// non locale"       --metrics-url http://metrics.client.fr/api/v1/write
refus "étiquette avec espace"           --magasin 'LYON 01'
refus "étiquette avec injection"        --magasin 'X$(reboot)'
refus "mot de passe avec guillemet"     --password 'a"b'
refus "mot de passe avec apostrophe"    --password "a'b"
refus "argument inconnu"                --pirate oui
cp "$T/conf" "$T/conf.apres"; bash "$ENROLL" --magasin 'a b' >/dev/null 2>&1; cmp -s "$T/conf" "$T/conf.apres" && ok "le fichier n'est pas modifié après un refus" || ko "fichier altéré par un refus"

echo "== inscription : vérification auprès du serveur (faux curl)"
cat > "$T/curl-fake" <<'EOF'
#!/bin/bash
# imite le serveur : le code renvoyé vient de FAUX_CODE ; enregistre l'appel (sans mot de passe en clair dans le journal)
echo "appel $*" | sed 's/:[^ ]* http/:*** http/' >> "$FAUX_LOG"
printf '%s' "${FAUX_CODE:-400}"
[ "${FAUX_CODE:-400}" = "000" ] && exit 7
exit 0
EOF
chmod +x "$T/curl-fake"
export STOREPOS_VERIFY=1 STOREPOS_CURL="$T/curl-fake" FAUX_LOG="$T/curl.log"
ARGS=(--enseigne LECLERC --magasin LYON-01 --poste 2 --metrics-url https://metrics.client.fr/api/v1/write --logs-url https://logs.client.fr/loki/api/v1/push --user agent-lyon)
cp "$T/conf" "$T/conf.avant"
printf 'MotDePasseEnFichier1
' > "$T/mdp"
FAUX_CODE=401 bash "$ENROLL" "${ARGS[@]}" --password-file "$T/mdp" >"$T/out" 2>&1; rc=$?
[ "$rc" = 2 ] && grep -q "REFUSÉ" "$T/out" && ok "identifiant refusé (401) : sortie 2 et message clair" || { ko "401 mal traité (code $rc)"; cat "$T/out"; }
cmp -s "$T/conf" "$T/conf.avant" && ok "identifiant refusé : le fichier de réglages n'est pas modifié" || ko "fichier modifié malgré le refus"
FAUX_CODE=404 bash "$ENROLL" "${ARGS[@]}" --password-file "$T/mdp" >"$T/out" 2>&1; rc=$?
[ "$rc" = 2 ] && grep -q "ADRESSE INCORRECTE" "$T/out" && ok "mauvaise adresse (404) : sortie 2 et message clair" || ko "404 mal traité (code $rc)"
FAUX_CODE=400 bash "$ENROLL" "${ARGS[@]}" --password-file "$T/mdp" >"$T/out" 2>&1; rc=$?
[ "$rc" = 0 ] && grep -q "vérifié : les mesures" "$T/out" && grep -q "vérifié : les journaux" "$T/out" && ok "identifiant accepté (400 sur envoi vide) : inscription faite, mesures et journaux vérifiés" || { ko "cas nominal (code $rc)"; cat "$T/out"; }
bash -c ". '$T/conf'; [ \"\$METRICS_PASSWORD\" = 'MotDePasseEnFichier1' ]" && ok "mot de passe lu dans le fichier (jamais sur la ligne de commande)" || ko "mot de passe du fichier non écrit"
! grep -q "MotDePasseEnFichier1" "$T/curl.log" && ok "le mot de passe n'apparaît pas dans le journal de l'appel" || ko "mot de passe visible dans l'appel (journal du test)"
FAUX_CODE=000 bash "$ENROLL" "${ARGS[@]}" --password-file "$T/mdp" >"$T/out" 2>&1; rc=$?
[ "$rc" = 0 ] && grep -q "AVERTISSEMENT : serveur injoignable" "$T/out" && ok "serveur injoignable : avertissement, inscription poursuivie (installation hors ligne possible)" || ko "000 mal traité (code $rc)"
: > "$T/curl.log"; FAUX_CODE=401 bash "$ENROLL" "${ARGS[@]}" --password-file "$T/mdp" --no-verify >/dev/null 2>&1; rc=$?
[ "$rc" = 0 ] && [ ! -s "$T/curl.log" ] && ok "--no-verify : aucun appel au serveur" || ko "--no-verify ignoré"
cp "$SUP/agent/pkg/etc/default/storepos-kiosk-monitoring" "$T/conf-vierge"
STOREPOS_MONITORING_CONF="$T/conf-vierge" bash "$ENROLL" --metrics-url https://m.fr/api/v1/write --enseigne X1 --magasin M --poste 1 --user u </dev/null >"$T/out" 2>&1; rc=$?
[ "$rc" = 1 ] && ok "sans mot de passe ni terminal : refusé (pas de mot de passe vide)" || ko "mot de passe vide accepté (code $rc)"
unset STOREPOS_VERIFY STOREPOS_CURL FAUX_LOG

echo "== mesures système : format et contenu"
METRICS_DIR="$T/m" bash "$METR" && ok "script exécuté" || ko "script en échec"
f="$T/m/kiosk.prom"
for s in kiosk_os_info kiosk_reboot_required kiosk_usb_device_present kiosk_metrics_last_run_timestamp_seconds; do
    grep -q "^$s" "$f" && ok "série $s" || ko "série $s absente"
done
[ ! -e "$f.tmp" ] && ok "pas de fichier temporaire laissé (écriture atomique)" || ko "fichier temporaire resté"
grep -E '^[a-z_]+(\{.*\})? [^ ]+$|^#' "$f" | wc -l | xargs -I{} sh -c 'test {} -eq $(wc -l < '"$f"')' && ok "toutes les lignes sont des séries ou des commentaires valides" || ko "ligne invalide dans kiosk.prom"

echo; [ "$FAIL" -eq 0 ] && echo "AGENT : TOUT EST OK" || { echo "AGENT : DES ECHECS"; exit 1; }
