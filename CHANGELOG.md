# Historique des versions — Caisse Démo

Dernière version en haut. Le paquet kiosque (`storepos-kiosk`) et les paquets de supervision ont leur propre numérotation.

## Version 1.3.3

- **Bouton « i » moderne**, dessiné dans l'application : rond, translucide, avec un anneau qui se remplit pendant l'appui maintenu (2 s) ; un appui
  bref n'ouvre rien et affiche « Maintenez 2 s ». Il ouvre une **carte d'informations** (poste : nom, adresse IP, ID TeamViewer, versions ;
  application : magasin, caisse, réseau, dernière synchronisation, tickets en attente). Réglages inchangés : `INFO_BUTTON_ENABLED`,
  `INFO_BUTTON_POS`, `INFO_HOLD_SECONDS` dans `/etc/default/storepos-kiosk`. Le paquet storepos-kiosk 1.3.1 retire l'ancien bouton gris.
- Écran de connexion : les trois tuiles (Vente rapide, Stock en temps réel, Sécurisé) sont retirées.

## Version 1.3.2

- **Nouvel écran de connexion clair**, d'après la maquette du magasin : à gauche la marque (logo, slogan), « Bon retour sur votre caisse », trois tuiles
  (Vente rapide, Stock en temps réel, Sécurisé), l'heure, la date, le magasin et le numéro de caisse, sur une photo de magasin ;
  à droite une **carte blanche** avec le profil (nom de code) centré et le pavé du code d'accès aux touches arrondies.
  La marque se règle dans la base (`marque_nom`, `marque_slogan`). La photo se remplace sans reconstruire : `/etc/caisse-demo/accueil.jpg`
  (ou la variable `CAISSE_FOND_ACCUEIL`) ; sans photo, un décor dessiné en code est utilisé.
- **Dates en français garanties** : elles s'affichaient en anglais (« Mon 19 Oct ») car le runtime embarqué n'a pas les données de langue.
- L'état du réseau devient « Caisse opérationnelle » (local ou en ligne) ou « Hors ligne ».
- Déverrouillage après la veille : même carte blanche que la connexion.

## Version 1.3.1

- **Écran de connexion** : panneau de marque à gauche, liste des profils à droite, puis **pavé de code PIN rond** (points de saisie, effacement
  d'un chiffre, secousse si le code est faux). Palette sombre de la caisse, avatars sobres (silhouette, pas d'initiales).
- **Police Inter** (licence SIL OFL, dans `src/main/resources/fonts`) dans toute l'application : chiffres nets, lisible de loin.
- **Mise en veille** : après 5 minutes sans toucher (réglable : Jamais, 2, 5, 10 ou 15 min, par le responsable, dans le menu), un écran noir
  avec l'heure (qui se déplace pour ne pas marquer l'écran) recouvre la caisse. Au toucher, le code du caissier est redemandé ; la session,
  le ticket en cours et les paiements restent intacts dessous. Un scan pendant la veille n'ajoute rien au ticket.
  Ne coupe pas le rétro-éclairage : cela relève du système (voir le paquet storepos-kiosk).
- « **Mode autonome** » : aucun serveur n'est configuré ; la caisse vend seule et ne transmet rien.

- **Noms de code à la connexion** : l'écran affiche « Manager » et « Caissier 01 », pas l'identité des personnes ; le vrai nom (Armel NGANDO, Alice M., Chloé D.) reste utilisé dans le système (tickets, journal, SaaS). Champ `code` ajouté à l'API du référentiel.

## Version 1.3.0

- **Supervision** : l'application publie ses mesures (tickets en attente, version, ventes du jour, état de la synchronisation…) dans un
  fichier lisible par un agent Grafana Alloy, qui les envoie **en sortie** vers Prometheus. Aucun port n'est ouvert sur la caisse.
  Voir `supervision/README.md` (agent, serveur Grafana, alertes, tableau de bord, tests de bout en bout).

## Version 1.2.0

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

