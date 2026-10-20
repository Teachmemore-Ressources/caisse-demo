# Toutes les commandes AWS (aide-mémoire)

Poste de travail : la machine Linux (`kiosk@192.168.56.3`), dossier `~/caisse-demo`. Région : `eu-west-3` (Paris).
Chaque bloc se copie tel quel. Les mots de passe ne sont jamais écrits ici : on les lit dans AWS (§ 7).

## 0. Se placer au bon endroit (à faire en premier, dans chaque nouveau terminal)

```bash
cd ~/caisse-demo/supervision/terraform
export R=eu-west-3
export I=$(terraform output -raw instance_id)
echo "$I"        # doit afficher i-xxxxxxxx ; vide = vous n'êtes pas dans le dossier terraform
```
`terraform output` ne marche que **dans ce dossier** (c'est la cause de l'erreur « argument --target: expected one argument »).

## 1. Vérifier ses accès

```bash
aws --version
aws sts get-caller-identity            # doit répondre (compte, utilisateur) ; sinon : aws configure (vous saisissez vos clés)
session-manager-plugin                 # doit afficher « successfully installed » (sinon § 11)
```

## 2. Terraform

```bash
terraform init                         # une fois, ou après un changement de version
terraform plan                         # À LIRE : « No changes » ou la liste de ce qui va changer
terraform apply                        # publie les fichiers modifiés de ../serveur dans S3 (et crée/modifie l'infrastructure)
terraform output                       # toutes les valeurs utiles
terraform output -raw instance_id
terraform state list | head            # vérifie que l'état est bien là
```
**Ne lancez jamais `terraform apply` dans un dossier sans `terraform.tfstate`** : Terraform voudrait tout recréer. Sauvegardez ce fichier (il contient des secrets, ne le publiez pas).

## 3. Ouvrir une session sur le serveur (sans SSH)

```bash
aws ssm start-session --region $R --target $I
```
Dans la session : `sudo -i`, puis `cd /opt/passerelle`. Quitter : `exit`.
Le serveur est-il joignable par Systems Manager ?
```bash
aws ssm describe-instance-information --region $R --query "InstanceInformationList[].[InstanceId,PingStatus,PlatformName]" --output table
aws ec2 describe-instances --region $R --instance-ids $I --query "Reservations[].Instances[].[State.Name,PublicIpAddress]" --output text
```

## 4. Ouvrir Grafana (et GLPI) depuis son poste

Sur la machine Linux (laisser la fenêtre ouverte, elle affiche « Waiting for connections ») :
```bash
aws ssm start-session --region $R --target $I --document-name AWS-StartPortForwardingSession --parameters portNumber=3000,localPortNumber=3000
```
Puis <http://localhost:3000> **dans un navigateur de cette machine Linux**. Compte `admin`, mot de passe : § 7.

Pour l'ouvrir depuis Windows, dans PowerShell (second terminal) :
```powershell
ssh -N -L 3000:127.0.0.1:3000 kiosk@192.168.56.3
```
puis <http://localhost:3000> dans le navigateur Windows. Même principe pour GLPI : `portNumber=8081,localPortNumber=8081` puis <http://localhost:8081>.

## 5. Lancer une commande sur le serveur sans ouvrir de session

Les commandes à plusieurs mots passent par un fichier JSON (évite les problèmes de guillemets) :
```bash
cat > /tmp/cmd.json <<'EOF'
{"commands":["cd /opt/passerelle && docker compose ps","df -h /srv/data"]}
EOF
CID=$(aws ssm send-command --region $R --instance-ids $I --document-name AWS-RunShellScript \
      --parameters file:///tmp/cmd.json --query Command.CommandId --output text)
sleep 6
aws ssm get-command-invocation --region $R --command-id $CID --instance-id $I \
    --query "[Status,StandardOutputContent,StandardErrorContent]" --output text
```
Remplacez simplement les lignes de `commands`. `Status` vaut `Success`, `InProgress` (relancer la dernière commande) ou `Failed`.

**Plus simple : le script du dépôt** `tools/ssm-executer.sh` fait tout cela (JSON, attente, résultat) à partir de commandes saisies telles quelles :
```bash
cd ~/caisse-demo
bash tools/ssm-executer.sh - <<'EOF'
cd /opt/passerelle && docker compose ps --format '{{.Service}}: {{.Status}}'
df -h /srv/data
EOF
```
Les commandes tournent en root avec `sh` (pas `bash`) : pas de `${var:0:4}` ni de `[[ ]]`.

## 6. Mettre à jour la configuration (règles d'alerte, tableau de bord Grafana, Caddy…)

```bash
cd ~/caisse-demo/supervision/terraform
terraform apply                                   # 1) envoie les fichiers modifiés dans S3
aws ssm send-command --region $R --instance-ids $I --document-name AWS-RunShellScript \
    --parameters commands=passerelle-sync --query Command.CommandId --output text     # 2) le serveur se resynchronise
```
`passerelle-sync` télécharge la configuration, **relance seulement** Prometheus, Alertmanager, Loki ou Grafana si *leurs* fichiers ont changé
(un simple nouveau tableau de bord n'impose aucune relance), puis recharge Caddy. Les identifiants des enseignes ne sont jamais écrasés.
Pour lire son résultat, utilisez l'`id` affiché avec la commande `get-command-invocation` du § 5, ou faites la même chose dans une session : `sudo passerelle-sync`.

Vérifier ensuite (dans une session, ou avec le § 5) :
```bash
cd /opt/passerelle && sudo docker compose ps
md5sum /opt/passerelle/grafana/dashboards/parc-caisses.json      # doit être celui du dossier ~/caisse-demo/supervision/serveur/grafana/dashboards/
ls /opt/passerelle/prometheus/rules/                             # caisses.yml et enregistrements.yml
```
Dans Grafana, rechargez la page avec **Ctrl+Maj+R**.

Remplacer le script `passerelle-sync` lui-même (installé une fois au démarrage, il n'est pas mis à jour tout seul) :
```bash
BUCKET=$(terraform output -raw bucket_configuration)
# dans la session sur le serveur :
sudo aws s3 cp s3://$BUCKET/outils/passerelle-sync.sh /usr/local/sbin/passerelle-sync --region eu-west-3
sudo chmod 750 /usr/local/sbin/passerelle-sync
```

## 6b. Arrêter / relancer Grafana (sans toucher au reste)

```bash
cd ~/caisse-demo && bash tools/ssm-executer.sh - <<'EOF'
cd /opt/passerelle && docker compose stop grafana
EOF
# relancer : remplacer « stop » par « start »
```
Prometheus continue de stocker et Alertmanager d'alerter pendant l'arrêt. **Attention : `passerelle-sync` relance Grafana** (il exécute `docker compose up -d`) : l'arrêter de nouveau après une synchronisation si besoin. **Avant une démo, le relancer.**

## 7. Lire les secrets (Parameter Store)

```bash
aws ssm get-parameter --region $R --name /passerelle-caisses/grafana_admin_password --with-decryption --query Parameter.Value --output text
aws ssm get-parameter --region $R --name /passerelle-caisses/glpi_admin_password   --with-decryption --query Parameter.Value --output text   # si GLPI activé
aws ssm get-parameter --region $R --name /passerelle-caisses/rustdesk_secret       --with-decryption --query Parameter.Value --output text   # si RustDesk activé
aws ssm describe-parameters --region $R --query "Parameters[].Name" --output text                                                              # liste des noms
```
(`passerelle-caisses` est la valeur de `name` dans `terraform.tfvars`.) Ces commandes affichent un secret : ne les lancez pas devant un client.

## 7b. GLPI (inventaire)

Procédure complète : `GUIDE-GLPI.md`. Les commandes AWS qui reviennent :
```bash
# activer GLPI : dans terraform.tfvars  activer_glpi = true, domaine_glpi = "glpi.exemple.fr", instance_type = "t3.large"
cd ~/caisse-demo/supervision/terraform && terraform plan && terraform apply     # crée les 3 mots de passe, passe en t3.large (le serveur redémarre quelques minutes)

# SERVEUR DÉJÀ EXISTANT : Terraform ne réécrit pas /etc/passerelle.env, il faut y activer GLPI à la main, puis synchroniser
cd ~/caisse-demo && bash tools/ssm-executer.sh - <<'EOF'
sed -i "s/^GLPI_ENABLED=.*/GLPI_ENABLED='1'/; s/^GLPI_DOMAIN=.*/GLPI_DOMAIN='glpi.exemple.fr'/" /etc/passerelle.env
/usr/local/sbin/passerelle-sync
cd /opt/passerelle && bash scripts/glpi-initialiser.sh
EOF
```
Ensuite, **DNS : enregistrement A `glpi` vers l'IP du serveur, « DNS only » (nuage gris)**. Avec le proxy Cloudflare (nuage orange), le certificat n'est pas obtenu et la réponse est 525.
Si Caddy a échoué avant que le DNS existe, il attend de plus en plus longtemps entre deux essais : le relancer.
```bash
cd ~/caisse-demo && bash tools/ssm-executer.sh - <<'EOF'
cd /opt/passerelle && docker compose restart caddy
EOF
curl -s -o /dev/null -w '%{http_code}
' https://glpi.exemple.fr/        # 404 = bon (seul le chemin d'inventaire est ouvert)
```
Sur la caisse : `sudo glpi-agent --force` lance l'inventaire tout de suite (sinon l'agent attend son délai). Mot de passe administrateur de GLPI : § 7 (`glpi_admin_password`), login `glpi`.

## 8. Enseignes (à lancer dans une session sur le serveur)

```bash
cd /opt/passerelle
sudo bash scripts/enseigne-ajouter.sh nomclient      # minuscules, sans espace ; affiche la commande d'enrôlement à lancer sur les caisses
sudo bash scripts/enseigne-lister.sh
sudo bash scripts/enseigne-revoquer.sh nomclient
```

## 9. Sauvegardes

```bash
BUCKET=$(terraform output -raw bucket_sauvegardes)
aws s3 ls s3://$BUCKET/passerelle/ --region $R                     # archives quotidiennes (03:30 UTC)
aws s3 cp s3://$BUCKET/passerelle/<fichier>.tar.gz . --region $R    # télécharger une archive
aws ec2 describe-snapshots --region $R --owner-ids self --query "Snapshots[].[SnapshotId,StartTime,State]" --output table   # instantanés du disque de données
```
Forcer une sauvegarde maintenant (dans une session) : `sudo passerelle-sauvegarde`.

## 10. Économiser en dehors des démos, et surveiller les coûts

```bash
aws ec2 stop-instances  --region $R --instance-ids $I        # arrête le serveur (les disques et l'IP fixe restent facturés, l'instance non)
aws ec2 start-instances --region $R --instance-ids $I        # le rallume (même IP, tout repart tout seul)
aws budgets describe-budgets --account-id $(aws sts get-caller-identity --query Account --output text) --query "Budgets[].[BudgetName,BudgetLimit.Amount]" --output table
```
Les caisses gardent leurs mesures en file pendant l'arrêt et les rejouent au retour (dans la limite de ~30 minutes pour les métriques).
Tout supprimer à la fin de l'essai (**efface aussi le disque de données et les sauvegardes**) : `terraform destroy`.

## 11. Dépannage

| Message | Cause | Remède |
|---|---|---|
| `argument --target: expected one argument` | `$I` vide : vous n'êtes pas dans `~/caisse-demo/supervision/terraform` | `cd` puis § 0 |
| `SessionManagerPlugin is not found` | plugin Session Manager absent | l'installer (doc AWS « session-manager-plugin »), tester `session-manager-plugin` |
| `TargetNotConnected` | serveur éteint ou agent SSM pas encore démarré | § 3 (`PingStatus` doit valoir `Online`), attendre 2 minutes après un démarrage |
| `AccessDenied ... ssm:GetParameter` / `ssm:StartSession` | votre utilisateur IAM n'a pas le droit | ajouter la politique correspondante à l'utilisateur |
| `Error acquiring the state lock` | un autre `terraform` tourne ou a été interrompu | attendre ; sinon `terraform force-unlock <ID affiché>` |
| `terraform plan` veut tout créer | `terraform.tfstate` absent du dossier | arrêter, retrouver le fichier (ne pas faire `apply`) |
| GLPI : « votre mot de passe a expiré » à la connexion | empreinte calculée avec un autre format que celui de PHP (`$2a$` au lieu de `$2y$`) | corrigé dans `glpi-initialiser.sh` (empreinte calculée par PHP) ; relancer le script sur un serveur déjà initialisé avant la correction |
| GLPI ou Grafana : réponse 525 de Cloudflare | l'enregistrement DNS est en nuage orange (proxy) | passer en « DNS only », attendre le cache DNS (la caisse : `sudo resolvectl flush-caches`) |
| `Bad substitution` dans une commande SSM | SSM exécute avec `sh`, pas `bash` | éviter `${x:0:4}` et `${#x}` ; ou utiliser `cut` et `wc -c` |
| Grafana inchangé après une mise à jour | `terraform apply` ou `passerelle-sync` non lancé, ou page non rechargée | § 6 puis Ctrl+Maj+R |
