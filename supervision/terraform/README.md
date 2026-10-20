# Déploiement de la passerelle sur AWS avec Terraform

> Aide-mémoire de **toutes les commandes AWS** (session, tunnel Grafana, mise à jour, secrets, sauvegardes, coûts, dépannage) : [`../guides/AWS-COMMANDES.md`](../guides/AWS-COMMANDES.md).

Crée, dans votre compte AWS, la passerelle décrite dans `../guides/GUIDE-SERVEUR.md` (Caddy, Prometheus, Loki, Alertmanager, Grafana).

```
Internet ──443──► Elastic IP ──► EC2 Ubuntu 24.04 (t3.medium par défaut, essai) ── Docker : Caddy → Prometheus / Loki ; Grafana ; Alertmanager
                                   │  disque de données chiffré (instantané quotidien DLM, 7 jours)
administrateurs ──► AWS Systems Manager (pas de SSH, pas de VPN)       S3 : configuration · sauvegardes (chiffrées, versionnées)
```

## Ce que Terraform crée

| Fichier | Ressources |
|---|---|
| `network.tf` | VPC + sous-réseau public, groupe de sécurité (**80, 443 seulement ; aucun SSH**), Elastic IP, enregistrements Route 53 (optionnel) |
| `instance.tf` | EC2 Ubuntu 24.04 (IMDSv2 obligatoire, disque racine chiffré, protection contre la suppression) ; démarrage par `files/bootstrap.sh` |
| `storage.tf` | disque de données chiffré + instantanés quotidiens (DLM) ; bucket S3 *configuration* ; bucket S3 *sauvegardes* (chiffré, versionné, privé, TLS obligatoire, rotation) |
| `iam.tf` | rôle du serveur : Systems Manager, lecture de la configuration, écriture des sauvegardes, lecture de **ses** secrets seulement |
| `secrets.tf` | mot de passe administrateur Grafana (généré) et secret SSO dans Parameter Store (chiffrés) |
| `monitoring.tf` | relance automatique du serveur si AWS détecte une panne matérielle ; e-mail optionnel |
| `files/` | scripts embarqués : `bootstrap.sh` (1er démarrage), `passerelle-sync` (config → serveur), `passerelle-sauvegarde` (cron 03:30 UTC → S3) |

Le dossier `../serveur` (fichiers Caddy, Prometheus, règles d'alerte, Grafana, scripts) est envoyé tel quel dans S3 puis récupéré par le serveur : **une seule source de vérité**.

## Option : inventaire GLPI
> **Serveur déjà créé ?** `terraform apply` génère les mots de passe et change la taille de l'instance, mais ne réécrit pas `/etc/passerelle.env` (écrit au premier démarrage) : y activer GLPI à la main, voir `../guides/GUIDE-GLPI.md` § 1 et `../guides/AWS-COMMANDES.md` § 7b.

`activer_glpi = true` + `domaine_glpi = "glpi.exemple.fr"` (et `instance_type = "t3.large"`) ajoutent GLPI et sa base MariaDB sur le même serveur : mots de passe générés et conservés chiffrés, enregistrement DNS (si Route 53), sauvegarde de la base. Interface de GLPI jamais publique (tunnel, port 8081). Procédure complète et limites : `../guides/GUIDE-GLPI.md`.

## Option : prise en main à distance (RustDesk)
`activer_rustdesk = true` + `domaine_rustdesk = "rustdesk.exemple.fr"` ajoutent le serveur RustDesk (annuaire + relais) : ouvre 21115-21117/tcp et 21116/udp
**sur le serveur** (rien chez le client), génère le secret d'où sont dérivés les mots de passe des caisses, sauvegarde la clé du serveur. Voir `../guides/GUIDE-RUSTDESK.md`.

## Prérequis
- Terraform ≥ 1.6, AWS CLI v2 configuré (`aws sts get-caller-identity` répond), et le [plugin Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html) pour se connecter au serveur.
- Un nom de domaine dont vous pouvez créer des enregistrements A (Route 53 ou ailleurs).
- Un compte avec les droits de créer VPC, EC2, EBS, S3, IAM, SSM, CloudWatch, DLM, Route 53.

## Déploiement

```bash
cd supervision/terraform
cp terraform.tfvars.example terraform.tfvars      # adapter : domaines, e-mail, mode Grafana
terraform init
terraform plan                                    # À LIRE avant d'appliquer
terraform apply
```

1. **DNS.** Si `route53_zone_id` est vide, créez les enregistrements A donnés par `terraform output enregistrements_dns` chez votre registrar. Faites-le dès que l'IP est connue : Caddy obtient ses certificats Let's Encrypt au démarrage, et réessaie tant que le DNS n'est pas prêt.
2. **Attendez ~5 à 8 minutes** (installation de Docker, téléchargement des images). Suivre l'avancement :
   ```bash
   $(terraform output -raw ouvrir_une_session)           # session sur le serveur
   sudo tail -f /var/log/passerelle-bootstrap.log        # attendre « bootstrap terminé »
   cd /opt/passerelle && sudo docker compose ps
   curl https://metrics.exemple.fr/healthz               # → ok
   ```
3. **Créer l'identifiant d'une enseigne** (dans la session) :
   ```bash
   cd /opt/passerelle && sudo bash scripts/enseigne-ajouter.sh nomclient
   ```
   Il affiche la commande d'enrôlement à lancer sur chaque caisse. Le mot de passe n'est conservé que sous forme d'empreinte, dans `caddy/enseignes.caddy` (sauvegardé dans S3 ; **jamais écrasé** par une synchronisation).
4. **Grafana.**
   - Mode `prive` : `$(terraform output -raw tunnel_grafana)` puis <http://localhost:3000> ; compte `admin`, mot de passe : `$(terraform output -raw mot_de_passe_grafana)`.
   - Mode `sso` : <https://grafana.exemple.fr>. Déclarez chez Google l'URI de redirection `https://<domaine_grafana>/login/google` et imposez la validation en deux étapes dans la console Google (voir `../guides/GUIDE-SERVEUR.md` § 8).

## Mettre à jour la configuration (règles d'alerte, tableau de bord, Caddyfile…)

```bash
# modifier un fichier de ../serveur, puis :
terraform apply                                   # republie les fichiers modifiés dans S3
$(terraform output -raw appliquer_la_configuration)    # le serveur se resynchronise et relance ce qui a changé
```
Modifier `files/bootstrap.sh` n'a **pas** d'effet sur un serveur existant (et ne le détruit pas : `user_data` est ignoré après création) ; pour changer l'installation de base, intervenez dans une session ou recréez le serveur depuis une sauvegarde.

## Sauvegardes et restauration
- Tous les jours, 03:30 UTC : archive locale (`/srv/data/sauvegardes`, 3 copies) puis copie dans `s3://<bucket_sauvegardes>/passerelle/` (conservation `retention_sauvegardes_jours`, 30 par défaut). En plus : instantané EBS quotidien du disque de données (7 jours).
- **Une sauvegarde S3 contient `.env` et les empreintes des enseignes** : le bucket est privé et chiffré ; ne donnez l'accès qu'aux administrateurs.
- Restauration : recréer l'infrastructure (`terraform apply`), puis suivre la restauration de `../guides/GUIDE-SERVEUR.md` § 10 en téléchargeant l'archive avec `aws s3 cp`. Ou, pour un disque abîmé, créer un volume depuis un instantané EBS et l'attacher à la place du disque de données.

## Coûts approximatifs (région Paris, estimations non vérifiées sur la grille AWS)
**Réglages par défaut = essai** (`t3.medium`, 30 Go, `environnement_test = true`) : environ 35-40 €/mois si le serveur reste allumé en continu (instance ≈ 35 €, disques ≈ 5 €, Elastic IP ≈ 3 €), soit moins de 2 €/jour ; S3 et Parameter Store sont négligeables. À comparer à votre crédit : le programme gratuit AWS donne des crédits limités dans le temps, vérifiez le solde dans *Billing*.
Pour la production avec des milliers de caisses : `instance_type = "c6i.xlarge"` (≈ 150 €/mois), `data_volume_gb` ≥ 120, `environnement_test = false`.
`budget_mensuel_usd = 100` + `alert_email` crée un budget AWS qui vous écrit à 50 % et 90 % du réel et à 100 % de la prévision.

## Détruire (essai)
`terraform destroy` supprime tout d'un coup tant que `environnement_test = true` (buckets vidés automatiquement, pas de protection de l'instance). **Cela supprime aussi le disque de données et les sauvegardes S3.** En production (`environnement_test = false`), il faut d'abord repasser à `true` et faire un `apply`.

## Sécurité : ce qu'il faut savoir
- **L'état Terraform contient des secrets** (mot de passe Grafana généré, secret SSO). Ne le publiez pas ; pour une équipe, configurez le backend S3 chiffré commenté dans `versions.tf`.
- Aucun port d'administration n'est ouvert : l'accès passe par Systems Manager, authentifié et journalisé par AWS IAM. Donnez le droit `ssm:StartSession` aux seuls administrateurs.
- Pas de filtrage par adresse IP des caisses (box ADSL partagées, adresses changeantes) : la protection est l'identifiant par enseigne. Les limites (étiquettes déclarées par la caisse, mot de passe partagé par enseigne) sont décrites dans le guide.
- Le serveur est en un seul exemplaire (une zone). Une panne matérielle est relancée automatiquement ; une panne de zone entière demande de recréer le serveur depuis les sauvegardes. Les caisses réessaient d'elles-mêmes et gardent leurs données en file d'attente.

## Ce qui est vérifié, et ce qui ne l'est pas
Vérifié hors ligne (`bash supervision/tests/validate-terraform.sh`) : formatage, `terraform init` et `validate` avec le vrai fournisseur AWS, syntaxe des scripts embarqués, taille du `user_data` (3,7 Ko / 16 Ko), absence de secret en dur et de port SSH. **Non vérifié** : `plan` et `apply` (aucun compte AWS ici), donc ni les quotas, ni les droits, ni l'exécution réelle de `bootstrap.sh` et `passerelle-sync` sur une instance. Premier essai : un compte de test, `instance_type = "t3.medium"`, puis `terraform destroy`.
