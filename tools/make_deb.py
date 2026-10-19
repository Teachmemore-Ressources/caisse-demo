#!/usr/bin/env python3
"""Assemble caisse-demo_<version>_amd64.deb à partir de l'image d'application Compose
(build/compose/binaries/main/app/caisse-demo). Sans dpkg-deb ni fakeroot : à lancer sous Linux
(les droits d'exécution des fichiers sont lus depuis le disque).

    python3 tools/make_deb.py <dossier app-image> <version> <fichier .deb de sortie>
"""
import hashlib
import io
import lzma
import os
import stat
import sys
import tarfile
import time
from pathlib import Path

PACKAGE = "caisse-demo"
INSTALL_DIR = "opt/caisse-demo"
MTIME = int(time.time())

DEPENDS = (
    "libc6, libgcc-s1, libstdc++6, libgl1, libx11-6, libxext6, libxi6, libxrender1, libxtst6, "
    "libfreetype6, libfontconfig1, libpng16-16, zlib1g, xdg-utils"
)

# Raccourci : l'icône est celle de ce projet (installée dans le thème hicolor), jamais celle d'un autre logiciel.
DESKTOP = """[Desktop Entry]
Version=1.0
Type=Application
Name=Caisse Démo
GenericName=Caisse enregistreuse
Comment=Caisse enregistreuse tactile de démonstration
Exec=/opt/caisse-demo/bin/caisse-demo
Icon=caisse-demo
Terminal=false
Categories=Office;Finance;
Keywords=caisse;pos;vente;ticket;
StartupWMClass=fr-demo-caisse-MainKt
StartupNotify=true
"""

HERE = Path(__file__).resolve().parent
ICON_SIZES = (32, 48, 64, 128, 256, 512)


def ti(name, kind=tarfile.REGTYPE, mode=0o644, size=0, link=""):
    t = tarfile.TarInfo(name)
    t.type, t.mode, t.size, t.mtime, t.linkname = kind, mode, size, MTIME, link
    t.uid = t.gid = 0
    t.uname = t.gname = "root"
    return t


def tar_xz(add) -> bytes:
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.GNU_FORMAT) as tar:
        add(tar)
    return lzma.compress(raw.getvalue(), preset=6)


def ar_member(name: str, data: bytes) -> bytes:
    head = f"{name:<16}{MTIME:<12}{0:<6}{0:<6}{'100644':<8}{len(data):<10}`\n".encode()
    assert len(head) == 60
    return head + data + (b"\n" if len(data) % 2 else b"")


def main(app_dir: str, version: str, out: str):
    root = Path(app_dir)
    if not (root / "bin" / "caisse-demo").exists():
        sys.exit(f"Image d'application introuvable dans {root}")

    md5s, total = [], 0
    dirs = set()
    files = []  # (chemin dans le deb, source, mode, est_lien)

    for src in sorted(root.rglob("*")):
        rel = f"{INSTALL_DIR}/{src.relative_to(root).as_posix()}"
        st = src.lstat()
        if stat.S_ISDIR(st.st_mode):
            dirs.add(rel)
        else:
            files.append((rel, src, 0o755 if st.st_mode & 0o111 else 0o644, stat.S_ISLNK(st.st_mode)))
    extra_dirs = {"opt", INSTALL_DIR, "usr", "usr/bin", "usr/share", "usr/share/applications", "usr/share/pixmaps", "usr/share/icons", "usr/share/icons/hicolor"}
    for n in ICON_SIZES:
        extra_dirs |= {f"usr/share/icons/hicolor/{n}x{n}", f"usr/share/icons/hicolor/{n}x{n}/apps"}

    def add_data(tar):
        for d in sorted(extra_dirs | dirs | {"opt", "usr"}):
            tar.addfile(ti("./" + d, tarfile.DIRTYPE, 0o755))
        nonlocal total
        for rel, src, mode, is_link in files:
            if is_link:
                tar.addfile(ti("./" + rel, tarfile.SYMTYPE, 0o777, link=os.readlink(src)))
                continue
            data = src.read_bytes()
            total += len(data)
            md5s.append(f"{hashlib.md5(data).hexdigest()}  {rel}\n")
            tar.addfile(ti("./" + rel, mode=mode, size=len(data)), io.BytesIO(data))
        desk = DESKTOP.encode()
        tar.addfile(ti("./usr/share/applications/caisse-demo.desktop", size=len(desk)), io.BytesIO(desk))
        md5s.append(f"{hashlib.md5(desk).hexdigest()}  usr/share/applications/caisse-demo.desktop\n")
        tar.addfile(ti("./usr/bin/caisse-demo", tarfile.SYMTYPE, 0o777, link="/opt/caisse-demo/bin/caisse-demo"))
        # Icônes du projet (packaging/icons) dans le thème hicolor + pixmaps
        for n in ICON_SIZES:
            f = HERE.parent / "packaging" / "icons" / f"caisse-demo-{n}.png"
            if not f.exists():
                sys.exit(f"Icône manquante : {f}")
            png = f.read_bytes()
            rel = f"usr/share/icons/hicolor/{n}x{n}/apps/caisse-demo.png"
            tar.addfile(ti("./" + rel, size=len(png)), io.BytesIO(png))
            md5s.append(f"{hashlib.md5(png).hexdigest()}  {rel}\n")
            if n == 128:
                tar.addfile(ti("./usr/share/pixmaps/caisse-demo.png", size=len(png)), io.BytesIO(png))
                md5s.append(f"{hashlib.md5(png).hexdigest()}  usr/share/pixmaps/caisse-demo.png\n")

    data_tar = tar_xz(add_data)

    control = (
        f"Package: {PACKAGE}\nVersion: {version}\nSection: misc\nPriority: optional\nArchitecture: amd64\n"
        f"Installed-Size: {total // 1024}\nDepends: {DEPENDS}\nMaintainer: Formation Linux <formation@example.com>\n"
        "Recommends: xdg-utils\n"
        "Description: Caisse enregistreuse de démonstration (maquette)\n"
        " Application de caisse tactile (Kotlin / Compose Desktop) : connexion par PIN, catalogue,\n"
        " panier, TVA, paiement espèces/carte (simulé), ticket, clôture, synchronisation avec un SaaS\n"
        " et vente hors ligne. Maquette de démonstration, non destinée à un usage commercial.\n"
    )

    def add_control(tar):
        for name, body in (("control", control), ("md5sums", "".join(md5s))):
            b = body.encode()
            tar.addfile(ti("./" + name, size=len(b)), io.BytesIO(b))
        # scripts du mainteneur (raccourci bureau, caches d'icônes) : tools/deb/postinst et postrm
        for name in ("postinst", "postrm"):
            b = (HERE / "deb" / name).read_bytes().replace(b"\r\n", b"\n")
            tar.addfile(ti("./" + name, mode=0o755, size=len(b)), io.BytesIO(b))

    control_tar = tar_xz(add_control)

    deb = b"!<arch>\n" + ar_member("debian-binary", b"2.0\n")
    deb += ar_member("control.tar.xz", control_tar) + ar_member("data.tar.xz", data_tar)
    Path(out).write_bytes(deb)
    print(f"OK : {out} ({len(deb) / 1e6:.1f} Mo, {len(files)} fichiers, {total / 1e6:.0f} Mo installés)")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
