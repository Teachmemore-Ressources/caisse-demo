package fr.demo.caisse.data

import java.nio.file.Path
import java.nio.file.Paths

/** Emplacements des données de la caisse (XDG : ~/.local/share/caisse-demo par défaut). */
object AppPaths {
    val dataDir: Path by lazy {
        System.getenv("CAISSE_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it, "caisse-demo") }
            ?: Paths.get(System.getProperty("user.home"), ".local", "share", "caisse-demo")
    }
    val dbFile: Path get() = dataDir.resolve("caisse.db")
    val ticketsDir: Path get() = dataDir.resolve("tickets")
}
