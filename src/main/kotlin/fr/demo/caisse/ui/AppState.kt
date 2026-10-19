package fr.demo.caisse.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.demo.caisse.data.AppPaths
import fr.demo.caisse.data.Database
import fr.demo.caisse.data.Seed
import fr.demo.caisse.domain.Panier
import fr.demo.caisse.model.Caissier
import fr.demo.caisse.model.Client
import fr.demo.caisse.model.LigneTicket
import fr.demo.caisse.model.Paiement
import fr.demo.caisse.model.Produit
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.model.TypeLigne
import fr.demo.caisse.net.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Screen { LOGIN, CAISSE, COMPTAGE, JOURNAL, SYSTEME }

/** Ce que modifie le pavé numérique sur la ligne sélectionnée. */
enum class ModeSaisie(val libelle: String) { QTE("Qté"), REMISE("% Remise"), PRIX("Prix") }

/** Action protégée en attente du code d'un responsable (affichée par la fenêtre d'autorisation). */
class DemandeAutorisation(val libelle: String, val action: () -> Unit)

enum class Niveau { INFO, SUCCES, ERREUR, BLOQUANT }

/** Message affiché en haut de l'écran. `code` aide le support (« E-PRD-001 »). */
data class Message(val niveau: Niveau, val texte: String, val code: String? = null, val id: Long = System.nanoTime())

enum class ChoixImpression { DEMANDER, TICKET, AUCUN }

@Serializable
private data class LigneSauvee(val code: String, val nom: String, val qte: Int, val prix: Long, val tva: Int, val remise: Int, val retour: Boolean)

@Serializable
private data class PanierSauve(val lignes: List<LigneSauvee>, val remise: Long, val clientId: Long?, val retour: Boolean)

/** État global de l'application : écran courant, caissier connecté, ticket en cours, préférences. */
class AppState(val db: Database, val sync: SyncService) {
    /** Vrai quand un caissier est connecté (exposé en métrique de supervision). */
    val sessionOuverte: Boolean get() = caissier != null

    var screen by mutableStateOf(Screen.LOGIN)
        private set
    var caissier by mutableStateOf<Caissier?>(null)
        private set
    var panier by mutableStateOf(Panier())
        private set
    var client by mutableStateOf<Client?>(null)
        private set
    var modeRetour by mutableStateOf(false)
        private set

    /** Ligne du ticket sélectionnée (celle que le pavé numérique modifie). */
    var selection by mutableStateOf<Int?>(null)
        private set
    var modeSaisie by mutableStateOf(ModeSaisie.QTE)
        private set
    /** Chiffres tapés sur le pavé pour la ligne sélectionnée (« 12 », « 3,5 »…). */
    var saisie by mutableStateOf("")
        private set
    var autorisation by mutableStateOf<DemandeAutorisation?>(null)
        private set

    // ---- mise en veille -----------------------------------------------------------------------
    /** Écran de veille affiché. La session et le ticket en cours sont conservés dessous ; le code PIN est redemandé au réveil. */
    var veille by mutableStateOf(false)
        private set
    /** Délai d'inactivité avant la veille, en minutes (0 = jamais). Réglé par le responsable (menu), conservé dans la base. */
    var veilleMinutes by mutableStateOf(db.config("veille_minutes")?.toIntOrNull()?.takeIf { it in Veille.CHOIX } ?: Veille.DEFAUT_MINUTES)
        private set
    @Volatile private var derniereActiviteMs = System.currentTimeMillis()

    var produits by mutableStateOf(db.produits())
        private set
    var clients by mutableStateOf(db.clients())
        private set
    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(db.config("theme").orEmpty()) }.getOrDefault(ThemeMode.SYSTEME))
        private set

    var message by mutableStateOf<Message?>(null)
        private set
    var nbTicketsJour by mutableStateOf(db.nbTicketsDuJour())
        private set
    var dernierTicket by mutableStateOf(db.dernierTicket())
        private set
    var prochainNumero by mutableStateOf(db.prochainNumero())
        private set

    /** « Ne plus demander aujourd'hui » : mémorisé pour la journée seulement. */
    var choixImpression by mutableStateOf(ChoixImpression.DEMANDER)
        private set
    private var jourChoixImpression: LocalDate? = null

    private val json = Json { ignoreUnknownKeys = true }

    // ---- navigation et session ------------------------------------------------------------

    fun connecter(c: Caissier) {
        caissier = c
        restaurerPanier()
        screen = Screen.CAISSE
        rafraichirStats()
    }

    /** À appeler à chaque toucher ou frappe. */
    fun activite() { derniereActiviteMs = System.currentTimeMillis() }

    /** Appelé régulièrement : met en veille si l'inactivité dépasse le délai réglé. */
    fun verifierVeille(maintenantMs: Long = System.currentTimeMillis()) {
        if (!veille && Veille.doitDormir(derniereActiviteMs, maintenantMs, veilleMinutes)) { veille = true; tampon.clear() }
    }

    fun reveiller() { veille = false; activite() }

    fun changerVeille(minutes: Int) {
        if (minutes !in Veille.CHOIX) return
        veilleMinutes = minutes
        db.setConfig("veille_minutes", minutes.toString())
        activite()
    }

    fun verrouiller() {
        sauvegarderPanier()
        selection = null
        saisie = ""
        autorisation = null
        caissier = null
        message = null
        screen = Screen.LOGIN
    }

    fun aller(s: Screen) {
        if (caissier == null) return
        if (s == Screen.CAISSE) rafraichirStats()
        screen = s
    }

    fun rafraichirStats() {
        nbTicketsJour = db.nbTicketsDuJour()
        dernierTicket = db.dernierTicket()
        prochainNumero = db.prochainNumero()
    }

    fun rechargerCatalogue() {
        produits = db.produits()
        clients = db.clients()
    }

    fun changerTheme(m: ThemeMode) {
        themeMode = m
        db.setConfig("theme", m.name)
    }

    // ---- lecteur de codes-barres -----------------------------------------------------------
    // Un lecteur USB se comporte comme un clavier : il « tape » les chiffres très vite puis Entrée.

    /** Vrai quand l'écran de caisse est affiché sans fenêtre ouverte : seul cas où l'on écoute le lecteur. */
    @Volatile var scanActif: Boolean = false
    private val tampon = StringBuilder()
    private var dernierCaractere = 0L

    /** Renvoie true si la touche a été consommée par le lecteur de codes-barres. */
    fun toucheClavier(caractere: Char?, entree: Boolean): Boolean {
        if (veille) { tampon.clear(); return true }          // en veille, un scan ou une frappe ne doit rien ajouter au ticket
        if (!scanActif || screen != Screen.CAISSE) { tampon.clear(); return false }
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierCaractere > 300) tampon.clear() // une frappe humaine est bien plus lente qu'un lecteur
        dernierCaractere = maintenant
        if (entree) {
            val code = tampon.toString()
            tampon.clear()
            if (code.length >= 4) { scanner(code); return true }
            return false
        }
        if (caractere != null && caractere.isLetterOrDigit()) tampon.append(caractere)
        return false
    }

    // ---- messages ------------------------------------------------------------------------

    fun notifier(niveau: Niveau, texte: String, code: String? = null) {
        message = Message(niveau, texte, code)
    }

    fun fermerMessage() {
        message = null
    }

    // ---- ticket en cours ------------------------------------------------------------------

    private fun appliquer(p: Panier) {
        panier = p
        sauvegarderPanier()
    }

    fun ajouter(p: Produit, quantite: Int = 1) {
        appliquer(panier.ajouter(p, modeRetour, quantite))
        selection = panier.indexDe(p, modeRetour).takeIf { it >= 0 }
        saisie = ""
    }

    /** Code lu par le lecteur de codes-barres (il « tape » le code puis Entrée). */
    fun scanner(code: String) {
        val p = produits.firstOrNull { it.code == code.trim() }
        if (p == null) notifier(Niveau.ERREUR, "Code ${code.trim()} inconnu", "E-PRD-001")
        else ajouter(p)
    }

    fun changerQuantite(i: Int, delta: Int) = appliquer(panier.changerQuantite(i, delta))
    fun remiseLigne(i: Int, pct: Int) = appliquer(panier.remiseLigne(i, pct))
    fun supprimerLigne(i: Int) {
        appliquer(panier.supprimer(i))
        selection = panier.lignes.lastIndex.takeIf { it >= 0 }
        saisie = ""
    }
    fun remiseTicket(cents: Long) = appliquer(panier.avecRemiseTicket(cents))
    fun viderPanier() {
        client = null
        selection = null
        saisie = ""
        appliquer(Panier())
    }

    // ---- pavé numérique (façon caisse : Qté / % Remise / Prix / ±) --------------------------

    fun selectionner(i: Int?) {
        selection = i
        saisie = ""
    }

    fun exigerResponsable(libelle: String, action: () -> Unit) {
        if (caissier?.estResponsable == true) action() else autorisation = DemandeAutorisation(libelle, action)
    }

    fun fermerAutorisation() {
        autorisation = null
    }

    fun choisirModeSaisie(m: ModeSaisie) {
        if (m == ModeSaisie.PRIX && caissier?.estResponsable != true) {
            exigerResponsable("Modifier le prix d'une ligne") { modeSaisie = ModeSaisie.PRIX; saisie = "" }
            return
        }
        modeSaisie = m
        saisie = ""
    }

    private fun centimes(texte: String): Long {
        val s = texte.replace('.', ',')
        val entier = s.substringBefore(',').toLongOrNull() ?: 0L
        val dec = s.substringAfter(',', "").padEnd(2, '0').take(2).toLongOrNull() ?: 0L
        return entier * 100 + dec
    }

    private fun appliquerSaisie(i: Int) {
        when (modeSaisie) {
            ModeSaisie.QTE -> saisie.toIntOrNull()?.let { appliquer(panier.fixerQuantite(i, it)) }
            ModeSaisie.REMISE -> {
                val pct = (saisie.toIntOrNull() ?: 0).coerceIn(0, 100)
                if (pct > 20 && caissier?.estResponsable != true) {
                    saisie = ""
                    exigerResponsable("Remise de $pct % sur une ligne") { appliquer(panier.remiseLigne(i, pct)) }
                } else appliquer(panier.remiseLigne(i, pct))
            }
            ModeSaisie.PRIX -> if (saisie.isNotEmpty()) appliquer(panier.fixerPrix(i, centimes(saisie)))
        }
    }

    fun toucheNumerique(c: String) {
        val i = selection?.takeIf { it in panier.lignes.indices }
        if (i == null) {
            notifier(Niveau.INFO, "Touchez un article du ticket pour le modifier")
            return
        }
        when (modeSaisie) {
            ModeSaisie.QTE, ModeSaisie.REMISE -> {
                if (c == ",") return
                val s = (saisie + c).trimStart('0').take(if (modeSaisie == ModeSaisie.QTE) 4 else 3)
                if (s.isEmpty()) return
                if (modeSaisie == ModeSaisie.REMISE && (s.toIntOrNull() ?: 0) > 100) return
                saisie = s
            }
            ModeSaisie.PRIX -> {
                if (c == "," && saisie.contains(',')) return
                if (saisie.contains(',') && saisie.substringAfter(',').length >= 2) return
                saisie = if (saisie.isEmpty() && c == ",") "0," else saisie + c
            }
        }
        appliquerSaisie(i)
    }

    /** ⌫ : efface un chiffre ; si rien n'est en cours de saisie, retire la ligne (Qté) ou la remise (% Remise). */
    fun toucheEffacer() {
        val i = selection?.takeIf { it in panier.lignes.indices } ?: return
        if (saisie.isNotEmpty()) {
            saisie = saisie.dropLast(1)
            appliquerSaisie(i) // recalcule avec les chiffres restants (Qté et Prix : inchangé s'il n'en reste aucun)
            return
        }
        when (modeSaisie) {
            ModeSaisie.QTE -> supprimerLigne(i)
            ModeSaisie.REMISE -> appliquer(panier.remiseLigne(i, 0))
            ModeSaisie.PRIX -> {}
        }
    }

    /** ± : bascule la ligne entre vente et retour (réservé au responsable). */
    fun toucheSigne() {
        val i = selection?.takeIf { it in panier.lignes.indices } ?: return
        exigerResponsable("Basculer la ligne en retour") { appliquer(panier.basculerRetour(i)); saisie = "" }
    }

    fun choisirClient(c: Client?) {
        client = c
        sauvegarderPanier()
    }

    fun activerRetour(actif: Boolean) {
        modeRetour = actif
        sauvegarderPanier()
    }

    // ---- sauvegarde du ticket en cours (coupure de courant, plantage) ----------------------

    private fun sauvegarderPanier() {
        val s = if (panier.estVide && client == null) ""
        else json.encodeToString(
            PanierSauve(
                panier.lignes.map { LigneSauvee(it.code, it.nom, it.qte, it.prixCents, it.tvaBp, it.remisePct, it.type == TypeLigne.RETOUR) },
                panier.remiseCents, client?.id, modeRetour,
            ),
        )
        db.setConfig("panier_en_cours", s)
    }

    private fun restaurerPanier() {
        val brut = db.config("panier_en_cours").orEmpty()
        if (brut.isBlank()) return
        runCatching { json.decodeFromString<PanierSauve>(brut) }.onSuccess { s ->
            panier = Panier(
                s.lignes.map { LigneTicket(it.code, it.nom, it.qte, it.prix, it.tva, it.remise, if (it.retour) TypeLigne.RETOUR else TypeLigne.PRODUIT) },
                s.remise,
            )
            client = clients.firstOrNull { it.id == s.clientId }
            modeRetour = s.retour
            selection = panier.lignes.lastIndex.takeIf { it >= 0 }
            if (!panier.estVide) notifier(Niveau.INFO, "Ticket en cours restauré (${panier.nbArticles} article(s))")
        }
    }

    // ---- encaissement ----------------------------------------------------------------------

    fun definirChoixImpression(c: ChoixImpression) {
        choixImpression = c
        jourChoixImpression = LocalDate.now()
    }

    /** Le choix « ne plus demander » ne vaut que pour la journée. */
    fun choixImpressionValide(): ChoixImpression {
        if (choixImpression != ChoixImpression.DEMANDER && jourChoixImpression != LocalDate.now()) choixImpression = ChoixImpression.DEMANDER
        return choixImpression
    }

    /** Enregistre la vente (règlements déjà validés), vide le panier et déclenche l'envoi au serveur. */
    fun finaliser(paiements: List<Paiement>, retourDe: Int? = null): Ticket {
        val p = panier
        val t = Ticket(
            uuid = UUID.randomUUID().toString(),
            numero = db.prochainNumero(),
            dateIso = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            caissier = caissier?.nom ?: "?",
            client = client,
            lignes = p.lignes,
            remiseCents = p.remiseEffectiveCents,
            totalCents = p.totalCents,
            paiements = paiements,
            retourDe = retourDe,
        )
        db.enregistrerTicket(t)
        panier = Panier()
        client = null
        modeRetour = false
        selection = null
        saisie = ""
        sauvegarderPanier()
        rafraichirStats()
        sync.rafraichirCompteur()
        sync.declencher()
        return t
    }

    /** Ticket de retour complet d'un ticket existant (action réservée au responsable). */
    fun retourTicket(origine: Ticket): Ticket {
        val lignes = origine.lignes.map { l -> l.copy(type = if (l.type == TypeLigne.RETOUR) TypeLigne.PRODUIT else TypeLigne.RETOUR) }
        // Le retour rembourse exactement ce qui a été payé : lignes inversées + remise ticket rendue.
        val total = lignes.sumOf { it.totalCents } + if (origine.totalCents >= 0) origine.remiseCents else 0L
        val modeRemboursement = origine.paiements.singleOrNull()?.mode ?: fr.demo.caisse.model.ModePaiement.ESPECES
        val t = Ticket(
            uuid = UUID.randomUUID().toString(),
            numero = db.prochainNumero(),
            dateIso = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            caissier = caissier?.nom ?: "?",
            client = origine.client,
            lignes = lignes,
            remiseCents = 0,
            totalCents = total,
            paiements = listOf(Paiement(modeRemboursement, total)),
            retourDe = origine.numero,
        )
        db.enregistrerTicket(t)
        rafraichirStats()
        sync.rafraichirCompteur()
        sync.declencher()
        return t
    }

    fun fermer() {
        sauvegarderPanier()
        db.close()
    }

    companion object {
        fun creer(scope: CoroutineScope): AppState {
            val db = Database(AppPaths.dbFile)
            Seed.appliquerSiVide(db)
            val sync = SyncService(db, scope)
            sync.demarrer()
            val etat = AppState(db, sync)
            fr.demo.caisse.util.MetricsService(db, sync, scope) { etat.sessionOuverte }.demarrer()
            return etat
        }
    }
}
