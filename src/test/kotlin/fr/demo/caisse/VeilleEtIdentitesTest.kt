package fr.demo.caisse

import fr.demo.caisse.data.Database
import fr.demo.caisse.data.Seed
import fr.demo.caisse.data.hashPin
import fr.demo.caisse.model.Caissier
import fr.demo.caisse.model.Role
import fr.demo.caisse.net.SyncService
import fr.demo.caisse.ui.AppState
import fr.demo.caisse.ui.Veille
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VeilleEtIdentitesTest {
    private fun base() = Database(Files.createTempDirectory("veille").resolve("t.db"))
    private fun etat(db: Database = base().also { Seed.appliquerSiVide(it) }) =
        AppState(db, SyncService(db, CoroutineScope(SupervisorJob() + Dispatchers.Default)))

    // ---- règle de mise en veille ----
    @Test
    fun `la veille se declenche apres le delai, jamais avant, et jamais si desactivee`() {
        val t0 = 1_000_000L
        assertFalse(Veille.doitDormir(t0, t0 + 4 * 60_000 + 59_999, 5))
        assertTrue(Veille.doitDormir(t0, t0 + 5 * 60_000, 5))
        assertFalse(Veille.doitDormir(t0, t0 + 24 * 3_600_000L, 0), "0 = jamais")
    }

    @Test
    fun `la veille conserve la session et le ticket`() {
        val s = etat()
        s.connecter(s.db.caissiers().first { !it.estResponsable })
        s.ajouter(s.produits.first())
        val avant = s.panier.totalCents
        s.verifierVeille(System.currentTimeMillis() + 6 * 60_000L)
        assertTrue(s.veille, "5 min d'inactivite -> veille")
        assertNotNull(s.caissier, "la session reste ouverte dessous")
        assertEquals(avant, s.panier.totalCents, "le ticket en cours est conserve")
        s.reveiller()
        assertFalse(s.veille)
    }

    @Test
    fun `une activite repousse la veille`() {
        val s = etat()
        s.activite()
        s.verifierVeille(System.currentTimeMillis() + 4 * 60_000L)
        assertFalse(s.veille)
    }

    @Test
    fun `en veille un scan ne s'ajoute pas au ticket`() {
        val s = etat()
        s.connecter(s.db.caissiers().first())
        s.verifierVeille(System.currentTimeMillis() + 10 * 60_000L)
        assertTrue(s.veille)
        "3000001".forEach { assertTrue(s.toucheClavier(it, false)) }
        assertTrue(s.toucheClavier(null, true))
        assertTrue(s.panier.lignes.isEmpty(), "rien ajoute pendant la veille")
    }

    @Test
    fun `le delai de veille est reglable, memorise et borne aux choix proposes`() {
        val db = base().also { Seed.appliquerSiVide(it) }
        val s = etat(db)
        assertEquals(Veille.DEFAUT_MINUTES, s.veilleMinutes)
        s.changerVeille(10)
        assertEquals(10, etat(db).veilleMinutes, "memorise dans la base")
        s.changerVeille(7)
        assertEquals(10, s.veilleMinutes, "valeur hors choix ignoree")
        s.changerVeille(0)
        s.verifierVeille(System.currentTimeMillis() + 10 * 3_600_000L)
        assertFalse(s.veille, "0 = jamais")
    }

    // ---- noms de code ----
    @Test
    fun `l'ecran de connexion affiche des noms de code, pas les identites`() {
        val cs = etat().db.caissiers()
        assertEquals(listOf("Manager", "Caissier 01", "Caissier 02"), cs.map { it.libelleConnexion })
        assertEquals(listOf("Armel NGANDO", "Alice M.", "Chloé D."), cs.map { it.nom }, "l'identite reste connue du systeme")
        assertTrue(cs.none { it.libelleConnexion.contains("NGANDO") || it.libelleConnexion.contains("Alice") })
    }

    @Test
    fun `sans code fourni par le serveur le libelle par defaut est neutre`() {
        assertEquals("Manager", Caissier(9, "Jean Dupont", Role.RESPONSABLE).libelleConnexion)
        assertEquals("Caissier 07", Caissier(7, "Jean Dupont", Role.CAISSIER).libelleConnexion)
        assertEquals("Orion", Caissier(7, "Jean Dupont", Role.CAISSIER, "Orion").libelleConnexion)
    }

    @Test
    fun `une base creee par l'ancienne version recoit les nouveaux noms et garde ses codes PIN`() {
        val db = base()
        // base déjà alimentée par l'ancienne version (produits présents : le jeu de démonstration n'est PAS rechargé)
        db.remplacerReferentiel(
            listOf(fr.demo.caisse.model.Produit("9", "Produit", 100, 550, "Divers")),
            listOf(
                Caissier(1, "Alice Martin", Role.CAISSIER) to hashPin("1234"),
                Caissier(2, "Bruno Lefèvre", Role.RESPONSABLE) to hashPin("0000"),
                Caissier(3, "Chloé Dupont", Role.CAISSIER) to hashPin("4321"),
            ),
        )
        assertEquals("Bruno Lefèvre", db.caissiers().single { it.id == 2L }.nom, "point de départ : ancien nom")
        Seed.appliquerSiVide(db)
        val cs = db.caissiers().associateBy { it.id }
        assertEquals("Armel NGANDO", cs.getValue(2).nom)
        assertEquals("Manager", cs.getValue(2).libelleConnexion)
        assertEquals("Alice M.", cs.getValue(1).nom)
        assertNotNull(db.verifierPinCaissier(2, "0000"), "le code PIN du manager est conserve")
        assertNotNull(db.verifierPinCaissier(1, "1234"))
        assertNull(db.verifierPinCaissier(1, "0000"))
    }

    @Test
    fun `un nom venu du serveur n'est jamais ecrase par la migration`() {
        val db = base()
        db.remplacerReferentiel(
            listOf(fr.demo.caisse.model.Produit("9", "Produit", 100, 550, "Divers")),
            listOf(Caissier(1, "Nom du SaaS", Role.CAISSIER, "Faucon") to hashPin("1234")),
        )
        Seed.appliquerSiVide(db)
        val c = db.caissiers().single { it.id == 1L }
        assertEquals("Nom du SaaS", c.nom)
        assertEquals("Faucon", c.libelleConnexion)
    }
}
