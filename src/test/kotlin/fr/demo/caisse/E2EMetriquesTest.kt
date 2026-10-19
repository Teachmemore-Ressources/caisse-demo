package fr.demo.caisse

import fr.demo.caisse.data.Database
import fr.demo.caisse.data.Seed
import fr.demo.caisse.domain.Panier
import fr.demo.caisse.model.ModePaiement
import fr.demo.caisse.model.Paiement
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.net.SyncService
import fr.demo.caisse.util.MetricsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Produit un vrai fichier de mesures avec le code de l'application, pour le test de bout en bout de la supervision
 * (supervision/tests/e2e.sh). Ignoré sauf si E2E_METRICS_DIR est défini.
 */
class E2EMetriquesTest {
    @Test
    fun `ecrit un fichier de mesures realiste`() {
        val dossier = System.getenv("E2E_METRICS_DIR")
        assumeTrue(dossier != null, "E2E_METRICS_DIR non défini : test ignoré")
        val db = Database(Files.createTempDirectory("e2e").resolve("t.db"))
        Seed.appliquerSiVide(db)
        val produits = db.produits()
        repeat(4) { i ->
            val p = Panier().ajouter(produits[i], quantite = i + 1)
            db.enregistrerTicket(
                Ticket("e2e-$i", db.prochainNumero(), "${java.time.LocalDate.now()}T1$i:00:00", "Alice", null, p.lignes, 0, p.totalCents, listOf(Paiement(ModePaiement.CARTE, p.totalCents, 0))),
            )
        }
        db.marquerSynchronises(listOf("e2e-0", "e2e-1")) // 2 tickets acquittés, 2 en attente
        val sync = SyncService(db, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val service = MetricsService(db, sync, CoroutineScope(SupervisorJob() + Dispatchers.Default), Paths.get(dossier, "caisse.prom")) { true }
        assertTrue(service.ecrire())
        db.close()
    }
}
