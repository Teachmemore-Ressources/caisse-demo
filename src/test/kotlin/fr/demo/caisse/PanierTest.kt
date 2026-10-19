package fr.demo.caisse

import fr.demo.caisse.data.Database
import fr.demo.caisse.data.Seed
import fr.demo.caisse.data.hashPin
import fr.demo.caisse.domain.Money
import fr.demo.caisse.domain.Panier
import fr.demo.caisse.model.ModePaiement
import fr.demo.caisse.model.Paiement
import fr.demo.caisse.model.Produit
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.model.TypeComptage
import fr.demo.caisse.model.TypeMouvement
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanierTest {
    private val eau = Produit("1", "Eau", 120, 550, "B")
    private val savon = Produit("2", "Savon", 250, 2000, "D")

    private fun base(): Database = Database(Files.createTempDirectory("caisse-test").resolve("t.db")).also { Seed.appliquerSiVide(it) }

    private fun ticket(db: Database, panier: Panier, vararg paiements: Paiement, uuid: String = "u-${System.nanoTime()}") =
        Ticket(uuid, db.prochainNumero(), "2026-10-17T10:00:00", "Alice", null, panier.lignes, panier.remiseEffectiveCents, panier.totalCents, paiements.toList())

    @Test
    fun `ajouter deux fois le meme produit incremente la quantite`() {
        val p = Panier().ajouter(eau).ajouter(eau)
        assertEquals(1, p.lignes.size)
        assertEquals(2, p.lignes[0].qte)
        assertEquals(240, p.totalCents)
    }

    @Test
    fun `remise en pourcentage arrondie au centime`() {
        assertEquals(225, Money.appliquerRemise(250, 10))
        assertEquals(0, Money.appliquerRemise(250, 100))
        assertEquals(250, Money.appliquerRemise(250, 0))
        assertEquals(-225, Money.appliquerRemise(-250, 10)) // arrondi symétrique pour les retours
    }

    @Test
    fun `quantite a zero supprime la ligne`() {
        assertTrue(Panier().ajouter(eau).changerQuantite(0, -1).estVide)
    }

    @Test
    fun `ventilation TVA par taux`() {
        val p = Panier().ajouter(eau).ajouter(savon)
        val v = p.ventilationTva()
        assertEquals(listOf(550, 2000), v.map { it.tvaBp })
        // 1,20 TTC à 5,5 % -> HT 1,14 ; 2,50 TTC à 20 % -> HT 2,08
        assertEquals(114, v[0].htCents)
        assertEquals(208, v[1].htCents)
        assertEquals(p.totalCents, v.sumOf { it.ttcCents })
    }

    @Test
    fun `remise ticket repartie au prorata et la somme reste exacte`() {
        val p = Panier().ajouter(eau, quantite = 3).ajouter(savon, quantite = 2).avecRemiseTicket(137)
        val v = p.ventilationTva()
        assertEquals(p.totalCents, v.sumOf { it.ttcCents }, "le TTC ventilé doit égaler le total à payer")
        assertEquals(360 + 500 - 137, p.totalCents)
    }

    @Test
    fun `une remise ne depasse jamais le sous-total`() {
        val p = Panier().ajouter(eau).avecRemiseTicket(10_000)
        assertEquals(0, p.totalCents)
        assertEquals(120, p.remiseEffectiveCents)
    }

    @Test
    fun `retour - total negatif et ventilation negative`() {
        val p = Panier().ajouter(eau, retour = true, quantite = 2)
        assertEquals(-240, p.totalCents)
        assertTrue(p.estRetour)
        assertEquals(-240, p.ventilationTva().sumOf { it.ttcCents })
        // vente et retour du même produit ne fusionnent pas
        assertEquals(2, Panier().ajouter(eau).ajouter(eau, retour = true).lignes.size)
    }

    @Test
    fun `format des montants`() {
        assertEquals("1,20 €", Money.format(120))
        assertEquals("0,05 €", Money.format(5))
        assertEquals("-3,00 €", Money.format(-300))
        assertEquals("5,5 %", Money.formatTaux(550))
        assertEquals("20 %", Money.formatTaux(2000))
    }

    @Test
    fun `base de donnees ticket paiements mixtes cloture et synchro`() {
        val db = base()
        assertNotNull(db.verifierPinCaissier(1, "1234"))
        assertNull(db.verifierPinCaissier(1, "0000"), "le PIN d'un autre caissier ne doit pas passer")
        assertNull(db.verifierPin("9999"))
        assertTrue(db.verifierPin("0000")!!.estResponsable)
        assertEquals(64, hashPin("0000").length)

        val panier = Panier().ajouter(eau).ajouter(savon) // 3,70 €
        val t = ticket(db, panier, Paiement(ModePaiement.ESPECES, 200, 0), Paiement(ModePaiement.CARTE, 170, 0), uuid = "u-1")
        db.enregistrerTicket(t)
        assertEquals(1, db.nbTicketsNonSynchronises())

        val relu = db.ticketsNonSynchronises().single()
        assertEquals(370, relu.totalCents)
        assertEquals(2, relu.lignes.size)
        assertEquals("MIXTE", relu.mode)
        assertEquals(listOf(ModePaiement.ESPECES, ModePaiement.CARTE), relu.paiements.map { it.mode })

        db.marquerSynchronises(listOf("u-1"))
        assertEquals(0, db.nbTicketsNonSynchronises())

        val z = assertNotNull(db.cloturer("Alice"))
        assertEquals(1, z.nbTickets)
        assertEquals(200, z.total(ModePaiement.ESPECES))
        assertEquals(170, z.total(ModePaiement.CARTE))
        assertEquals(370, z.totalCents)
        assertNull(db.cloturer("Alice")) // plus rien à clôturer
        db.close()
    }

    @Test
    fun `comptage theorique - fond plus especes plus mouvements`() {
        val db = base()
        db.enregistrerComptage(TypeComptage.OUVERTURE, "Alice", 10_000, 0, 10_000, "")
        assertEquals(10_000, db.fondOuverture())

        db.enregistrerTicket(ticket(db, Panier().ajouter(eau), Paiement(ModePaiement.ESPECES, 120, 80)))      // espèces 1,20 net
        db.enregistrerTicket(ticket(db, Panier().ajouter(savon), Paiement(ModePaiement.CARTE, 250, 0)))        // carte : hors tiroir
        db.enregistrerMouvement(TypeMouvement.ENTREE, 2_000, "Alice", "Fond")
        db.enregistrerMouvement(TypeMouvement.PRELEVEMENT, 5_000, "Bruno", "Banque")
        db.enregistrerMouvement(TypeMouvement.SORTIE, 300, "Alice", "Divers")

        assertEquals(10_000 + 120 + 2_000 - 5_000 - 300, db.theoriqueEspeces())
        assertEquals(250, db.theoriquesParMoyen().getValue(ModePaiement.CARTE))

        db.cloturer("Bruno")
        assertEquals(0, db.fondOuverture(), "après clôture, il faut un nouveau comptage d'ouverture")
        assertEquals(0, db.theoriqueEspeces())
        db.close()
    }

    @Test
    fun `ticket de retour - un ticket ne peut etre retourne qu une fois`() {
        val db = base()
        val vente = ticket(db, Panier().ajouter(eau), Paiement(ModePaiement.ESPECES, 120, 0), uuid = "v-1")
        db.enregistrerTicket(vente)
        assertFalse(db.dejaRetourne(vente.numero))
        val retour = ticket(db, Panier().ajouter(eau, retour = true), Paiement(ModePaiement.ESPECES, -120, 0), uuid = "r-1").copy(retourDe = vente.numero)
        db.enregistrerTicket(retour)
        assertTrue(db.dejaRetourne(vente.numero))
        assertTrue(db.ticket(retour.numero)!!.estRetour)
        db.close()
    }

    @Test
    fun `migration - une base de la premiere version reste lisible`() {
        val dir = Files.createTempDirectory("caisse-migration")
        val fichier = dir.resolve("ancienne.db")
        DriverManager.getConnection("jdbc:sqlite:$fichier").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE ticket(uuid TEXT PRIMARY KEY, numero INTEGER NOT NULL, date_iso TEXT NOT NULL, caissier TEXT NOT NULL, total_cents INTEGER NOT NULL, mode TEXT NOT NULL, recu_cents INTEGER NOT NULL, rendu_cents INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, cloture_id INTEGER)")
                s.execute("CREATE TABLE ligne(id INTEGER PRIMARY KEY AUTOINCREMENT, ticket_uuid TEXT NOT NULL, code TEXT NOT NULL, nom TEXT NOT NULL, qte INTEGER NOT NULL, prix_cents INTEGER NOT NULL, tva_bp INTEGER NOT NULL, remise_pct INTEGER NOT NULL)")
                s.execute("CREATE TABLE cloture(id INTEGER PRIMARY KEY AUTOINCREMENT, date_iso TEXT NOT NULL, caissier TEXT NOT NULL, nb_tickets INTEGER NOT NULL, total_especes INTEGER NOT NULL, total_carte INTEGER NOT NULL)")
                s.execute("INSERT INTO ticket VALUES('old-1', 7, '2026-10-16T09:00:00', 'Alice', 480, 'ESPECES', 500, 20, 1, NULL)")
                s.execute("INSERT INTO ligne(ticket_uuid,code,nom,qte,prix_cents,tva_bp,remise_pct) VALUES('old-1','1','Eau',4,120,550,0)")
            }
        }
        val db = Database(fichier)
        val t = db.ticket(7)!!
        assertEquals(480, t.totalCents)
        assertEquals(ModePaiement.ESPECES, t.paiements.single().mode)
        assertEquals(20, t.renduCents)
        assertEquals(4, t.lignes.single().qte)
        assertEquals(8, db.prochainNumero())
        db.close()
    }
}
