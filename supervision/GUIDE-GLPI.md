# Inventaire du parc avec GLPI — installation de A à Z

GLPI tient l'**inventaire** des caisses : matériel, système, logiciels installés, rattachés à une enseigne et à un magasin. C'est complémentaire de Grafana, qui sert à la **surveillance** (en ligne ou non, alertes).

```
 Caisse (derrière la box du client)                                   Passerelle AWS
 ┌──────────────────────────────────┐                                 ┌─────────────────────────────────┐
 │ agent GLPI (aucun port ouvert)   │ ── inventaire, HTTPS, identifiant ─► Caddy ── seulement l'envoi ──► GLPI + MariaDB
 │ storepos-kiosk-glpi              │       de l'enseigne                │  (le reste : 404)             │
 └──────────────────────────────────┘                                 │ interface web : tunnel seulement│
                                                                      └─────────────────────────────────┘
```

Principes (les mêmes que pour la supervision) : **rien n'est ouvert chez le client** (l'interface web de l'agent, port 62354, est désactivée) ; les caisses **envoient**, rien ne les contacte ; chaque enseigne a **son identifiant**, le même que pour la supervision ; l'interface de GLPI n'est **jamais publique**.

## Ce qui est vérifié, et ce qui ne l'est pas

| Vérifié avec les vrais programmes | Pas vérifié |
|---|---|
| Le **vrai agent GLPI 1.21** collecte un inventaire complet et l'envoie à travers le **vrai Caddy** : dialogue 401 puis 200, chemin `/front/inventory.php`, enseigne identifiée dans le journal, mot de passe absent du journal, en-tête d'authentification retiré avant GLPI | **GLPI lui-même** : ni son installation automatique, ni la réception réelle de l'inventaire, ni l'apparition de la machine dans l'interface. PHP, MariaDB et Docker n'étaient pas disponibles ici ; la réponse de GLPI est simulée |
| Un mauvais mot de passe : rien n'atteint GLPI. Interface web, API, installation, plugins : 404 depuis Internet | Le **SQL** de `glpi-initialiser.sh` (changement du mot de passe, désactivation des comptes par défaut, activation de l'inventaire) : envoyé tel quel, jamais exécuté sur une vraie base GLPI |
| Paquet `storepos-kiosk-glpi` : inscription, refus des valeurs dangereuses, diagnostic (réglages, droits 0600, 401/404/joint) | L'image `glpi/glpi:11.0.11` et `mariadb:10.11` au démarrage, la sauvegarde/restauration sur un vrai Docker |
| Sauvegarde (export SQL + fichiers, échec signalé), initialisation (logique), fichiers `docker-compose` (ports, images versionnées) et Terraform (`validate`) | Le déploiement AWS réel avec GLPI activé |

**Première installation : sur un compte de test**, et gardez la page de GLPI ouverte pour vérifier les points de la section 6.

## 1. Activer GLPI dans Terraform

Dans `terraform/terraform.tfvars` :
```hcl
activer_glpi = true
domaine_glpi = "glpi.teachmemore.fr"
instance_type = "t3.large"      # 8 Go : GLPI + MariaDB s'ajoutent à Grafana, Prometheus et Loki (t3.medium est trop juste)
```
Puis `terraform plan` et `terraform apply`. Terraform génère et conserve chiffrés (Parameter Store) trois mots de passe : base GLPI, root de la base, administrateur `glpi`. Ils ne sont jamais saisis ni affichés.

**DNS** : ajoutez un enregistrement **A** `glpi` vers l'IP du serveur (Cloudflare : nuage **gris**, « DNS only »), comme pour `metrics` et `logs`.

> Serveur déjà déployé sans GLPI et dont l'état Terraform est perdu : ce chemin ne s'applique pas. Recréez le déploiement (voir `terraform/nettoyer-sans-etat.sh`), puis refaites un `apply` avec GLPI activé.

## 2. Premier démarrage et initialisation

Le serveur télécharge `glpi/glpi:11.0.11` et `mariadb:10.11` (quelques minutes). L'image installe GLPI automatiquement.

Dans la session sur le serveur (`terraform output glpi` donne les commandes) :
```bash
cd /opt/passerelle
sudo docker compose ps                       # glpi et glpi-db : « Up », glpi-db « healthy »
sudo bash scripts/glpi-initialiser.sh
```
`glpi-initialiser.sh` :
- remplace le mot de passe du compte `glpi` (l'installation crée des comptes aux mots de passe **connus** : `glpi/glpi`, `tech/tech`, `normal/normal`, `post-only/postonly`) ;
- **désactive** `tech`, `normal` et `post-only` ;
- active l'inventaire natif.

Il affiche un résumé de la base. Ne passez pas à la suite sans l'avoir lu.

## 3. Ouvrir GLPI (tunnel, jamais public)

```bash
aws ssm start-session --region eu-west-3 --target i-XXXXXXXX \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8081"],"localPortNumber":["8081"]}'
```
Puis <http://localhost:8081>. Compte : `glpi`. Mot de passe :
```bash
aws ssm get-parameter --region eu-west-3 --name /passerelle-caisses/glpi_admin_password --with-decryption --query Parameter.Value --output text
```

## 4. Vérifier dans GLPI (à faire une fois)

1. **Administration › Inventaire** : « Activer l'inventaire » doit être **coché**. Sinon, cochez-le et enregistrez (le script le fait par la base, mais ce point est l'un de ceux que je n'ai pu vérifier).
2. **Administration › Utilisateurs** : seul `glpi` est actif. Les mots de passe par défaut ne marchent plus.
3. Créez, si vous le souhaitez, un compte nominatif par administrateur, plutôt que de partager `glpi`.

## 5. Installer l'inventaire sur une caisse

**a. L'agent officiel GLPI** (version 1.21 testée) — sur la caisse :
```bash
wget https://github.com/glpi-project/glpi-agent/releases/download/1.21/glpi-agent_1.21-1_all.deb
sha256sum glpi-agent_1.21-1_all.deb      # attendu : a17c94e6d05d443df2a3468b8174f33479d3d7949d962f719b3a4f9fab89f9c4
```
**b. Notre paquet** `storepos-kiosk-glpi_1.0.0_all.deb` (dans `glpi-agent/dist/`). Installez les deux ensemble pour qu'apt résolve la dépendance :
```bash
sudo apt-get install -y ./glpi-agent_1.21-1_all.deb ./storepos-kiosk-glpi_1.0.0_all.deb
```
Le paquet désactive l'interface web de l'agent (`no-httpd = 1` : **aucun port ouvert**).

**c. Inscrire la caisse.** Si elle est déjà inscrite à la supervision, enseigne, magasin, poste et identifiants sont repris :
```bash
sudo storepos-kiosk-glpi-enroll --server-url https://glpi.teachmemore.fr/front/inventory.php
sudo storepos-kiosk-glpi-check
```
Sinon, ajoutez `--enseigne … --magasin … --poste … --user … --password …`. L'enroll lance un premier inventaire (`--no-run` pour l'éviter). L'identifiant est celui de l'enseigne (`enseigne-ajouter.sh`, voir `serveur/GUIDE-SERVEUR.md`).

**d. Voir la caisse dans GLPI** : **Parc › Ordinateurs** (premier envoi : quelques minutes). L'étiquette `enseigne/magasin/poste` est visible dans l'inventaire de la machine.

## 6. Points à contrôler à la première installation

- **La machine apparaît dans Parc › Ordinateurs** après `storepos-kiosk-glpi-enroll`. Sinon : `storepos-kiosk-glpi-check` (réseau, identifiants, adresse) puis, sur le serveur, `sudo docker compose logs glpi | tail` et `sudo docker compose logs caddy | grep inventory`.
- **L'étiquette** : dans GLPI, *Administration › Règles › Règles d'affectation d'entité* permet de ranger chaque machine dans l'entité de son enseigne en lisant l'étiquette (`demo/LYON-01/1` → entité `demo`). Je n'ai pas vérifié l'écran des règles ; la création d'entités et de règles est manuelle.
- **Réponse de GLPI à la route `/`** : l'agent poste sur l'adresse que vous lui donnez. La procédure utilise `/front/inventory.php`, explicite et acceptée par Caddy. La racine `/` est aussi autorisée en envoi (protocole natif de l'agent).
- **Taille des envois** : Caddy accepte jusqu'à 32 Mo par inventaire (large pour une caisse).

## 7. Sécurité

- Les comptes par défaut de GLPI sont remplacés/désactivés par `glpi-initialiser.sh` : **à ne pas oublier**, ils sont connus de tous.
- L'interface web de GLPI n'est joignable que par le tunnel ; depuis Internet, seul l'envoi d'inventaire passe (test automatisé : login, API, installation, plugins → 404).
- Les identifiants des enseignes sont partagés par toutes les caisses d'une enseigne et par la supervision : révoquer une enseigne (`enseigne-revoquer.sh`) coupe aussi son inventaire.
- Le mot de passe de l'enseigne est dans `/etc/glpi-agent/conf.d/50-storepos-kiosk-serveur.cfg` (droits 0600). Le paquet le supprime à la désinstallation.
- Mises à jour de GLPI : changez le numéro de version dans `docker-compose.glpi.yml` après une sauvegarde ; l'image met à jour la base au démarrage. Les images sont reconstruites régulièrement pour les correctifs de sécurité.

## 8. Sauvegarde et restauration

`scripts/sauvegarder.sh` (quotidien, voir le guide serveur) ajoute à l'archive, quand GLPI est installé :
- un **export SQL** de la base (`glpi_db.sql.gz`) ;
- les **fichiers de GLPI** (`glpi_data.tar.gz`), qui contiennent la **clé de chiffrement `glpicrypt.key`** : sans elle, les données chiffrées de la base sont inutilisables. Les deux vont ensemble.

La base brute n'est pas copiée à chaud (copie incohérente). Si l'export échoue (GLPI arrêté), la sauvegarde le signale (code 2).
Restauration (non éprouvée sur un vrai Docker) : redémarrer la pile, restaurer `glpi_data` dans son volume, puis `gunzip -c glpi_db.sql.gz | docker compose exec -T glpi-db mariadb -uroot -p"$GLPI_DB_ROOT_PASSWORD" glpi`.

## 9. Ce que GLPI ne fait pas ici

- Pas de déploiement de logiciels ni de découverte réseau (cela demande le plugin *GLPI Inventory* et ouvrirait des flux vers les caisses, ce que nous évitons).
- Pas de tickets ni de gestion des contrats configurés : GLPI est installé nu. Connecter les alertes de Grafana à des tickets GLPI est une étape ultérieure.
- Pas de haute disponibilité : un seul serveur, comme le reste de la passerelle.
