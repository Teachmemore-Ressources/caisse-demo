package fr.demo.caisse

import fr.demo.caisse.data.Database
import fr.demo.caisse.data.Seed
import fr.demo.caisse.domain.Panier
import fr.demo.caisse.model.ModePaiement
import fr.demo.caisse.model.Paiement
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.net.SyncService
import fr.demo.caisse.util.Instantane
import fr.demo.caisse.util.Metriques
import fr.demo.caisse.util.MetricsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetriquesTest {
    private val exemple = Instantane(
        version = "1.2.0", deviceId = "abc-123", magasin = "Magasin \"Démo\" Lyon", tpv = "Caisse 1",
        demarrageEpoch = 1_700_000_000, maintenantEpoch = 1_700_000_060, ticketsEnAttente = 3, ticketsJour = 12,
        ventesJourCents = 45_678, produits = 25, clients = 6, etatReseau = 1, derniereSyncEpoch = 1_700_000_050,
        tailleBaseOctets = 8192, sessionOuverte = true,
    )

    @Test
    fun `le format est valide et complet`() {
        val texte = Metriques.rendre(exemple)
        assertTrue(texte.endsWith("\n"))
        // chaque série a son HELP et son TYPE, une seule fois
        val noms = Regex("^# TYPE (\\w+) gauge$", RegexOption.MULTILINE).findAll(texte).map { it.groupValues[1] }.toList()
        assertEquals(noms.toSet().size, noms.size)
        for (n in noms) {
            assertTrue("# HELP $n " in texte, "HELP manquant pour $n")
            assertTrue(Regex("^$n(\\{.*\\})? \\S+$", RegexOption.MULTILINE).containsMatchIn(texte), "valeur manquante pour $n")
        }
        assertTrue("caisse_tickets_pending 3" in texte)
        assertTrue("caisse_sales_today_cents 45678" in texte)
        assertTrue("caisse_cashier_session 1" in texte)
        // l'échappement des guillemets protège le format
        assertTrue("saas_magasin=\"Magasin \\\"Démo\\\" Lyon\"" in texte)
        assertFalse(texte.lines().any { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("caisse_") })
    }

    @Test
    fun `le service ecrit un fichier atomique avec les vraies valeurs de la base`() {
        val dir = Files.createTempDirectory("metriques")
        val db = Database(dir.resolve("t.db"))
        Seed.appliquerSiVide(db)
        val p = Panier().ajouter(db.produits().first())
        db.enregistrerTicket(Ticket("m-1", db.prochainNumero(), "${java.time.LocalDate.now()}T10:00:00", "Alice", null, p.lignes, 0, p.totalCents, listOf(Paiement(ModePaiement.CARTE, p.totalCents, 0))))

        val sync = SyncService(db, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val fichier = dir.resolve("sortie").resolve("caisse.prom")
        val service = MetricsService(db, sync, CoroutineScope(SupervisorJob() + Dispatchers.Default), fichier) { false }
        assertTrue(service.ecrire())
        val lu = Files.readString(fichier)
        assertTrue("caisse_tickets_pending 1" in lu)
        assertTrue("caisse_tickets_today 1" in lu)
        assertTrue("caisse_sync_state -1" in lu, "caisse non activée : mode autonome")
        assertTrue("caisse_cashier_session 0" in lu)
        assertFalse(Files.exists(fichier.resolveSibling("caisse.prom.tmp")), "pas de fichier temporaire laissé")
        db.close()
    }

    @Test
    fun `un dossier inaccessible ne fait pas planter l'application`() {
        val dir = Files.createTempDirectory("metriques")
        val db = Database(dir.resolve("t.db"))
        Seed.appliquerSiVide(db)
        val sync = SyncService(db, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        // le « dossier » parent est un fichier : la création échoue
        val bloc = Files.writeString(dir.resolve("fichier"), "x")
        val service = MetricsService(db, sync, CoroutineScope(SupervisorJob() + Dispatchers.Default), bloc.resolve("sous").resolve("c.prom")) { false }
        assertFalse(service.ecrire())
        db.close()
    }
}
