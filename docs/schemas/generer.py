#!/usr/bin/env python3
"""Génère les deux schémas du dépôt (SVG autonomes, lisibles sur GitHub) :

    python3 docs/schemas/generer.py        ->  docs/schemas/deploiements.svg  et  docs/schemas/infrastructure.svg

Pour changer un schéma : modifier les listes ci-dessous (textes, positions) puis relancer la commande. Aucune dépendance.
"""
import pathlib
from xml.sax.saxutils import escape

OUT = pathlib.Path(__file__).resolve().parent
FONT = "Inter, 'Segoe UI', Helvetica, Arial, sans-serif"
INK, MUTED, BG = "#1f2937", "#6b7280", "#ffffff"
# (remplissage, bordure) par famille
C = {
    "bleu":   ("#e0edff", "#3b82f6"),
    "vert":   ("#dcf5e6", "#22a06b"),
    "violet": ("#ede7fb", "#7c5cd6"),
    "ambre":  ("#fff2d6", "#d99a1b"),
    "gris":   ("#eef0f3", "#8b95a5"),
    "rouge":  ("#fde6e4", "#d6584b"),
    "blanc":  ("#ffffff", "#8b95a5"),
}


class Svg:
    def __init__(self, w, h, titre, sous_titre=""):
        self.w, self.h, self.p = w, h, []
        self.p.append(f'<rect width="{w}" height="{h}" fill="{BG}"/>')
        self.text(24, 34, titre, 22, bold=True)
        if sous_titre:
            self.text(24, 56, sous_titre, 13, color=MUTED)

    def text(self, x, y, s, size=13, bold=False, color=INK, anchor="start"):
        w = "700" if bold else "400"
        self.p.append(f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" font-weight="{w}" fill="{color}" text-anchor="{anchor}">{escape(s)}</text>')

    def box(self, x, y, w, h, lignes, fam="blanc", titre=None, size=12, dash=False, rx=10):
        f, s = C[fam]
        d = ' stroke-dasharray="6 4"' if dash else ""
        self.p.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{f}" stroke="{s}" stroke-width="1.6"{d}/>')
        n = len(lignes) + (1 if titre else 0)
        lh = size + 4
        y0 = y + (h - n * lh) / 2 + size
        if titre:
            self.text(x + w / 2, y0, titre, size + 1, bold=True, anchor="middle"); y0 += lh
        for l in lignes:
            self.text(x + w / 2, y0, l, size, color=INK if not l.startswith("·") else INK, anchor="middle"); y0 += lh

    def zone(self, x, y, w, h, titre, fam="gris"):
        f, s = C[fam]
        self.p.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="{f}" fill-opacity="0.35" stroke="{s}" stroke-width="1.6" stroke-dasharray="8 5"/>')
        self.text(x + 14, y + 22, titre, 13, bold=True, color=s)

    def arrow(self, pts, label=None, fam="gris", lx=None, ly=None):
        s = C[fam][1]
        d = "M " + " L ".join(f"{a} {b}" for a, b in pts)
        self.p.append(f'<path d="{d}" fill="none" stroke="{s}" stroke-width="2" marker-end="url(#fl-{fam})"/>')
        if label:
            (ax, ay), (bx, by) = pts[0], pts[-1]
            x = lx if lx is not None else (ax + bx) / 2
            y = ly if ly is not None else (ay + by) / 2 - 6
            self.p.append(f'<rect x="{x - len(label) * 3.3 - 4}" y="{y - 11}" width="{len(label) * 6.6 + 8}" height="16" rx="4" fill="{BG}" fill-opacity="0.92"/>')
            self.text(x, y + 1, label, 11, color=s, anchor="middle")

    def save(self, nom):
        defs = "".join(
            f'<marker id="fl-{k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="{v[1]}"/></marker>'
            for k, v in C.items())
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" width="{self.w}" height="{self.h}">'
               f'<defs>{defs}</defs>' + "".join(self.p) + "</svg>\n")
        (OUT / nom).write_text(svg, encoding="utf-8")
        print("écrit :", OUT / nom)


# ------------------------------------------------------------------------------------------------------------------
# 1. Schéma de principe des déploiements
# ------------------------------------------------------------------------------------------------------------------
def deploiements():
    W = 1210
    s = Svg(W, 834, "Schéma de principe : comment tout se déploie",
            "Cinq étapes, de la construction des paquets à l'exploitation. Le poste de travail est la machine Linux (~/caisse-demo).")
    lanes = [
        ("① Construire", "poste Linux", "bleu", [
            (["Code source", "src/ · supervision/ · tools/", "(dépôt Git)"], "blanc"),
            (["Compiler et tester", "bash tools/wsl-build.sh · make tests"], "blanc"),
            (["Fabriquer les paquets .deb", "caisse-demo · storepos-kiosk", "supervision · remote · glpi"], "blanc"),
            (["livrables/", "dernière version de chaque", "paquet (make livrables)"], "bleu"),
        ]),
        ("② Installer", "sur chaque caisse", "vert", [
            (["Installer les paquets", "sudo apt install ./livrables/*.deb"], "blanc"),
            (["Inscrire à la supervision", "storepos-kiosk-monitoring-enroll", "(identifiant testé avant d'écrire)"], "blanc"),
            (["Inventaire et accès distant", "glpi-agent · RustDesk", "(options)"], "blanc"),
            (["Envoi sortant seulement (HTTPS)", "santé · journaux · inventaire", "aucun port ouvert chez le client"], "vert"),
        ]),
        ("③ Infrastructure", "AWS · Terraform", "violet", [
            (["Terraform", "terraform.tfvars + tfstate", "plan puis apply"], "blanc"),
            (["Réseau et serveur", "VPC · pare-feu 80/443 · EC2", "IP fixe · sans SSH"], "blanc"),
            (["Données et secrets", "disque chiffré + instantanés", "S3 · Parameter Store · IAM"], "blanc"),
            (["Serveur Docker (passerelle)", "Caddy · Prometheus · Loki", "Alertmanager · Grafana · GLPI"], "violet"),
        ]),
        ("④ Configurer", "le serveur", "ambre", [
            (["Modifier supervision/serveur/", "alertes · tableau de bord", "Caddy · docker-compose"], "blanc"),
            (["terraform apply", "publie les fichiers modifiés", "dans le bucket S3 « configuration »"], "blanc"),
            (["passerelle-sync", "via SSM (tools/ssm-executer.sh)", "S3 → /opt/passerelle"], "blanc"),
            (["Relance ciblée", "seulement les services dont", "la configuration a changé"], "ambre"),
        ]),
        ("⑤ Exploiter", "sans SSH", "gris", [
            (["Commandes à distance", "aws ssm start-session", "tools/ssm-executer.sh"], "blanc"),
            (["Tunnels SSM", "Grafana :3000 · GLPI :8081", "jamais publics"], "blanc"),
            (["Enseignes", "enseigne-ajouter.sh / -revoquer.sh", "un identifiant par client"], "blanc"),
            (["Sauvegardes et coûts", "03:30 UTC → S3 · instantanés", "budget AWS"], "gris"),
        ]),
    ]
    x0, y0, lh, gap, bw, bh, bgap = 190, 78, 138, 10, 215, 86, 38
    xs = [x0 + i * (bw + bgap) for i in range(4)]
    centres_y = []
    for i, (titre, sous, fam, boites) in enumerate(lanes):
        y = y0 + i * (lh + gap)
        f, st = C[fam]
        s.p.append(f'<rect x="14" y="{y}" width="{W - 28}" height="{lh}" rx="12" fill="{f}" fill-opacity="0.28" stroke="{st}" stroke-width="1.2"/>')
        s.text(30, y + 62, titre, 17, bold=True, color=st)
        s.text(30, y + 84, sous, 12, color=MUTED)
        by = y + (lh - bh) / 2
        centres_y.append(by)
        for j, (lignes, bf) in enumerate(boites):
            s.box(xs[j], by, bw, bh, lignes, bf)
            if j:
                s.arrow([(xs[j - 1] + bw, by + bh / 2), (xs[j], by + bh / 2)], fam=fam)
    # flèches entre les étapes
    last = xs[3] + bw / 2
    # livrables -> installer : boîte 4 de la ligne 1 vers boîte 1 de la ligne 2 (coude)
    s.arrow([(xs[3] + 6, centres_y[0] + bh), (xs[3] + 6, centres_y[0] + bh + 20), (xs[0] + bw / 2, centres_y[0] + bh + 20), (xs[0] + bw / 2, centres_y[1])], "installer", fam="bleu", lx=(xs[0] + xs[3]) / 2 + 100, ly=centres_y[0] + bh + 16)
    s.arrow([(xs[3] + bw / 2 + 40, centres_y[1] + bh), (xs[3] + bw / 2 + 40, centres_y[2])], "mesures, journaux, inventaire (HTTPS)", fam="vert", lx=xs[3] + bw / 2 - 98, ly=centres_y[1] + bh + 28)
    s.arrow([(xs[3] + bw / 2, centres_y[3]), (xs[3] + bw / 2, centres_y[2] + bh)], "configuration appliquée", fam="ambre", lx=xs[3] + bw / 2 - 86, ly=centres_y[3] - 14)
    s.save("deploiements.svg")


# ------------------------------------------------------------------------------------------------------------------
# 2. Schéma d'infrastructure
# ------------------------------------------------------------------------------------------------------------------
def infrastructure():
    W, H = 1260, 860
    s = Svg(W, H, "Schéma d'infrastructure",
            "Les caisses envoient vers le serveur (sortie seulement). Aucun accès entrant chez le client, aucun SSH sur le serveur.")

    # --- Magasins
    s.zone(20, 80, 250, 560, "Magasins (réseau du client)", "bleu")
    s.box(40, 112, 210, 230, [], "bleu", size=12)
    s.text(145, 140, "Caisse kiosk Linux", 13, bold=True, anchor="middle")
    for i, t in enumerate(["caisse-demo + storepos-kiosk", "Grafana Alloy (santé + journaux)", "glpi-agent (inventaire)", "RustDesk (accès distant)"]):
        s.box(52, 160 + i * 42, 186, 34, [t], "blanc", size=11, rx=7)
    s.box(40, 360, 210, 56, ["… × des milliers de caisses", "plusieurs enseignes"], "blanc", dash=True)
    s.box(40, 440, 210, 70, ["Box ADSL du client", "sortie HTTPS seulement", "rien n'est ouvert"], "gris")
    s.box(40, 530, 210, 90, ["Identifiant par enseigne", "mot de passe saisi à l'inscription", "vérifié par le serveur"], "ambre", size=11)

    # --- Internet / DNS
    s.zone(300, 80, 190, 560, "Internet et DNS", "gris")
    s.box(316, 120, 158, 150, ["Cloudflare (DNS seul)", "nuage GRIS obligatoire", "metrics.…  logs.…", "glpi.…  rustdesk.…", "→ IP fixe du serveur"], "gris", size=11)
    s.box(316, 300, 158, 90, ["HTTPS 443", "certificats Let's Encrypt", "gérés par Caddy"], "blanc", size=11)
    s.box(316, 420, 158, 90, ["RustDesk (option)", "21115-21117 TCP", "21116 UDP"], "blanc", size=11)

    # --- AWS
    s.zone(520, 80, 720, 640, "AWS · région eu-west-3 (Paris) · créé par Terraform", "violet")
    s.box(540, 112, 680, 38, ["VPC · sous-réseau public · groupe de sécurité : 80 et 443 seulement (aucun port 22)"], "violet", size=12)
    s.box(540, 164, 680, 360, [""], "blanc")
    s.text(560, 188, "Serveur EC2 « passerelle » · Ubuntu 24.04 · t3.large · IP fixe · disque racine chiffré · Docker Compose", 12, bold=True, color="#7c5cd6")
    # conteneurs
    cx = [560, 760, 960]
    s.box(cx[0], 204, 180, 92, ["Caddy", "TLS · un identifiant", "par enseigne", "tout le reste : 404"], "violet", size=11)
    s.box(cx[1], 204, 180, 92, ["Prometheus", "reçoit les mesures de santé", "règles et alertes", "inventaire caisse:connue (7 j)"], "violet", size=11)
    s.box(cx[2], 204, 240, 92, ["Loki + Alertmanager", "journaux des caisses", "alertes → notifications", "(récepteur de démo)"], "violet", size=11)
    s.box(cx[0], 316, 180, 92, ["Grafana", "127.0.0.1:3000", "tunnel SSM seulement", "tableau « Santé du parc »"], "violet", size=11)
    s.box(cx[1], 316, 180, 92, ["GLPI + MariaDB", "127.0.0.1:8081", "tunnel seulement", "inventaire : /front/inventory.php"], "violet", size=11)
    s.box(cx[2], 316, 240, 92, ["RustDesk hbbs / hbbr", "annuaire et relais (option)", "mots de passe dérivés", "par caisse"], "violet", size=11)
    s.box(560, 430, 640, 80, ["/opt/passerelle  ·  passerelle-sync (S3 → serveur, relance ciblée)", "passerelle-sauvegarde (03:30 UTC) · scripts : enseigne-ajouter.sh, glpi-initialiser.sh…", "SSM Agent : reçoit les commandes sans port ouvert"], "gris", size=11)

    # services AWS
    s.box(540, 544, 150, 80, ["Disque de données", "EBS chiffré /srv/data", "instantané quotidien (7 j)"], "ambre", size=11)
    s.box(704, 544, 150, 80, ["S3 « configuration »", "fichiers de supervision/", "serveur/ publiés"], "ambre", size=11)
    s.box(868, 544, 160, 80, ["S3 « sauvegardes »", "chiffré · versionné", "rotation 30 jours"], "ambre", size=11)
    s.box(1042, 544, 178, 80, ["Parameter Store", "mots de passe générés", "(Grafana, GLPI, RustDesk)"], "ambre", size=11)
    s.box(540, 636, 220, 66, ["Systems Manager", "sessions et commandes", "journalisées, sans SSH"], "violet", size=11)
    s.box(776, 636, 200, 66, ["IAM", "rôle du serveur : ses secrets", "et ses buckets seulement"], "violet", size=11)
    s.box(992, 636, 228, 66, ["CloudWatch + Budgets", "récupération automatique", "alerte de coût"], "violet", size=11)

    # --- Administration
    s.zone(20, 660, 480, 170, "Poste d'administration (machine Linux ~/caisse-demo)", "gris")
    s.box(36, 696, 220, 112, ["terraform plan / apply", "tfstate à conserver", "terraform.tfvars (secret)"], "blanc", size=11)
    s.box(268, 696, 220, 112, ["aws cli + plugin Session Manager", "tools/ssm-executer.sh", "tunnels Grafana / GLPI"], "blanc", size=11)
    s.zone(520, 744, 720, 90, "Légende", "gris")
    s.box(540, 776, 120, 40, ["serveur / conteneur"], "violet", size=10, rx=6)
    s.box(676, 776, 120, 40, ["stockage / secret"], "ambre", size=10, rx=6)
    s.box(812, 776, 120, 40, ["côté client"], "bleu", size=10, rx=6)
    s.box(948, 776, 120, 40, ["administration"], "gris", size=10, rx=6)
    s.text(1084, 801, "→ sens du flux", 12, color=MUTED)

    # --- Flèches
    s.arrow([(250, 230), (316, 230)], "HTTPS", fam="bleu", lx=283, ly=221)
    s.arrow([(474, 195), (560, 250)], "443", fam="bleu", lx=514, ly=214)
    s.arrow([(474, 345), (560, 270)], None, fam="bleu")
    s.arrow([(474, 465), (510, 465), (510, 419), (1080, 419), (1080, 410)], "21115-21117", fam="gris", lx=700, ly=425)
    s.arrow([(740, 250), (760, 250)], None, fam="violet")
    s.arrow([(500, 756), (540, 676)], "SSM", fam="gris", lx=508, ly=735)
    s.arrow([(146, 696), (146, 652), (520, 652)], "terraform apply (API AWS)", fam="gris", lx=330, ly=648)
    s.save("infrastructure.svg")


if __name__ == "__main__":
    deploiements()
    infrastructure()
