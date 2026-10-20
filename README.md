# Caisse Démo — caisse tactile + supervision d'un parc de caisses

Deux produits dans un seul dépôt :

1. **Caisse Démo 1.3.3** : application de caisse tactile (Kotlin + Compose Desktop) avec vente hors ligne et synchronisation vers un SaaS.
2. **Supervision de parc** : suivi de la **santé** de milliers de caisses (processeur, mémoire, disque, services, état de l'application), sans ouvrir de port chez les clients, déployé sur AWS avec Terraform. Inventaire GLPI et prise en main à distance RustDesk inclus.

> Maquette de démonstration : pas de conformité fiscale légale (certification, archivage), terminal de paiement simulé.

## Par où commencer

| Je veux… | Ouvrir |
|---|---|
| **Faire la démo client** (supervision, de A à Z) | [`supervision/guides/DEMO-CLIENT.md`](supervision/guides/DEMO-CLIENT.md) |
| Comprendre l'ensemble : **schémas** de déploiement et d'infrastructure | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Retrouver une **commande AWS** | [`supervision/guides/AWS-COMMANDES.md`](supervision/guides/AWS-COMMANDES.md) |
| Mettre à jour Grafana / les alertes sur le serveur | `AWS-COMMANDES.md` § 6 |
| Déployer le serveur sur AWS | [`supervision/terraform/README.md`](supervision/terraform/README.md) |
| Comprendre la supervision (mesures, alertes, dimensionnement) | [`supervision/README.md`](supervision/README.md) |
| Installer un paquet sur une caisse | [`livrables/`](livrables/) (tableau ci-dessous) |
| Voir ce qui a changé | [`CHANGELOG.md`](CHANGELOG.md) |

## Arborescence

```
caisse-demo/
├── README.md, CHANGELOG.md
├── livrables/            les paquets .deb à installer (dernière version de chacun)
├── src/                  code de l'application de caisse (Kotlin) et ses tests
├── tools/                construction du .deb de l'application, vérifications, ssm-executer.sh (commandes sur le serveur AWS)
├── packaging/            icônes
├── saas-mock/            faux SaaS pour la démo de la synchronisation
├── docs/                 ARCHITECTURE.md et schémas (schemas/*.svg), contrat d'API (API.md), captures d'écran
├── storepos-kiosk/       paquet « mode kiosque » (démarrage automatique en plein écran, bouton « i », administration)
└── supervision/
    ├── guides/           DEMO-CLIENT, AWS-COMMANDES, GUIDE-SERVEUR, GUIDE-GLPI, GUIDE-RUSTDESK
    ├── serveur/          la passerelle : Caddy, Prometheus, Loki, Alertmanager, Grafana (Docker), tableau de bord, alertes
    ├── terraform/        déploiement AWS de cette passerelle
    ├── agent/            paquet de supervision installé sur chaque caisse
    ├── remote-agent/     paquet RustDesk (prise en main à distance)
    ├── glpi-agent/       paquet d'inventaire GLPI
    ├── demo/             simulateur de parc pour la démo
    └── tests/            tests automatiques (voir tests/README.md)
```

## Paquets (dossier `livrables/`)

| Paquet | Où l'installer | Rôle |
|---|---|---|
| `caisse-demo_1.3.3_amd64.deb` | chaque caisse | l'application de caisse |
| `storepos-kiosk_1.3.2_all.deb` | chaque caisse | mode kiosque, bouton « i », outils d'administration |
| `storepos-kiosk-monitoring_1.2.1_all.deb` | chaque caisse | agent de supervision (envoie la santé de la machine, en sortie seulement) |
| `storepos-kiosk-remote_1.0.0_all.deb` | chaque caisse (option) | prise en main à distance RustDesk |
| `storepos-kiosk-glpi_1.0.0_all.deb` | chaque caisse (option) | inventaire GLPI (avec l'agent officiel `glpi-agent`) |

```bash
sudo apt install ./caisse-demo_1.3.3_amd64.deb ./storepos-kiosk_1.3.2_all.deb
sudo apt install ./storepos-kiosk-monitoring_1.2.1_all.deb       # Grafana Alloy d'abord : voir supervision/README.md § 2
sudo reboot
```
Les données de l'application sont conservées lors d'une mise à jour (`~/.local/share/caisse-demo/`). Après la mise à jour d'une caisse déjà en kiosque :
`sudo systemctl restart storepos`.

## L'application de caisse

- Connexion par **nom de code** (« Manager », « Caissier 01 ») : l'écran n'affiche pas l'identité des personnes ; le vrai nom reste utilisé dans le système (tickets, journal, SaaS). Pavé de code d'accès, **mise en veille** après 5 minutes (code redemandé, ticket intact).
- Caisse tactile : ticket et pavé numérique à gauche (`Qté`, `% Remise`, `Prix`, `±`, `⌫`, gros bouton **Payer**), catalogue à droite, paiements multiples, comptage de fonds, journal, **vente hors ligne**.
- Droits : **Prix**, **±**, le mode **Retour**, une remise **au-delà de 20 %**, le retour d'un ticket, le prélèvement, la clôture, la réinitialisation et « Quitter » demandent le code d'un responsable.
- Bouton **« i »** (appui maintenu 2 s) : carte d'informations du poste (nom, IP, ID TeamViewer, versions, dernière synchronisation).
- Dates toujours en français ; police Inter embarquée ; photo de fond remplaçable sans reconstruire : `/etc/caisse-demo/accueil.jpg`.

Comptes de démonstration :

| Profil à l'écran | Code | Rôle |
|---|---|---|
| Manager (Armel NGANDO) | `0000` | Responsable |
| Caissier 01 (Alice M.) | `1234` | Caissier |
| Caissier 02 (Chloé D.) | `4321` | Caissier |

Codes-barres de test (à taper puis Entrée) : `3000001` (eau), `3000011` (croissant), `3000043` (journal)…

### Démonstration avec le SaaS simulé

```bash
python3 saas-mock/mock_saas.py --port 8080
```
Page de suivi : `http://<ip>:8080/`. Sur la caisse : ☰ → Configuration → adresse du serveur et code `DEMO-0001` (ou `-0002`, `-0003`) → **Activer la caisse**.
Scénario hors ligne : arrêter le serveur, vendre, le relancer : les tickets partent seuls en moins de 30 s. Contrat d'API : [`docs/API.md`](docs/API.md).

## Construire (développement)

Prérequis : Linux (ou WSL), JDK 21, `rsync`, `unzip`, `curl`, Python 3. Gradle est téléchargé tout seul.
```bash
bash tools/wsl-build.sh test                 # tests unitaires + captures d'écran (build/ui-snapshots)
bash tools/run-it.sh                         # + tests d'intégration contre le SaaS simulé
bash tools/wsl-build.sh createDistributable  # image de l'appli + caisse-demo_<version>_amd64.deb à la racine
bash tools/verify-deb.sh                     # contrôle du .deb (contenu, icônes, raccourci, dépendances, démarrage)
python3 storepos-kiosk/build-deb.py          # paquet kiosque
python3 tools/pkg_builder.py supervision/agent/pkg supervision/agent/dist     # paquet de supervision (idem pour remote-agent, glpi-agent)
```
Après une construction, copier le nouveau `.deb` dans `livrables/` et retirer l'ancien.

## Tests de la supervision

```bash
bash supervision/tests/lancer-tout.sh        # configurations, alertes, agent, scripts serveur, Terraform (hors ligne), synchronisation
```
Détail et tests plus lourds (bout en bout avec les vrais composants) : [`supervision/tests/README.md`](supervision/tests/README.md).

## Limites connues

- Pas de binaire unique : un `.deb` de ~68 Mo avec lanceur natif, Java embarqué et `.jar`.
- Impression via `lp` (CUPS) si disponible, sinon ticket enregistré en `.txt` ; pas de pilote ESC/POS ni de tiroir-caisse.
- HTTP seulement dans le simulateur : le vrai SaaS doit être en HTTPS.
- Supervision : **non testée à 6000 caisses** (estimations dans `supervision/README.md`) ; RustDesk non éprouvé en conditions réelles. GLPI est validé sur le serveur de test (20 octobre 2026) : installation, initialisation, connexion et inventaire d'une caisse.
