#!/usr/bin/env python3
"""Génère dashboards/parc-caisses.json (tableau de bord Grafana du parc de caisses).

    python3 build_dashboard.py

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


def stat(titre, expr, x, w=4, unite="none", seuils=None, decimals=None, description=""):
    seuils = seuils or [{"color": "green", "value": None}]
    champ = {"unit": unite, "thresholds": {"mode": "absolute", "steps": seuils}, "color": {"mode": "thresholds"}}
    if decimals is not None:
        champ["decimals"] = decimals
    return {
        "id": nid(), "type": "stat", "title": titre, "description": description, "datasource": PROM,
        "gridPos": {"x": x, "y": 1, "w": w, "h": 4},
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


panneaux = []

panneaux.append(rangee("Vue d'ensemble du parc", 0))
panneaux += [
    stat("Caisses en ligne", f'count(up{{job="caisse", {F}}} == 1) or vector(0)', 0, seuils=[{"color": "red", "value": None}, {"color": "green", "value": 1}],
         description="Caisses qui ont envoyé des mesures ces 5 dernières minutes."),
    stat("Caisses muettes (24 h)", f'(count(last_over_time(up{{job="caisse", {F}}}[24h])) - count(up{{job="caisse", {F}}})) or vector(0)', 4,
         seuils=[{"color": "green", "value": None}, {"color": "red", "value": 1}],
         description="Caisses vues dans les dernières 24 h mais silencieuses depuis plus de 5 minutes."),
    stat("Hors ligne (serveur SaaS)", f'count(caisse_sync_state{{{F}}} == 0) or vector(0)', 8,
         seuils=[{"color": "green", "value": None}, {"color": "orange", "value": 1}],
         description="Caisses qui vendent mais ne joignent plus le serveur."),
    stat("Tickets en attente", f'sum(caisse_tickets_pending{{{F}}}) or vector(0)', 12,
         seuils=[{"color": "green", "value": None}, {"color": "orange", "value": 20}, {"color": "red", "value": 200}],
         description="Tickets enregistrés localement, pas encore acquittés par le serveur."),
    stat("Tickets du jour", f'sum(caisse_tickets_today{{{F}}}) or vector(0)', 16),
    stat("Ventes du jour", f'sum(caisse_sales_today_cents{{{F}}}) / 100 or vector(0)', 20, unite="currencyEUR", decimals=2),
]

panneaux.append({
    "id": nid(), "type": "piechart", "title": "Versions de l'application", "datasource": PROM,
    "gridPos": {"x": 0, "y": 6, "w": 6, "h": 8},
    "targets": [cible(f'count by (version) (caisse_app_info{{{F}}})', "{{version}}", instant=True)],
    "fieldConfig": {"defaults": {"unit": "none"}, "overrides": []},
    "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "legend": {"displayMode": "table", "placement": "right", "values": ["value"]}, "pieType": "donut"},
})
panneaux.append({
    "id": nid(), "type": "table", "title": "Caisses à surveiller (tickets en attente)", "datasource": PROM,
    "gridPos": {"x": 6, "y": 6, "w": 18, "h": 8},
    "targets": [cible(f'sort_desc(caisse_tickets_pending{{{F}}} > 0)', fmt="table", instant=True)],
    "fieldConfig": {"defaults": {"custom": {"align": "auto"}}, "overrides": [
        {"matcher": {"id": "byName", "options": "Value"}, "properties": [{"id": "displayName", "value": "En attente"}, {"id": "custom.cellOptions", "value": {"type": "color-background"}},
                                                                         {"id": "thresholds", "value": {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "orange", "value": 20}, {"color": "red", "value": 200}]}}]},
    ]},
    "options": {"showHeader": True, "sortBy": [{"displayName": "En attente", "desc": True}]},
    "transformations": [{"id": "organize", "options": {"excludeByName": {"Time": True, "__name__": True, "job": True, "instance": True, "plateforme": True}, "renameByName": {"enseigne": "Enseigne", "magasin": "Magasin", "poste": "Poste", "machine": "Machine"}}}],
})

panneaux.append(serie("Caisses en ligne", [(f'count(up{{job="caisse", {F}}} == 1)', "en ligne")], 0, 15, w=8))
panneaux.append(serie("Tickets en attente (total)", [(f'sum(caisse_tickets_pending{{{F}}})', "en attente")], 8, 15, w=8,
                      description="Une courbe qui monte longtemps = réseau ou serveur indisponible."))
panneaux.append(serie("Ventes du jour (cumul, toutes caisses)", [(f'sum(caisse_sales_today_cents{{{F}}}) / 100', "ventes")], 16, 15, w=8, unite="currencyEUR"))

panneaux.append(rangee("Détail d'une caisse (choisir la machine en haut)", 22))
panneaux.append(serie("Processeur", [(f'100 * (1 - avg by (machine) (rate(node_cpu_seconds_total{{mode="idle", {FM}}}[5m])))', "{{machine}}")], 0, 23, w=8, unite="percent", mini=0, maxi=100))
panneaux.append(serie("Mémoire utilisée", [(f'100 * (1 - node_memory_MemAvailable_bytes{{{FM}}} / node_memory_MemTotal_bytes{{{FM}}})', "{{machine}}")], 8, 23, w=8, unite="percent", mini=0, maxi=100))
panneaux.append(serie("Disque utilisé (/)", [(f'100 * (1 - node_filesystem_avail_bytes{{mountpoint="/", {FM}}} / node_filesystem_size_bytes{{mountpoint="/", {FM}}})', "{{machine}}")], 16, 23, w=8, unite="percent", mini=0, maxi=100))

panneaux.append({
    "id": nid(), "type": "state-timeline", "title": "Services (vert = actif)", "datasource": PROM,
    "gridPos": {"x": 0, "y": 30, "w": 12, "h": 7},
    "targets": [cible(f'node_systemd_unit_state{{state="active", {FM}}}', "{{machine}} · {{name}}")],
    "fieldConfig": {"defaults": {"mappings": [{"type": "value", "options": {"0": {"text": "arrêté", "color": "red"}, "1": {"text": "actif", "color": "green"}}}], "custom": {"fillOpacity": 70}}, "overrides": []},
    "options": {"showValue": "never", "legend": {"showLegend": False}},
})
panneaux.append(serie("Tickets en attente et redémarrages", [
    (f'caisse_tickets_pending{{{FM}}}', "{{machine}} · tickets en attente"),
    (f'kiosk_service_restarts{{unit="storepos.service", {FM}}}', "{{machine}} · redémarrages de l'app"),
], 12, 30, w=12))

panneaux.append({
    "id": nid(), "type": "table", "title": "Logiciels installés", "datasource": PROM,
    "gridPos": {"x": 0, "y": 37, "w": 12, "h": 7},
    "targets": [cible(f'kiosk_package_info{{{FM}}}', fmt="table", instant=True)],
    "fieldConfig": {"defaults": {}, "overrides": []},
    "options": {"showHeader": True},
    "transformations": [{"id": "organize", "options": {"excludeByName": {"Time": True, "__name__": True, "job": True, "instance": True, "plateforme": True, "Value": True, "enseigne": True, "poste": True}, "renameByName": {"package": "Paquet", "version": "Version", "machine": "Machine", "magasin": "Magasin"}}}],
})
panneaux.append({
    "id": nid(), "type": "table", "title": "Application : identité et état", "datasource": PROM,
    "gridPos": {"x": 12, "y": 37, "w": 12, "h": 7},
    "targets": [cible(f'caisse_app_info{{{FM}}}', fmt="table", instant=True)],
    "fieldConfig": {"defaults": {}, "overrides": []},
    "options": {"showHeader": True},
    "transformations": [{"id": "organize", "options": {"excludeByName": {"Time": True, "__name__": True, "job": True, "instance": True, "plateforme": True, "Value": True}, "renameByName": {"version": "Version", "device_id": "Identifiant de la caisse", "saas_magasin": "Magasin (SaaS)", "saas_tpv": "Caisse (SaaS)"}}}],
})

panneaux.append({
    "id": nid(), "type": "logs", "title": "Journaux de la caisse (application, écran, SSH)", "datasource": LOKI,
    "gridPos": {"x": 0, "y": 44, "w": 24, "h": 10},
    "targets": [{"datasource": LOKI, "expr": '{machine=~"$machine", unit=~"storepos.service|lightdm.service|ssh.service|sshd.service"}', "refId": "A", "queryType": "range"}],
    "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True},
})


def variable(nom, label, requete, tout=True):
    return {
        "name": nom, "label": label, "type": "query", "datasource": PROM,
        "query": {"query": requete, "refId": "v"},
        "definition": requete, "refresh": 2, "sort": 1,
        "multi": True, "includeAll": tout, "allValue": ".*", "current": {"selected": True, "text": ["All"], "value": ["$__all"]},
    }


tableau = {
    "uid": "parc-caisses", "title": "Parc de caisses", "tags": ["caisses", "supervision"],
    "timezone": "browser", "schemaVersion": 39, "version": 1, "editable": True, "graphTooltip": 1,
    "time": {"from": "now-6h", "to": "now"}, "refresh": "1m",
    "templating": {"list": [
        variable("enseigne", "Enseigne", 'label_values(up{job="caisse"}, enseigne)'),
        variable("magasin", "Magasin", 'label_values(up{job="caisse", enseigne=~"$enseigne"}, magasin)'),
        variable("machine", "Machine", 'label_values(up{job="caisse", enseigne=~"$enseigne", magasin=~"$magasin"}, machine)'),
    ]},
    "annotations": {"list": []},
    "panels": panneaux,
}

sortie = Path(__file__).resolve().parent / "dashboards" / "parc-caisses.json"
sortie.parent.mkdir(parents=True, exist_ok=True)
sortie.write_text(json.dumps(tableau, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"OK : {sortie} ({len(panneaux)} panneaux)")
