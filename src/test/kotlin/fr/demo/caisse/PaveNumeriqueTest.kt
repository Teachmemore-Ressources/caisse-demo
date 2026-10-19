package fr.demo.caisse

import fr.demo.caisse.data.Database
import fr.demo.caisse.data.Seed
import fr.demo.caisse.model.TypeLigne
import fr.demo.caisse.net.SyncService
import fr.demo.caisse.ui.AppState
import fr.demo.caisse.ui.ModeSaisie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comportement du pavé numérique de la caisse (Qté, % Remise, Prix, ±, ⌫) et des droits responsable. */
class PaveNumeriqueTest {
    private fun etat(responsable: Boolean = false): AppState {
        val db = Database(Files.createTempDirectory("pave").resolve("t.db"))
        Seed.appliquerSiVide(db)
        val s = AppState(db, SyncService(db, CoroutineScope(SupervisorJob() + Dispatchers.Default)))
        s.connecter(db.caissiers().first { it.estResponsable == responsable })
        s.ajouter(s.produits.first { it.code == "3000001" }) // eau 1,20 €
        return s
    }

    @Test
    fun `ajouter un produit selectionne sa ligne`() {
        val s = etat()
        assertEquals(0, s.selection)
        s.ajouter(s.produits.first { it.code == "3000011" })
        assertEquals(1, s.selection)
    }

    @Test
    fun `quantite saisie au pave remplace la quantite de la ligne`() {
        val s = etat()
        s.toucheNumerique("1"); s.toucheNumerique("2")
        assertEquals(12, s.panier.lignes[0].qte)
        assertEquals(1440, s.panier.totalCents)
        s.toucheNumerique("0")                 // « 120 »
        assertEquals(120, s.panier.lignes[0].qte)
    }

    @Test
    fun `retour arriere efface un chiffre puis retire la ligne`() {
        val s = etat()
        s.toucheNumerique("3"); s.toucheNumerique("5")
        s.toucheEffacer()
        assertEquals("3", s.saisie)
        assertEquals(3, s.panier.lignes[0].qte)
        s.toucheEffacer()                      // saisie vide
        s.toucheEffacer()                      // rien en cours : la ligne disparaît
        assertTrue(s.panier.estVide)
        assertNull(s.selection)
    }

    @Test
    fun `remise de ligne au pave - au dessus de 20 pour cent il faut un responsable`() {
        val s = etat(responsable = false)
        s.choisirModeSaisie(ModeSaisie.REMISE)
        s.toucheNumerique("1"); s.toucheNumerique("0")
        assertEquals(10, s.panier.lignes[0].remisePct)
        s.toucheNumerique("5")                 // « 105 » dépasse 100 : ignoré
        assertEquals("10", s.saisie)
        s.toucheEffacer(); s.toucheEffacer()   // plus de chiffre : 0 %
        assertEquals(0, s.panier.lignes[0].remisePct)
        s.toucheNumerique("5"); s.toucheNumerique("0")
        assertNotNull(s.autorisation, "50 % demande l'autorisation d'un responsable")
        assertTrue(s.panier.lignes[0].remisePct < 50, "la remise ne s'applique pas avant l'autorisation")
        s.autorisation!!.action()
        assertEquals(50, s.panier.lignes[0].remisePct)
    }

    @Test
    fun `modifier le prix est reserve au responsable`() {
        val caissier = etat(responsable = false)
        caissier.choisirModeSaisie(ModeSaisie.PRIX)
        assertNotNull(caissier.autorisation)
        assertEquals(ModeSaisie.QTE, caissier.modeSaisie, "le mode ne change pas sans autorisation")

        val resp = etat(responsable = true)
        resp.choisirModeSaisie(ModeSaisie.PRIX)
        assertNull(resp.autorisation)
        resp.toucheNumerique("2"); resp.toucheNumerique(","); resp.toucheNumerique("5")
        assertEquals(250, resp.panier.lignes[0].prixCents)
        resp.toucheNumerique("9")
        assertEquals(259, resp.panier.lignes[0].prixCents)
        resp.toucheNumerique("9")              // plus de deux décimales : ignoré
        assertEquals(259, resp.panier.lignes[0].prixCents)
    }

    @Test
    fun `touche plus ou moins bascule la ligne en retour avec autorisation`() {
        val s = etat(responsable = false)
        s.toucheSigne()
        assertNotNull(s.autorisation)
        s.autorisation!!.action()
        assertEquals(TypeLigne.RETOUR, s.panier.lignes[0].type)
        assertEquals(-120, s.panier.totalCents)
    }

    @Test
    fun `sans ligne selectionnee le pave previent sans rien casser`() {
        val s = etat()
        s.viderPanier()
        s.toucheNumerique("5")
        assertTrue(s.panier.estVide)
        assertEquals(fr.demo.caisse.ui.Niveau.INFO, s.message?.niveau)
    }
}
