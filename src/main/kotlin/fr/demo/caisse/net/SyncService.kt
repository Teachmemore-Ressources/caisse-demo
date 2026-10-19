package fr.demo.caisse.net

import fr.demo.caisse.data.Database
import fr.demo.caisse.model.Caissier
import fr.demo.caisse.model.Produit
import fr.demo.caisse.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class EtatReseau { NON_CONFIGURE, EN_LIGNE, HORS_LIGNE }

/**
 * Synchronisation avec le SaaS, en tâche de fond :
 *  - envoi des tickets en attente (file d'attente = tickets.synced = 0), donc la caisse continue
 *    de vendre hors ligne et rattrape son retard au retour du réseau ;
 *  - récupération périodique du référentiel (produits, caissiers).
 */
class SyncService(private val db: Database, private val scope: CoroutineScope) {
    private val _etat = MutableStateFlow(EtatReseau.NON_CONFIGURE)
    val etat: StateFlow<EtatReseau> = _etat
    private val _enAttente = MutableStateFlow(db.nbTicketsNonSynchronises())
    val enAttente: StateFlow<Int> = _enAttente
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    private val _derniereSync = MutableStateFlow("jamais")
    val derniereSync: StateFlow<String> = _derniereSync
    /** Même information en secondes epoch (0 = jamais), pour les métriques de supervision. */
    private val _derniereSyncEpoch = MutableStateFlow(0L)
    val derniereSyncEpoch: StateFlow<Long> = _derniereSyncEpoch
    /** Incrémenté à chaque mise à jour du référentiel : l'interface recharge alors le catalogue. */
    private val _versionCatalogue = MutableStateFlow(0)
    val versionCatalogue: StateFlow<Int> = _versionCatalogue

    private val declencheur = Channel<Boolean>(Channel.CONFLATED) // true = rafraîchir aussi le référentiel
    private var boucle: Job? = null
    private var dernierReferentiel = 0L

    val deviceId: String
        get() = db.config("device_id") ?: UUID.randomUUID().toString().also { db.setConfig("device_id", it) }

    val urlServeur: String get() = db.config("server_url").orEmpty()
    val magasin: String get() = db.config("magasin").orEmpty()
    val estActivee: Boolean get() = !db.config("token").isNullOrBlank() && urlServeur.isNotBlank()

    fun demarrer() {
        boucle = scope.launch(Dispatchers.IO) {
            while (true) {
                cycle()
                // attend 30 s ou un déclenchement manuel / une nouvelle vente
                kotlinx.coroutines.withTimeoutOrNull(30_000) { declencheur.receive() }.let { forcer ->
                    if (forcer == true) dernierReferentiel = 0
                }
            }
        }
    }

    /** À appeler après une vente (envoi rapide) ou depuis l'écran d'administration. */
    fun declencher(rafraichirReferentiel: Boolean = false) {
        declencheur.trySend(rafraichirReferentiel)
    }

    fun rafraichirCompteur() {
        _enAttente.value = db.nbTicketsNonSynchronises()
    }

    private fun cycle() {
        rafraichirCompteur()
        if (!estActivee) {
            _etat.value = EtatReseau.NON_CONFIGURE
            return
        }
        val client = SaasClient(urlServeur, db.config("token"))
        try {
            if (!client.ping()) {
                _etat.value = EtatReseau.HORS_LIGNE
                _message.value = "Serveur injoignable"
                return
            }
            _etat.value = EtatReseau.EN_LIGNE

            val dev = deviceId
            while (true) {
                val lot = db.ticketsNonSynchronises(50)
                if (lot.isEmpty()) break
                val acceptes = client.envoyerTickets(lot.map { it.toDto(dev) })
                db.marquerSynchronises(acceptes)
                rafraichirCompteur()
                if (acceptes.isEmpty()) break
            }

            val maintenant = System.currentTimeMillis()
            if (maintenant - dernierReferentiel > 5 * 60_000L) {
                appliquerReferentiel(client.referentiel())
                dernierReferentiel = maintenant
            }
            _derniereSync.value = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"))
            _derniereSyncEpoch.value = System.currentTimeMillis() / 1000
            _message.value = "Synchronisé"
        } catch (e: SaasException) {
            _etat.value = if (e.status == null) EtatReseau.HORS_LIGNE else EtatReseau.EN_LIGNE
            _message.value = e.message.orEmpty()
        } catch (e: Exception) {
            _message.value = "Erreur de synchronisation : ${e.message}"
        }
    }

    private fun appliquerReferentiel(ref: ReferentielDto) {
        db.remplacerReferentiel(
            ref.produits.map { Produit(it.code, it.nom, it.prixCents, it.tvaBp, it.categorie) },
            ref.caissiers.map { Caissier(it.id, it.nom, runCatching { Role.valueOf(it.role.uppercase()) }.getOrDefault(Role.CAISSIER), it.code) to it.pinSha256 },
        )
        _versionCatalogue.value += 1
    }

    /** Active la caisse avec un code d'enregistrement fourni par l'exploitant (écran « Configuration »). */
    suspend fun activer(url: String, code: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("inconnu")
            val rep = SaasClient(url).activer(code.trim(), deviceId, hostname)
            db.setConfig("server_url", url.trim().trimEnd('/'))
            db.setConfig("token", rep.token)
            db.setConfig("magasin", rep.magasin)
            db.setConfig("tpv", rep.tpv)
            dernierReferentiel = 0
            declencher(true)
            "Caisse activée : ${rep.magasin} / ${rep.tpv}"
        }
    }

    fun desactiver() {
        db.setConfig("token", "")
        _etat.value = EtatReseau.NON_CONFIGURE
    }
}
