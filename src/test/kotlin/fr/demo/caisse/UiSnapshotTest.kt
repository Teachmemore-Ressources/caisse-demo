@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package fr.demo.caisse

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import fr.demo.caisse.data.Database
import fr.demo.caisse.data.Seed
import fr.demo.caisse.model.ModePaiement
import fr.demo.caisse.model.Paiement
import fr.demo.caisse.model.TypeComptage
import fr.demo.caisse.net.SyncService
import fr.demo.caisse.ui.AppState
import fr.demo.caisse.ui.AppTheme
import fr.demo.caisse.ui.CaisseScreen
import fr.demo.caisse.ui.ComptageScreen
import fr.demo.caisse.ui.DialogueImpression
import fr.demo.caisse.ui.EcranPaiement
import fr.demo.caisse.ui.MenuLateral
import androidx.compose.foundation.layout.Box
import fr.demo.caisse.ui.DialogueRemise
import fr.demo.caisse.ui.JournalScreen
import fr.demo.caisse.ui.LoginScreen
import fr.demo.caisse.ui.PanneauRechercheClients
import fr.demo.caisse.ui.PanneauRechercheProduits
import fr.demo.caisse.ui.Screen
import fr.demo.caisse.ui.SystemeScreen
import fr.demo.caisse.ui.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rend chaque écran en PNG sans fenêtre (build/ui-snapshots) pour contrôler l'interface. */
class UiSnapshotTest {
    private val sortie: Path = Paths.get("build", "ui-snapshots").also { Files.createDirectories(it) }

    private fun etat(): AppState {
        val db = Database(Files.createTempDirectory("snap").resolve("t.db"))
        Seed.appliquerSiVide(db)
        return AppState(db, SyncService(db, CoroutineScope(SupervisorJob() + Dispatchers.Default)))
    }

    private fun capturer(nom: String, theme: ThemeMode = ThemeMode.CLAIR, largeur: Int = 1280, hauteur: Int = 800, contenu: @Composable () -> Unit) {
        val scene = ImageComposeScene(largeur, hauteur, Density(1f)) { AppTheme(theme) { contenu() } }
        try {
            scene.render(0)
            scene.render(100_000_000)
            val octets = scene.render(200_000_000).encodeToData()!!.bytes
            Files.write(sortie.resolve("$nom.png"), octets)
            assertTrue(octets.size > 2000, "capture $nom vide ?")
        } finally {
            scene.close()
        }
    }

    private fun caisseAvecTicket(): AppState {
        val s = etat()
        s.connecter(s.db.caissiers().first { it.estResponsable })
        listOf("3000003", "3000001", "3000011", "3000013", "3000043").forEach { c -> s.ajouter(s.produits.first { it.code == c }) }
        s.ajouter(s.produits.first { it.code == "3000003" })
        s.remiseLigne(1, 10)
        s.choisirClient(s.clients.first())
        s.aller(Screen.CAISSE)
        return s
    }

    @Test
    fun `capture des ecrans`() {
        capturer("01-login") { LoginScreen(etat()) }
        capturer("01-login-compact", largeur = 1024, hauteur = 768) { LoginScreen(etat()) }
        val e = etat()
        capturer("01-login-pin") { LoginScreen(e, e.db.caissiers().first()) }
        capturer("01-login-pin-compact", largeur = 1024, hauteur = 768) { LoginScreen(e, e.db.caissiers().first { it.estResponsable }) }

        val s = caisseAvecTicket()
        capturer("12-veille") { fr.demo.caisse.ui.VeilleScreen(s) }
        capturer("12-veille-code") { fr.demo.caisse.ui.VeilleScreen(s, demarrerParLeCode = true) }
        s.selectionner(1)
        capturer("02-caisse") { CaisseScreen(s) }
        capturer("02-caisse-produits") { CaisseScreen(s, "Boissons") }
        capturer("02-caisse-sombre", ThemeMode.SOMBRE) { CaisseScreen(s, "Boulangerie") }
        capturer("02-caisse-1024x768", largeur = 1024, hauteur = 768) { CaisseScreen(s, "Boissons") }
        capturer("03-menu") { Box { CaisseScreen(s); MenuLateral(s) {} } }
        capturer("04-recherche-produits") { PanneauRechercheProduits(s) {} }
        capturer("05-recherche-clients") { PanneauRechercheClients(s) {} }
        capturer("06-remise") { DialogueRemise(s, {}, {}) }
        capturer("07-paiement") { EcranPaiement(s, {}, {}) }
        capturer("07-paiement-mixte") { EcranPaiement(s, {}, {}, listOf(ModePaiement.ESPECES, ModePaiement.CARTE)) }
        capturer("07-paiement-1024x768", largeur = 1024, hauteur = 768) { EcranPaiement(s, {}, {}, listOf(ModePaiement.ESPECES)) }

        val total = s.panier.totalCents
        val t = s.finaliser(listOf(Paiement(ModePaiement.ESPECES, 1000, 0), Paiement(ModePaiement.CARTE, total - 1000, 0)))
        assertEquals(total, t.totalCents)
        val t2 = s.run { ajouter(produits.first()); finaliser(listOf(Paiement(ModePaiement.ESPECES, panier.totalCents, 380))) }
        capturer("08-impression") { DialogueImpression(s, t2) {} }

        s.db.enregistrerComptage(TypeComptage.OUVERTURE, "Bruno", 15_000, 0, 15_000, "")
        s.aller(Screen.COMPTAGE)
        capturer("09-comptage") { ComptageScreen(s) }
        s.aller(Screen.JOURNAL)
        capturer("10-journal") { JournalScreen(s) }
        capturer("10-journal-sombre", ThemeMode.SOMBRE) { JournalScreen(s) }
        s.aller(Screen.SYSTEME)
        capturer("11-systeme") { SystemeScreen(s) }
    }
}
