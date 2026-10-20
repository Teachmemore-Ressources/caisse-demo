#!/usr/bin/env python3
"""Génère dashboards/parc-caisses.json : tableau de bord SANTÉ du parc de caisses (Grafana).

    python3 build_dashboard.py

Pensé pour des milliers de caisses de clients différents :
  - de haut en bas : l'état du parc en un coup d'œil, la situation PAR ENSEIGNE, les caisses à traiter (les 15 pires), la tendance du parc,
    puis le détail d'UNE caisse ;
  - aucun graphique à une courbe par caisse sur tout le parc (6000 courbes seraient illisibles et lents) : tableaux « top 15 » et centiles ;
  - les calculs lourds (processeur, mémoire, disque, température) sont précalculés par Prometheus (règles « caisse:… », voir
    prometheus/rules/enregistrements.yml) ;
  - pas de chiffres d'activité commerciale : la supervision ne remonte que de l'état de santé.
Le JSON est généré plutôt qu'écrit à la main : une seule définition des filtres, pas de coquille dans les requêtes.
Les requêtes sont testées contre de vraies données par supervision/tests/e2e.sh.
"""
import json
from pathlib import Path

PROM = {"type": "prometheus", "uid": "prom"}
LOKI = {"type": "loki", "uid": "loki"}

# Filtres communs : choisis dans les listes déroulantes en haut du tableau de bord
F = 'enseigne=~"$enseigne", magasin=~"$magasin"'
FM = 'machine=~"$machine"'
IDENT = "enseigne, magasin, poste, machine"

_id = 0


def nid():
    global _id
    _id += 1
    return _id


def cible(expr, legende="", instant=False, fmt="time_series", ref="A"):
    c = {"datasource": PROM, "expr": expr, "legendFormat": legende, "refId": ref, "instant": instant, "range": not instant}
    if fmt == "table":
        c["format"] = "table"
    return c


V = lambda couleur, valeur=None: {"color": couleur, "value": valeur}  # noqa: E731
VERT_ROUGE = [V("green"), V("red", 1)]
VERT_ORANGE_ROUGE = lambda a, b: [V("green"), V("orange", a), V("red", b)]  # noqa: E731


def stat(titre, expr, x, w=4, unite="none", seuils=None, description="", y=1):
    champ = {"unit": unite, "thresholds": {"mode": "absolute", "steps": seuils or [V("green")]}, "color": {"mode": "thresholds"}}
    return {
        "id": nid(), "type": "stat", "title": titre, "description": description, "datasource": PROM,
        "gridPos": {"x": x, "y": y, "w": w, "h": 4},
        "targets": [cible(expr, instant=True)],
        "fieldConfig": {"defaults": champ, "overrides": []},
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "colorMode": "background", "graphMode": "none", "textMode": "value"},
    }


def serie(titre, exprs, x, y, w=12, h=7, unite="none", description="", mini=None, maxi=None):
    defaults = {"unit": unite, "custom": {"lineWidth": 2, "fillOpacity": 12, "showPoints": "never"}}
    if mini is not None:
        defaults["min"] = mini
    if maxi is not None:
        defaults["max"] = maxi
    return {
        "id": nid(), "type": "timeseries", "title": titre, "description": description, "datasource": PROM,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [cible(e, l, ref=chr(65 + i)) for i, (e, l) in enumerate(exprs)],
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
    }


def rangee(titre, y):
    return {"id": nid(), "type": "row", "title": titre, "collapsed": False, "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []}


EXCLURE = {"Time": True, "__name__": True, "job": True, "instance": True, "plateforme": True}
RENOMMER = {"enseigne": "Enseigne", "magasin": "Magasin", "poste": "Poste", "machine": "Machine"}


def tableau_top(titre, expr, x, y, nom_valeur, unite="none", seuils=None, w=8, h=8, description=""):
    """Tableau « les N pires » : une ligne par caisse, la valeur colorée selon des seuils, triée du pire au meilleur."""
    return {
        "id": nid(), "type": "table", "title": titre, "description": description, "datasource": PROM,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [cible(expr, fmt="table", instant=True)],
        "fieldConfig": {"defaults": {"custom": {"align": "auto"}}, "overrides": [
            {"matcher": {"id": "byName", "options": "Value"}, "properties": [
                {"id": "displayName", "value": nom_valeur}, {"id": "unit", "value": unite}, {"id": "decimals", "value": 0},
                {"id": "custom.cellOptions", "value": {"type": "color-background"}},
                {"id": "thresholds", "value": {"mode": "absolute", "steps": seuils or [V("green")]}},
                {"id": "custom.width", "value": 120},
            ]},
            # largeurs fixes : les quatre colonnes tiennent dans une case étroite, la valeur reste visible sans faire défiler
            {"matcher": {"id": "byName", "options": "Enseigne"}, "properties": [{"id": "custom.width", "value": 95}]},
            {"matcher": {"id": "byName", "options": "Magasin"}, "properties": [{"id": "custom.width", "value": 150}]},
            {"matcher": {"id": "byName", "options": "Poste"}, "properties": [{"id": "custom.width", "value": 55}]},
        ]},
        "options": {"showHeader": True, "sortBy": [{"displayName": nom_valeur, "desc": True}]},
        # la valeur (le pourcentage) doit rester visible sans défiler : on retire l'identifiant technique de la machine et on range les colonnes
        "transformations": [{"id": "organize", "options": {
            "excludeByName": {**EXCLURE, "machine": True}, "renameByName": RENOMMER,
            "indexByName": {"enseigne": 0, "magasin": 1, "poste": 2, "Value": 3},
        }}],
    }


panneaux = []

# --- 1. L'état du parc en un coup d'œil ---------------------------------------------------------------------------------------------
panneaux.append(rangee("Santé du parc en un coup d'œil", 0))
RESSOURCES = (f'max by (machine) (caisse:cpu_percent{{{F}}} > 90) or max by (machine) (caisse:memoire_percent{{{F}}} > 90) '
              f'or max by (machine) (caisse:disque_percent{{{F}}} > 90)')
APPLI = (f'max by (machine) (node_systemd_unit_state{{name="storepos.service", state="active", {F}}} == 0) '
         f'or max by (machine) (time() - caisse_app_last_write_timestamp_seconds{{{F}}} > 180)')
panneaux += [
    stat("Caisses en ligne", f'count(up{{job="caisse", {F}}} == 1) or vector(0)', 0, seuils=[V("red"), V("green", 1)],
         description="Caisses qui ont envoyé des mesures ces 5 dernières minutes."),
    stat("Caisses muettes", f'(count(caisse:connue{{{F}}}) - count(up{{job="caisse", {F}}})) or vector(0)', 4, seuils=VERT_ROUGE,
         description="Caisses vues dans les dernières 24 h mais silencieuses depuis plus de 5 minutes (éteintes, réseau coupé, agent arrêté)."),
    stat("Ressources > 90 %", f'count({RESSOURCES}) or vector(0)', 8, seuils=[V("green"), V("orange", 1)],
         description="Caisses dont le processeur, la mémoire ou le disque dépasse 90 %."),
    stat("App arrêtée / figée", f'count({APPLI}) or vector(0)', 12, seuils=VERT_ROUGE,
         description="Le service de caisse est arrêté, ou l'application n'actualise plus ses mesures depuis plus de 3 minutes."),
    stat("Hors ligne SaaS", f'count(caisse_sync_state{{{F}}} == 0) or vector(0)', 16, seuils=[V("green"), V("orange", 1)],
         description="Caisses qui vendent mais ne joignent plus le serveur."),
    stat("Tickets en attente", f'sum(caisse_tickets_pending{{{F}}}) or vector(0)', 20, seuils=VERT_ORANGE_ROUGE(20, 200),
         description="Tickets enregistrés localement, pas encore acquittés par le serveur : un risque de perte si la caisse tombe."),
]

# --- 2. Par enseigne : une ligne par client ----------------------------------------------------------------------------------------
panneaux.append(rangee("Par enseigne", 5))
EN_ALERTE_ENS = (f'max by (enseigne, machine) (caisse:cpu_percent{{{F}}} > 90) or max by (enseigne, machine) (caisse:memoire_percent{{{F}}} > 90) '
                 f'or max by (enseigne, machine) (caisse:disque_percent{{{F}}} > 90)')
panneaux.append({
    "id": nid(), "type": "table", "title": "Situation de chaque enseigne", "datasource": PROM,
    "description": "Une ligne par client : combien de caisses, combien en ligne, muettes, en alerte de ressources, hors ligne du serveur.",
    "gridPos": {"x": 0, "y": 6, "w": 18, "h": 9},
    "targets": [
        cible(f'count by (enseigne) (up{{job="caisse", {F}}} == 1)', fmt="table", instant=True, ref="A"),
        cible(f'count by (enseigne) (count by (enseigne, machine) (caisse:connue{{{F}}}))', fmt="table", instant=True, ref="B"),
        cible(f'count by (enseigne) ({EN_ALERTE_ENS})', fmt="table", instant=True, ref="C"),
        cible(f'count by (enseigne) (caisse_sync_state{{{F}}} == 0)', fmt="table", instant=True, ref="D"),
        cible(f'sum by (enseigne) (caisse_tickets_pending{{{F}}})', fmt="table", instant=True, ref="E"),
    ],
    "fieldConfig": {"defaults": {"custom": {"align": "auto"}}, "overrides": [
        {"matcher": {"id": "byName", "options": "Muettes"}, "properties": [{"id": "custom.cellOptions", "value": {"type": "color-background"}}, {"id": "thresholds", "value": {"mode": "absolute", "steps": VERT_ROUGE}}]},
        {"matcher": {"id": "byName", "options": "Ressources en alerte"}, "properties": [{"id": "custom.cellOptions", "value": {"type": "color-background"}}, {"id": "thresholds", "value": {"mode": "absolute", "steps": [V("green"), V("orange", 1)]}}]},
    ]},
    "options": {"showHeader": True, "sortBy": [{"displayName": "Muettes", "desc": True}]},
    "transformations": [
        {"id": "joinByField", "options": {"byField": "enseigne", "mode": "outer"}},
        {"id": "calculateField", "options": {"mode": "binary", "binary": {"left": "Value #B", "operator": "-", "right": "Value #A"}, "alias": "Muettes", "replaceFields": False}},
        {"id": "organize", "options": {
            "excludeByName": {"Time": True, "Time 1": True, "Time 2": True, "Time 3": True, "Time 4": True, "Time 5": True},
            "renameByName": {"enseigne": "Enseigne", "Value #A": "En ligne", "Value #B": "Vues (24 h)", "Value #C": "Ressources en alerte", "Value #D": "Hors ligne SaaS", "Value #E": "Tickets en attente"},
            "indexByName": {"enseigne": 0, "Value #B": 1, "Value #A": 2, "Muettes": 3, "Value #C": 4, "Value #D": 5, "Value #E": 6},
        }},
    ],
})
panneaux.append({
    "id": nid(), "type": "piechart", "title": "Versions de l'application", "datasource": PROM,
    "gridPos": {"x": 18, "y": 6, "w": 6, "h": 9},
    "targets": [cible(f'count by (version) (caisse_app_info{{{F}}})', "{{version}}", instant=True)],
    "fieldConfig": {"defaults": {"unit": "none"}, "overrides": []},
    "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "legend": {"displayMode": "table", "placement": "right", "values": ["value"]}, "pieType": "donut"},
})

# --- 3. Les caisses à traiter : les 15 pires dans chaque catégorie ------------------------------------------------------------------
panneaux.append(rangee("Caisses à traiter (les 15 pires)", 16))
panneaux += [
    tableau_top("Processeur le plus chargé", f'topk(15, caisse:cpu_percent{{{F}}})', 0, 17, "Processeur", "percent", VERT_ORANGE_ROUGE(70, 90)),
    tableau_top("Mémoire la plus utilisée", f'topk(15, caisse:memoire_percent{{{F}}})', 8, 17, "Mémoire", "percent", VERT_ORANGE_ROUGE(80, 90)),
    tableau_top("Disque le plus plein (/)", f'topk(15, caisse:disque_percent{{{F}}})', 16, 17, "Disque", "percent", VERT_ORANGE_ROUGE(80, 90)),
    tableau_top("Température la plus élevée", f'topk(15, caisse:temperature_max_celsius{{{F}}})', 0, 25, "Température", "celsius", VERT_ORANGE_ROUGE(70, 85)),
    tableau_top("Application arrêtée ou figée", f'topk(15, max by ({IDENT}) (time() - caisse_app_last_write_timestamp_seconds{{{F}}}) > 180)', 8, 25, "Sans nouvelles depuis", "s",
                [V("green"), V("red", 180)], description="Temps écoulé depuis la dernière actualisation des mesures par l'application (au-delà de 3 minutes)."),
    tableau_top("Caisses muettes", f'topk(15, count by ({IDENT}) (caisse:connue{{{F}}}) unless count by ({IDENT}) (up{{job="caisse", {F}}}))', 16, 25, "Vue ces dernières 24 h", "none",
                [V("red")], description="Caisses qui ont envoyé des mesures dans les 24 dernières heures mais plus depuis 5 minutes."),
]

# --- 4. Tendance du parc : centiles, pas une courbe par caisse -----------------------------------------------------------------------
panneaux.append(rangee("Tendance du parc", 33))
def centiles(metrique):
    return [(f'quantile(0.5, {metrique}{{{F}}})', "médiane"), (f'quantile(0.95, {metrique}{{{F}}})', "95e centile (1 caisse sur 20 fait pire)"), (f'max({metrique}{{{F}}})', "pire caisse")]
panneaux += [
    serie("Caisses en ligne", [(f'count(up{{job="caisse", {F}}} == 1)', "en ligne")], 0, 34, w=6),
    serie("Processeur du parc", centiles("caisse:cpu_percent"), 6, 34, w=6, unite="percent", mini=0, maxi=100),
    serie("Mémoire du parc", centiles("caisse:memoire_percent"), 12, 34, w=6, unite="percent", mini=0, maxi=100),
    serie("Disque du parc", centiles("caisse:disque_percent"), 18, 34, w=6, unite="percent", mini=0, maxi=100),
]

# --- 5. Détail d'UNE caisse (choisir la machine en haut) ---------------------------------------------------------------------------------
panneaux.append(rangee("Détail d'une caisse (choisir la machine en haut)", 41))
panneaux += [
    serie("Processeur", [(f'caisse:cpu_percent{{{FM}}}', "utilisé"), (f'caisse:iowait_percent{{{FM}}}', "attente du disque")], 0, 42, w=8, unite="percent", mini=0, maxi=100),
    serie("Mémoire utilisée", [(f'caisse:memoire_percent{{{FM}}}', "{{magasin}} poste {{poste}}")], 8, 42, w=8, unite="percent", mini=0, maxi=100),
    serie("Disque utilisé (/)", [(f'caisse:disque_percent{{{FM}}}', "{{magasin}} poste {{poste}}")], 16, 42, w=8, unite="percent", mini=0, maxi=100),
    serie("Charge du système", [(f'node_load1{{{FM}}}', "1 min"), (f'node_load5{{{FM}}}', "5 min"), (f'node_load15{{{FM}}}', "15 min")], 0, 49, w=8),
    serie("Température", [(f'caisse:temperature_max_celsius{{{FM}}}', "{{magasin}} poste {{poste}}")], 8, 49, w=8, unite="celsius"),
    serie("Depuis le dernier démarrage", [(f'caisse:uptime_seconds{{{FM}}}', "{{magasin}} poste {{poste}}")], 16, 49, w=8, unite="s",
          description="Une courbe qui retombe à zéro = la caisse a redémarré."),
]
panneaux.append({
    "id": nid(), "type": "state-timeline", "title": "Services (vert = actif)", "datasource": PROM,
    "gridPos": {"x": 0, "y": 56, "w": 12, "h": 7},
    "targets": [cible(f'node_systemd_unit_state{{state="active", {FM}}}', "{{name}}")],
    "fieldConfig": {"defaults": {"mappings": [{"type": "value", "options": {"0": {"text": "arrêté", "color": "red"}, "1": {"text": "actif", "color": "green"}}}], "custom": {"fillOpacity": 70}}, "overrides": []},
    "options": {"showValue": "never", "legend": {"showLegend": False}},
})
panneaux.append(serie("Tickets en attente et redémarrages de l'application", [
    (f'caisse_tickets_pending{{{FM}}}', "tickets en attente"),
    (f'kiosk_service_restarts{{unit="storepos.service", {FM}}}', "redémarrages de l'application"),
], 12, 56, w=12))
panneaux.append({
    "id": nid(), "type": "table", "title": "Logiciels installés", "datasource": PROM,
    "gridPos": {"x": 0, "y": 63, "w": 12, "h": 7},
    "targets": [cible(f'kiosk_package_info{{{FM}}}', fmt="table", instant=True)],
    "fieldConfig": {"defaults": {}, "overrides": []},
    "options": {"showHeader": True},
    "transformations": [{"id": "organize", "options": {"excludeByName": {**EXCLURE, "Value": True, "enseigne": True, "poste": True}, "renameByName": {"package": "Paquet", "version": "Version", "machine": "Machine", "magasin": "Magasin"}}}],
})
panneaux.append({
    "id": nid(), "type": "table", "title": "Application : identité et état", "datasource": PROM,
    "gridPos": {"x": 12, "y": 63, "w": 12, "h": 7},
    "targets": [cible(f'caisse_app_info{{{FM}}}', fmt="table", instant=True)],
    "fieldConfig": {"defaults": {}, "overrides": []},
    "options": {"showHeader": True},
    "transformations": [{"id": "organize", "options": {"excludeByName": {**EXCLURE, "Value": True}, "renameByName": {"version": "Version", "device_id": "Identifiant de la caisse", "saas_magasin": "Magasin (SaaS)", "saas_tpv": "Caisse (SaaS)"}}}],
})
panneaux.append({
    "id": nid(), "type": "logs", "title": "Journaux de la caisse (application, écran, SSH)", "datasource": LOKI,
    "gridPos": {"x": 0, "y": 70, "w": 24, "h": 10},
    "targets": [{"datasource": LOKI, "expr": '{machine=~"$machine", unit=~"storepos.service|lightdm.service|ssh.service|sshd.service"}', "refId": "A", "queryType": "range"}],
    "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True},
})


def variable(nom, label, requete, tout=True, multi=True):
    v = {
        "name": nom, "label": label, "type": "query", "datasource": PROM,
        "query": {"query": requete, "refId": "v"},
        "definition": requete, "refresh": 2, "sort": 1, "multi": multi, "includeAll": tout,
    }
    if tout:
        v["allValue"] = ".*"
        v["current"] = {"selected": True, "text": ["All"], "value": ["$__all"]}
    return v


tableau = {
    "uid": "parc-caisses", "title": "Santé du parc de caisses", "tags": ["caisses", "supervision", "santé"],
    "timezone": "browser", "schemaVersion": 39, "version": 2, "editable": True, "graphTooltip": 1,
    "time": {"from": "now-6h", "to": "now"}, "refresh": "1m",
    "templating": {"list": [
        variable("enseigne", "Enseigne", 'label_values(up{job="caisse"}, enseigne)'),
        variable("magasin", "Magasin", 'label_values(up{job="caisse", enseigne=~"$enseigne"}, magasin)'),
        # UNE seule machine à la fois dans le détail : afficher 6000 courbes d'un coup serait illisible et très lent
        variable("machine", "Machine (détail)", 'label_values(up{job="caisse", enseigne=~"$enseigne", magasin=~"$magasin"}, machine)', tout=False, multi=False),
    ]},
    "annotations": {"list": []},
    "panels": panneaux,
}

sortie = Path(__file__).resolve().parent / "dashboards" / "parc-caisses.json"
sortie.parent.mkdir(parents=True, exist_ok=True)
sortie.write_text(json.dumps(tableau, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"OK : {sortie} ({len(panneaux)} panneaux)")
