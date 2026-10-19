# Caisse Démo 1.3.1 — maquette de caisse enregistreuse tactile

Application de caisse (Kotlin + Compose Desktop) pour démonstration : connexion par caissier, caisse tactile
complète, paiements multiples, comptage de fonds, journal, synchronisation avec un SaaS et **vente hors ligne**.
Application indépendante, conçue pour tourner en mode kiosque avec le paquet `storepos-kiosk`.

> Maquette de démonstration : ne gère ni les exigences fiscales légales (certification, archivage),
> ni les paiements sur un vrai terminal (le TPE est simulé).

## Nouveautés de la 1.3.1

- **Écran de connexion** : panneau de marque à gauche, liste des profils à droite, puis **pavé de code PIN rond** (points de saisie, effacement
  d'un chiffre, secousse si le code est faux). Palette sombre de la caisse, avatars sobres (silhouette, pas d'initiales).
- **Police Inter** (licence SIL OFL, dans `src/main/resources/fonts`) dans toute l'application : chiffres nets, lisible de loin.
- **Mise en veille** : après 5 minutes sans toucher (réglable : Jamais, 2, 5, 10 ou 15 min, par le responsable, dans le menu), un écran noir
  avec l'heure (qui se déplace pour ne pas marquer l'écran) recouvre la caisse. Au toucher, le code du caissier est redemandé ; la session,
  le ticket en cours et les paiements restent intacts dessous. Un scan pendant la veille n'ajoute rien au ticket.
  Ne coupe pas le rétro-éclairage : cela relève du système (voir le paquet storepos-kiosk).
- « **Mode autonome** » : aucun serveur n'est configuré ; la caisse vend seule et ne transmet rien.

- **Noms de code à la connexion** : l'écran affiche « Manager » et « Caissier 01 », pas l'identité des personnes ; le vrai nom (Armel NGANDO, Alice M., Chloé D.) reste utilisé dans le système (tickets, journal, SaaS). Champ `code` ajouté à l'API du référentiel.


## Nouveautés de la 1.3.0

- **Supervision** : l'application publie ses mesures (tickets en attente, version, ventes du jour, état de la synchronisation…) dans un
  fichier lisible par un agent Grafana Alloy, qui les envoie **en sortie** vers Prometheus. Aucun port n'est ouvert sur la caisse.
  Voir `supervision/README.md` (agent, serveur Grafana, alertes, tableau de bord, tests de bout en bout).

## Nouveautés de la 1.2.0

- **Nouvelle interface**, à la manière des caisses tactiles du commerce : le **ticket et son pavé numérique à gauche**
  (touches `Qté`, `% Remise`, `Prix`, `±`, `⌫` et gros bouton **Payer**), le **catalogue à droite** (catégories en cartes de
  couleur, produits en cartes avec initiales et prix, fil d'Ariane, recherche).
- On arrive **directement sur la caisse** après la connexion. Le menu **☰** donne accès au journal, au comptage, à la
  configuration, à l'apparence (clair / sombre / auto) et aux actions réservées au responsable.
- **Écran de paiement** en trois colonnes : moyens de paiement, règlements (avec statut du terminal carte), pavé numérique
  avec montants rapides (+10, +20, +50, Exact).
- **Barres de défilement tactiles** : sur écran tactile, une liste ne défile pas en glissant le doigt ; chaque liste a une poignée large.
- **Icône propre à l'application** (dessinée pour ce projet) et **raccourci sur le bureau** créé à l'installation.
- Mise à jour sans perte : les données (`~/.local/share/caisse-demo`) sont conservées et la base est migrée.

## Pavé numérique (comme sur une caisse)

1. Touchez un article dans le ticket (le dernier ajouté est sélectionné automatiquement).
2. Choisissez ce que vous modifiez : **Qté** (par défaut), **% Remise** ou **Prix**.
3. Tapez les chiffres : la ligne se met à jour tout de suite. `⌫` efface un chiffre ; sans saisie en cours, il retire la ligne
   (en mode Qté) ou la remise (en mode % Remise). `±` bascule la ligne en retour.

Droits : **Prix**, **±**, le mode **Retour**, une remise **au-delà de 20 %**, le retour d'un ticket, le prélèvement, la clôture,
la réinitialisation et « Quitter » demandent le **code d'un responsable**.

## Comptes de démonstration

| Caissier | Code | Rôle |
|---|---|---|
| Alice Martin | `1234` | Caissier |
| Bruno Lefèvre | `0000` | Responsable |
| Chloé Dupont | `4321` | Caissier |

Codes-barres de test (à taper puis Entrée, ou avec un lecteur USB, sur l'écran de caisse) : `3000001` (eau), `3000011` (croissant), `3000043` (journal)…

## Installation et mise à jour (VM ou machine Linux amd64)

```bash
sudo cp caisse-demo_1.3.1_amd64.deb /tmp/
sudo apt install /tmp/caisse-demo_1.3.1_amd64.deb      # première installation OU mise à jour de la 1.1.0
```
À l'installation, le paquet :
- installe l'application dans `/opt/caisse-demo` et la commande `caisse-demo` ;
- installe **l'icône de l'application** (6 tailles, thème `hicolor`) et l'entrée du menu des applications ;
- crée le **raccourci « Caisse Démo » sur le bureau** de chaque utilisateur existant (hors compte kiosque `caissepos`) et dans `/etc/skel`
  pour les futurs comptes. Sur GNOME, le lanceur est marqué « fiable » (double-clic direct) ; sinon, clic droit puis « Autoriser le lancement » ;
- laisse le raccourci en place lors d'une mise à jour, et le retire à la désinstallation (`apt remove caisse-demo`).

Mode kiosque (démarrage automatique en plein écran) :
```bash
sudo cp storepos-kiosk_1.3.0_all.deb /tmp/ && sudo apt install /tmp/storepos-kiosk_1.3.0_all.deb
sudo reboot
```
Après une mise à jour d'une caisse déjà en kiosque : `sudo systemctl restart storepos`.

Données : `~/.local/share/caisse-demo/` (`caisse.db`, `tickets/`).
Variables : `CAISSE_DATA_DIR` (autre dossier), `CAISSE_FULLSCREEN=1` (plein écran sans bordure).

## Démonstration avec le SaaS simulé

```bash
python3 saas-mock/mock_saas.py --port 8080
```
Page de suivi des tickets : `http://<ip>:8080/`. Sur la caisse : ☰ → Configuration → adresse du serveur et code `DEMO-0001`
(ou `-0002`, `-0003`) → **Activer la caisse**. Contrat d'API : `docs/API.md`.
Scénario hors ligne : arrêter le serveur, vendre, le relancer : les tickets partent seuls en moins de 30 s.

## Construire

Prérequis : Linux (ou WSL), JDK 21, `rsync`, `unzip`, `curl`, Python 3. Gradle est téléchargé tout seul.
```bash
bash tools/wsl-build.sh test                 # tests unitaires + captures d'écran (build/ui-snapshots) + icônes (build/icons)
bash tools/run-it.sh                         # + tests d'intégration contre le SaaS simulé
bash tools/wsl-build.sh createDistributable  # image de l'appli + caisse-demo_<version>_amd64.deb
bash tools/verify-deb.sh                     # contrôle du .deb (contenu, icônes, raccourci, dépendances, démarrage)
bash tools/test-postinst.sh                  # teste le raccourci bureau dans un bac à sable
```
Les icônes sont générées par `IconeTest` (dessin vectoriel) puis copiées dans `packaging/icons/` et `src/main/resources/icon.png`.

## Limites connues
- Pas un binaire unique : un `.deb` de ~68 Mo avec un lanceur natif, un Java embarqué et les `.jar`.
- Impression via `lp` (CUPS) si disponible, sinon ticket enregistré en `.txt` ; pas de pilote ESC/POS ni de tiroir-caisse.
- Pas encore : écran client, balance, campagnes de fidélité, gestion des produits et clients en tableaux.
- Produits sans photo : cartes à initiales colorées (les images viendront avec le catalogue du SaaS).
- HTTP seulement dans le simulateur : le vrai SaaS doit être en HTTPS.
- Rendu : sans OpenGL (VM sans 3D), Skia bascule seul sur le rendu logiciel.
