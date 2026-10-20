# Prise en main à distance des caisses avec RustDesk

Voir et piloter l'écran d'une caisse depuis un poste d'administration, **sans SSH, sans VPN, sans ouvrir de port chez le client** : la caisse se connecte
*en sortie* à votre serveur RustDesk (sur la passerelle AWS), qui met en relation l'administrateur et la caisse.

```
 Administrateur (RustDesk, n'importe où) ─────┐
                                               ▼
                                   Serveur RustDesk (passerelle AWS)  ◄──── caisse (en sortie, comme pour la supervision)
                                   annuaire des ID + relais                    client RustDesk + storepos-kiosk-remote
```

## Ce qui est vérifié, et ce qui ne l'est pas

| Vérifié avec les vrais programmes | Pas vérifié |
|---|---|
| Le **vrai serveur** RustDesk 1.1.16 (`hbbs`, `hbbr`) démarre avec `-k _` : clé créée, ports ouverts, la clé est exigée | **Le client RustDesk 1.5.0 lui-même** (il demande un écran et les droits root) : aucune vraie session de prise en main n'a été ouverte |
| Inscription d'une caisse : configuration (serveur, relais, clé), droits 0600, ID, mot de passe ; valeurs dangereuses refusées | Le format exact du fichier `RustDesk2.toml` et son emplacement pour le service (`/root/.config/rustdesk/`) : écrits d'après la documentation et des exemples, à confirmer |
| Le mot de passe posé sur la caisse est **identique** à celui que recalcule le serveur (comparé à une implémentation HMAC de référence) | Le partage de l'écran de la **session kiosque** (X11, connexion automatique) en mode sans surveillance |
| Diagnostic contre le vrai serveur : annuaire et relais joignables, serveur arrêté détecté, clé absente signalée | La connexion directe en UDP, les débits, le comportement sur ADSL |
| Compose (image versionnée, `-k _` sur `hbbs` ET `hbbr`), sauvegarde de la clé privée, groupe de sécurité (21115, 21116 tcp+udp, 21117 seulement), `terraform validate` | Le déploiement AWS avec RustDesk activé |

**Première installation : une seule caisse de test**, avec une vraie session à ouvrir avant tout déploiement.

## 1. Activer le serveur

### Avec Terraform (nouveau déploiement)
Dans `terraform/terraform.tfvars` :
```hcl
activer_rustdesk = true
domaine_rustdesk = "rustdesk.teachmemore.fr"
```
Puis `terraform apply`. Ajoutez l'enregistrement DNS **A** `rustdesk` vers l'IP du serveur (Cloudflare : nuage **gris**, « DNS only » : RustDesk n'est pas du web, le proxy orange ne le transporte pas).
Terraform ouvre sur le **serveur** (jamais chez le client) : `21115/tcp`, `21116/tcp`, `21116/udp`, `21117/tcp`. Les ports du client web (`21118`, `21119`) restent **fermés**.
Il génère aussi le secret d'où sont dérivés les mots de passe des caisses (Parameter Store, chiffré).

### Serveur déjà déployé, sans état Terraform (votre cas actuel)
```bash
# 1. ports du groupe de sécurité (remplacer sg-XXXX par l'identifiant du groupe « passerelle-caisses-… »)
for r in "tcp 21115" "tcp 21116" "udp 21116" "tcp 21117"; do set -- $r
  aws ec2 authorize-security-group-ingress --region eu-west-3 --group-id sg-XXXX --protocol $1 --port $2 --cidr 0.0.0.0/0; done
# 2. secret des mots de passe (à noter dans votre gestionnaire de mots de passe)
aws ssm put-parameter --region eu-west-3 --name /passerelle-caisses/rustdesk_secret --type SecureString --value "$(openssl rand -hex 24)"
# 3. fichiers à jour dans S3
cd ~/caisse-demo/supervision/serveur && aws s3 sync . s3://$(cd ../terraform && terraform output -raw bucket_configuration)/serveur/ --exclude "cloudflare/*"
```
Puis, dans la session sur le serveur :
```bash
printf "RUSTDESK_ENABLED='1'\nRUSTDESK_DOMAIN='rustdesk.teachmemore.fr'\n" | sudo tee -a /etc/passerelle.env
sudo passerelle-sync
sudo docker compose -f /opt/passerelle/docker-compose.yml ps     # hbbs et hbbr : Up
```
> Le fichier `passerelle-sync` du serveur existant doit connaître RustDesk : il vient de S3 (`outils/`), que seul `terraform apply` met à jour. Sans état Terraform, copiez-le à la main : `aws s3 cp supervision/terraform/files/passerelle-sync.sh s3://$(cd ../terraform && terraform output -raw bucket_configuration)/outils/passerelle-sync.sh` puis, dans la session, `aws s3 cp s3://<bucket_configuration>/outils/passerelle-sync.sh /usr/local/sbin/passerelle-sync`.

## 2. Récupérer la clé du serveur

Dans la session sur le serveur :
```bash
cd /opt/passerelle && sudo bash scripts/rustdesk-cle.sh
```
Le script affiche la **clé publique** (44 caractères) et la commande d'inscription prête à copier. La clé publique n'est pas secrète, mais **ne la changez jamais** : toutes les caisses seraient à réinscrire. Sa clé privée est dans le volume `rustdesk_data`, **inclus dans les sauvegardes** (`scripts/sauvegarder.sh`).

Le secret des mots de passe se lit avec :
```bash
aws ssm get-parameter --region eu-west-3 --name /passerelle-caisses/rustdesk_secret --with-decryption --query Parameter.Value --output text
```

## 3. Installer sur une caisse

Le client officiel n'est pas dans les dépôts Ubuntu : téléchargez-le (version 1.5.0 testée) puis installez-le **avec** notre paquet, en une seule commande :
```bash
wget https://github.com/rustdesk/rustdesk/releases/download/1.5.0/rustdesk-1.5.0-x86_64.deb
sha256sum rustdesk-1.5.0-x86_64.deb      # attendu : 4e2b9701d25a7ef373f3d09bda584a37d6902f97a6ab15f2e86d4a5783e2a0ec
sudo apt install -y ./rustdesk-1.5.0-x86_64.deb ./storepos-kiosk-remote_1.0.0_all.deb
```
Le client tire une vingtaine de bibliothèques graphiques (GTK, GStreamer…), donc une centaine de Mo sur la caisse. Notre paquet **arrête le service tant que la caisse n'est pas inscrite** : sans cela, RustDesk se connecterait aux serveurs publics de RustDesk.

Déposez le secret dans un fichier protégé, inscrivez, puis **supprimez le fichier** :
```bash
sudo bash -c 'umask 077; read -rsp "Secret : " s; printf "%s\n" "$s" > /root/secret-rustdesk'     # saisie masquée : rien dans l'historique
sudo storepos-kiosk-remote-enroll --server rustdesk.teachmemore.fr --key 'CLE_PUBLIQUE=' --secret-file /root/secret-rustdesk
sudo shred -u /root/secret-rustdesk
sudo storepos-kiosk-remote-check
```
L'inscription affiche l'**ID RustDesk** de la caisse (aussi visible dans le bouton « i », ligne « ID RustDesk », avec `storepos-kiosk` 1.3.2). Le mot de passe de cette caisse n'est écrit nulle part : il se **recalcule** sur le serveur.

## 4. Se connecter à une caisse

1. Sur le poste de l'administrateur, installez RustDesk (Windows, Linux ou macOS) et renseignez **Paramètres › Réseau › Serveur ID/relais** : `rustdesk.teachmemore.fr`, et la **clé** du serveur (sans elle, la connexion échoue).
2. Calculez le mot de passe de la caisse (dans la session sur le serveur) :
   ```bash
   cd /opt/passerelle && sudo bash scripts/rustdesk-mdp.sh 123456789      # l'ID de la caisse
   ```
3. Dans RustDesk, saisissez l'ID, puis ce mot de passe.

**Voir le bureau Linux au lieu de l'application de caisse** : la caisse affiche l'application en plein écran. Pour intervenir sur le système, arrêtez l'application depuis la session distante ou en SSH, puis relancez-la :
```bash
sudo storepos-kiosk-maintenance on       # arrête l'application : le bureau (sans menu) apparaît
sudo storepos-kiosk-maintenance off      # relance l'application
```
Un terminal n'est pas disponible sur ce bureau (session verrouillée) : pour des commandes, utilisez les sessions du serveur ou un accès dédié.

## 5. Sécurité

- **Un mot de passe différent par caisse** : HMAC-SHA256 de l'ID et du secret de l'entreprise (24 caractères). Rien n'est stocké en clair ; la compromission d'un poste n'expose que son mot de passe.
- **Le secret est la clé de voûte** : celui qui le détient peut ouvrir *toutes* les caisses. Gardez-le dans un gestionnaire de mots de passe, pas dans un fichier partagé ; ne le tapez jamais sur la ligne de commande (le fichier `--secret-file` existe pour cela, à détruire après usage). Le changer invalide tous les mots de passe : il faut réinscrire les caisses.
- **La clé du serveur** (`-k _`) : sans elle, ni une caisse ni un administrateur ne peut s'enregistrer ni utiliser le relais. Cela réduit, sans l'annuler, l'exposition d'un serveur ouvert sur Internet.
- **Pas de journal ni de droits par administrateur** dans la version libre : quiconque a la clé et le secret peut se connecter, sans trace côté serveur. La console web, les droits par personne et l'audit sont dans l'offre payante de RustDesk. Si vous en avez besoin, dites-le : c'est un critère de choix face à TeamViewer.
- **Prévenir le magasin** avant d'intervenir : un écran de caisse est visible des clients. Je n'ai pas vérifié ce que RustDesk affiche sur la caisse pendant une session (un indicateur ou une bulle peut apparaître).
- **Coût de transfert** : les sessions passent par le relais quand la connexion directe n'est pas possible ; le trafic sortant d'AWS est facturé au Go (quelques dizaines à centaines de Mo par heure de session, à mesurer).

## 6. Dépannage

| Symptôme | Piste |
|---|---|
| `storepos-kiosk-remote-check` : annuaire ou relais injoignable | DNS (`rustdesk.…` doit pointer vers l'IP du serveur, **nuage gris**), ports du groupe de sécurité, `docker compose ps` sur le serveur |
| « clé du serveur absente » | relancer l'inscription ; ne jamais laisser une caisse sans clé |
| Le client affiche « ID introuvable » / refuse le mot de passe | recalculer avec `rustdesk-mdp.sh <ID>` ; l'ID a pu changer après une réinstallation : réinscrire la caisse |
| L'écran distant est noir | la session graphique du kiosque n'est pas détectée par le service : à me signaler avec `journalctl -u rustdesk -n 50` |
| Connexion lente | passe par le relais (UDP 21116 bloqué) ; normal sur certaines box |
