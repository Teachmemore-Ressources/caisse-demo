package fr.demo.caisse

import fr.demo.caisse.data.Database
import fr.demo.caisse.data.Seed
import fr.demo.caisse.domain.Panier
import fr.demo.caisse.model.ModePaiement
import fr.demo.caisse.model.Paiement
import fr.demo.caisse.model.Produit
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.net.SaasClient
import fr.demo.caisse.net.SaasException
import fr.demo.caisse.net.SyncService
import fr.demo.caisse.net.toDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test d'intégration contre le serveur simulé. Lancé seulement si MOCK_SAAS_URL est défini :
 *   python3 saas-mock/mock_saas.py --port 8099 &
 *   MOCK_SAAS_URL=http://localhost:8099 ./gradlew test
 */
class SaasIntegrationTest {
    private val url = System.getenv("MOCK_SAAS_URL")

    @Test
    fun `activation referentiel et envoi idempotent des tickets`() {
        assumeTrue(url != null, "MOCK_SAAS_URL non défini : test d'intégration ignoré")
        val anonyme = SaasClient(url)
        assertTrue(anonyme.ping())
        assertFailsWith<SaasException> { anonyme.activer("MAUVAIS-CODE", "dev-test", "test") }

        val rep = anonyme.activer("DEMO-0001", "dev-test", "test")
        val client = SaasClient(url, rep.token)
        val ref = client.referentiel()
        assertTrue(ref.produits.isNotEmpty())
        assertTrue(ref.caissiers.any { it.role == "RESPONSABLE" })

        val p = Panier().ajouter(Produit("1", "Eau", 120, 550, "B"))
        val t = Ticket("it-${System.nanoTime()}", 1, "2026-10-17T10:00:00", "Alice", null, p.lignes, 0, 120, listOf(Paiement(ModePaiement.CARTE, 120, 0)))
        val dto = t.toDto("dev-test")
        assertEquals(listOf(t.uuid), client.envoyerTickets(listOf(dto)))
        assertEquals(listOf(t.uuid), client.envoyerTickets(listOf(dto))) // renvoi : toujours accusé, pas dupliqué
        assertFailsWith<SaasException> { SaasClient(url, "jeton-bidon").referentiel() }
    }

    @Test
    fun `SyncService vide la file hors ligne et recupere le referentiel`() = runBlocking {
        assumeTrue(url != null, "MOCK_SAAS_URL non défini : test d'intégration ignoré")
        val db = Database(Files.createTempDirectory("caisse-sync").resolve("t.db"))
        Seed.appliquerSiVide(db)
        val p = Panier().ajouter(Produit("1", "Eau", 120, 550, "B"))
        db.enregistrerTicket(Ticket("sync-${System.nanoTime()}", db.prochainNumero(), "2026-10-17T10:00:00", "Alice", null, p.lignes, 0, 120, listOf(Paiement(ModePaiement.ESPECES, 120, 80))))
        assertEquals(1, db.nbTicketsNonSynchronises())

        val sync = SyncService(db, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val r = sync.activer(url, "DEMO-0002")
        assertTrue(r.isSuccess, r.exceptionOrNull()?.message)
        sync.demarrer()

        repeat(40) { if (db.nbTicketsNonSynchronises() > 0 || sync.versionCatalogue.value == 0) Thread.sleep(250) }
        assertEquals(0, db.nbTicketsNonSynchronises(), "le ticket en attente doit être envoyé")
        assertTrue(sync.versionCatalogue.value >= 1, "le référentiel doit être mis à jour")
        assertNotNull(db.verifierPin("4321"), "le caissier du serveur (PIN 4321) doit exister")
        db.close()
    }
}
