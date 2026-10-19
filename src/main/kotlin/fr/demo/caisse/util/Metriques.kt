package fr.demo.caisse.util

import fr.demo.caisse.data.AppPaths
import fr.demo.caisse.data.Database
import fr.demo.caisse.net.EtatReseau
import fr.demo.caisse.net.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** État de la caisse à un instant donné, tel que publié pour la supervision. */
data class Instantane(
    val version: String,
    val deviceId: String,
    val magasin: String,
    val tpv: String,
    val demarrageEpoch: Long,
    val maintenantEpoch: Long,
    val ticketsEnAttente: Int,
    val ticketsJour: Int,
    val ventesJourCents: Long,
    val produits: Int,
    val clients: Int,
    /** 1 = en ligne, 0 = hors ligne, -1 = caisse non reliée à un serveur (mode autonome). */
    val etatReseau: Int,
    val derniereSyncEpoch: Long,
    val tailleBaseOctets: Long,
    val sessionOuverte: Boolean,
)

/**
 * Métriques au format texte Prometheus. L'application ne publie aucun port : elle écrit ce fichier et l'agent
 * (Grafana Alloy, collecteur « textfile ») le lit puis l'envoie en sortie vers Prometheus ou Mimir.
 * La métrique d'information porte les identifiants de l'application (`device_id`, `saas_magasin`, `saas_tpv`) ;
 * l'agent ajoute à toutes les séries ses propres étiquettes (`enseigne`, `magasin`, `poste`, `machine`), d'où ces noms distincts.
 */
object Metriques {
    private fun echapper(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    fun rendre(i: Instantane): String = buildString {
        fun serie(nom: String, aide: String, type: String, valeur: Any, etiquettes: String = "") {
            appendLine("# HELP $nom $aide")
            appendLine("# TYPE $nom $type")
            appendLine("$nom$etiquettes $valeur")
        }
        serie(
            "caisse_app_info", "Informations sur l'application de caisse (valeur toujours 1).", "gauge", 1,
            "{version=\"${echapper(i.version)}\",device_id=\"${echapper(i.deviceId)}\",saas_magasin=\"${echapper(i.magasin)}\",saas_tpv=\"${echapper(i.tpv)}\"}",
        )
        serie("caisse_app_last_write_timestamp_seconds", "Heure d'écriture de ce fichier : sert à détecter une application figée ou arrêtée.", "gauge", i.maintenantEpoch)
        serie("caisse_app_start_time_seconds", "Heure de démarrage de l'application.", "gauge", i.demarrageEpoch)
        serie("caisse_tickets_pending", "Tickets enregistrés localement et pas encore acquittés par le serveur.", "gauge", i.ticketsEnAttente)
        serie("caisse_tickets_today", "Nombre de tickets du jour (ventes et retours).", "gauge", i.ticketsJour)
        serie("caisse_sales_today_cents", "Chiffre d'affaires du jour en centimes, retours déduits.", "gauge", i.ventesJourCents)
        serie("caisse_catalog_products", "Nombre de produits dans la base locale.", "gauge", i.produits)
        serie("caisse_catalog_customers", "Nombre de clients dans la base locale.", "gauge", i.clients)
        serie("caisse_sync_state", "État de la liaison avec le serveur : 1 en ligne, 0 hors ligne, -1 mode autonome.", "gauge", i.etatReseau)
        serie("caisse_last_sync_timestamp_seconds", "Heure de la dernière synchronisation réussie (0 = jamais).", "gauge", i.derniereSyncEpoch)
        serie("caisse_db_size_bytes", "Taille du fichier de base de données locale.", "gauge", i.tailleBaseOctets)
        serie("caisse_cashier_session", "1 si un caissier est connecté.", "gauge", if (i.sessionOuverte) 1 else 0)
    }
}

/** Écrit périodiquement les métriques, de façon atomique (écriture dans un fichier temporaire puis renommage). */
class MetricsService(
    private val db: Database,
    private val sync: SyncService,
    private val scope: CoroutineScope,
    private val fichier: Path = emplacement(),
    private val intervalleMs: Long = 30_000,
    private val sessionOuverte: () -> Boolean,
) {
    private val demarrage = System.currentTimeMillis() / 1000

    fun instantane(): Instantane = Instantane(
        version = SystemInfo.VERSION,
        deviceId = sync.deviceId,
        magasin = sync.magasin.ifBlank { "non-active" },
        tpv = db.config("tpv").orEmpty().ifBlank { "non-active" },
        demarrageEpoch = demarrage,
        maintenantEpoch = System.currentTimeMillis() / 1000,
        ticketsEnAttente = db.nbTicketsNonSynchronises(),
        ticketsJour = db.nbTicketsDuJour(),
        ventesJourCents = db.ventesDuJourCents(),
        produits = db.nbProduits(),
        clients = db.nbClients(),
        etatReseau = when (sync.etat.value) {
            EtatReseau.EN_LIGNE -> 1
            EtatReseau.HORS_LIGNE -> 0
            EtatReseau.NON_CONFIGURE -> -1
        },
        derniereSyncEpoch = sync.derniereSyncEpoch.value,
        tailleBaseOctets = runCatching { Files.size(db.path) }.getOrDefault(0L),
        sessionOuverte = sessionOuverte(),
    )

    /** Écrit une fois ; renvoie false (sans lever d'exception) si le dossier n'est pas accessible. */
    fun ecrire(): Boolean = runCatching {
        fichier.parent?.let { Files.createDirectories(it) }
        val tmp = fichier.resolveSibling(fichier.fileName.toString() + ".tmp")
        Files.writeString(tmp, Metriques.rendre(instantane()))
        runCatching { Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")) }
        Files.move(tmp, fichier, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess

    fun demarrer() {
        scope.launch(Dispatchers.IO) {
            var avertit = false
            while (true) {
                if (!ecrire() && !avertit) {
                    System.err.println("Métriques : impossible d'écrire $fichier (supervision désactivée)")
                    avertit = true
                }
                delay(intervalleMs)
            }
        }
    }

    companion object {
        /** CAISSE_METRICS_FILE si défini (le kiosque le place dans /var/lib/storepos-kiosk/metrics), sinon dans les données de la caisse. */
        fun emplacement(): Path =
            System.getenv("CAISSE_METRICS_FILE")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: AppPaths.dataDir.resolve("metrics.prom")
    }
}
