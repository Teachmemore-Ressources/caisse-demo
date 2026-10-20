# storepos-kiosk 1.3.2

Paquet kiosque pour l'application de caisse `com.aemsofts.storepos` (Debian 12 / Ubuntu 22.04-24.04, amd64).
Corrige et remplace `storepos-kiosk` 1.0.0.

## Installation

```bash
sudo apt install ./com.aemsofts.storepos_9.1.124_amd64.deb
sudo apt install ./storepos-kiosk_1.3.2_all.deb
sudo storepos-kiosk-check      # diagnostic
sudo reboot
```

## Ce qui change par rapport à 1.0.0

| Point | 1.0.0 | 1.2.0 |
|---|---|---|
| Groupes du compte `caissepos` | aucun | `plugdev`, `dialout`, `video`, `audio` (scanner, imprimante, balance série) |
| Relance du service | `on-failure` (pas de relance si l'appli quitte avec le code 0) | `always`, sans limite de tentatives |
| Écran X | `:0` en dur | détection automatique (`:0`, `:1`, `:2`...), 120 s max |
| Plein écran | `wmctrl` par titre « storepos » | règle Openbox + `wmctrl` par PID, puis par titre (`WINDOW_NAME_PATTERN`) |
| Raccourcis / menus | actifs | clavier et souris Openbox sans liaisons |
| Changement de console (Ctrl+Alt+Fx) | possible | bloqué (`DontVTSwitch`, `DontZap`) |
| Liste des utilisateurs à l'écran de connexion | affichée | masquée, invité refusé |
| SSH pour `caissepos` | possible | refusé (`DenyUsers`) |
| Ubuntu Server (cible `multi-user`) | le service ne démarrait pas | `graphical.target` + lightdm activés |
| Arrêt du service | l'appli pouvait rester orpheline | SIGTERM transmis à l'appli |
| Identification de la caisse | aucune | bouton « i » tactile (appui 2 s) + `storepos-kiosk-info` : nom, IP, ID TeamViewer, versions |
| `apt purge` | supprimait l'utilisateur et sa base | conserve les données de vente |
| Logs | fichier seul | journald + fichier, avec rotation |
| Réglages | en dur | `/etc/default/storepos-kiosk` |
| Outils | aucun | `storepos-kiosk-check`, `storepos-kiosk-maintenance` |

## Administration

```bash
sudo storepos-kiosk-check                 # état complet de la caisse
sudo storepos-kiosk-maintenance on        # arrête l'appli pour intervenir
sudo storepos-kiosk-maintenance off       # la relance
storepos-kiosk-info                       # nom, IP, ID TeamViewer, versions
sudo journalctl -u storepos -f            # logs du service
tail -f /var/log/storepos-kiosk.log       # logs du script de lancement
sudo nano /etc/default/storepos-kiosk     # réglages, puis : sudo systemctl restart storepos
```

Les consoles étant bloquées, **l'administration se fait uniquement par SSH** (compte `admincaisse`, clé).

## À savoir

- Non testé sur une vraie caisse : valider sur UNE machine avant d'en faire un ghost.
- Les SDK Datalogic / Epson restent à installer avec les scripts `install-*-linux.sh` de l'appli (`--user caissepos`).
- La base `storepos.db` est dans le home de `caissepos` (chemin exact à confirmer) : à sauvegarder.
- Avant un ghost : vider `/etc/machine-id`, régénérer les clés SSH de l'hôte, ne pas capturer une base de test.
- Reconstruire le paquet : `python build-deb.py` (sans dpkg, fonctionne sous Windows).
