package fr.demo.caisse.data

import fr.demo.caisse.model.Caissier
import fr.demo.caisse.model.Client
import fr.demo.caisse.model.Produit
import fr.demo.caisse.model.Role

/**
 * Données de démonstration, chargées au premier lancement pour que la caisse fonctionne
 * sans serveur. Dès que le SaaS est configuré, sa synchronisation remplace produits et caissiers.
 *
 * Comptes de démo : PIN 1234 (caissier), PIN 0000 (responsable).
 */
object Seed {
    // `nom` = identité réelle (prénom et initiale du nom pour les caissiers) ; `code` = ce qu'affiche l'écran de connexion.
    private val caissiers = listOf(
        Caissier(1, "Alice M.", Role.CAISSIER, "Caissier 01") to "1234",
        Caissier(2, "Armel NGANDO", Role.RESPONSABLE, "Manager") to "0000",
        Caissier(3, "Chloé D.", Role.CAISSIER, "Caissier 02") to "4321",
    )

    /** Noms de démonstration des versions précédentes, par identifiant : ils sont remplacés sans toucher aux noms venus du serveur. */
    private val anciensNoms = mapOf(
        1L to listOf("Alice Martin"),
        2L to listOf("Bruno Lefèvre"),
        3L to listOf("Chloé Dupont"),
    )

    private val produits = listOf(
        Produit("3000001", "Eau minérale 50 cl", 120, 550, "Boissons"),
        Produit("3000002", "Soda cola 33 cl", 180, 2000, "Boissons"),
        Produit("3000003", "Café expresso", 150, 550, "Boissons"),
        Produit("3000004", "Jus d'orange 1 L", 250, 550, "Boissons"),
        Produit("3000005", "Bière blonde 25 cl", 220, 2000, "Boissons"),
        Produit("3000006", "Thé glacé pêche", 195, 550, "Boissons"),
        Produit("3000010", "Baguette tradition", 110, 550, "Boulangerie"),
        Produit("3000011", "Croissant pur beurre", 130, 550, "Boulangerie"),
        Produit("3000012", "Pain au chocolat", 140, 550, "Boulangerie"),
        Produit("3000013", "Sandwich jambon-beurre", 450, 1000, "Boulangerie"),
        Produit("3000014", "Tarte aux pommes", 320, 550, "Boulangerie"),
        Produit("3000020", "Pâtes penne 500 g", 195, 550, "Épicerie"),
        Produit("3000021", "Riz basmati 1 kg", 240, 550, "Épicerie"),
        Produit("3000022", "Tablette chocolat noir", 230, 550, "Épicerie"),
        Produit("3000023", "Biscuits sablés", 210, 550, "Épicerie"),
        Produit("3000024", "Huile d'olive 50 cl", 599, 550, "Épicerie"),
        Produit("3000025", "Café moulu 250 g", 399, 550, "Épicerie"),
        Produit("3000030", "Pommes (le kilo)", 249, 550, "Fruits et légumes"),
        Produit("3000031", "Bananes (le kilo)", 189, 550, "Fruits et légumes"),
        Produit("3000032", "Tomates grappe 500 g", 320, 550, "Fruits et légumes"),
        Produit("3000040", "Savon liquide", 250, 2000, "Maison"),
        Produit("3000041", "Piles AA x4", 499, 2000, "Maison"),
        Produit("3000042", "Sac réutilisable", 10, 2000, "Maison"),
        Produit("3000043", "Journal quotidien", 200, 210, "Presse"),
        Produit("3000044", "Magazine TV", 399, 210, "Presse"),
    )

    private val clients = listOf(
        Client(1, "Marie Dubois", "2000 0001 4521", 342),
        Client(2, "Karim Benali", "2000 0002 7788", 1280),
        Client(3, "Sophie Moreau", "2000 0003 1054", 87),
        Client(4, "Julien Garnier", "2000 0004 9930", 0),
        Client(5, "Nadia Haddad", "2000 0005 3316", 560),
        Client(6, "Pierre Lambert", "2000 0006 2203", 15, actif = false),
    )

    fun appliquerSiVide(db: Database) {
        if (db.nbProduits() == 0) {
            db.remplacerReferentiel(produits, caissiers.map { (c, pin) -> c to hashPin(pin) })
        }
        if (db.nbClients() == 0) db.remplacerClients(clients)
        // Base déjà créée par une version précédente : nouveaux noms et noms de code, sans perdre les codes PIN
        caissiers.forEach { (c, _) -> db.renommerCaissierSiAncien(c.id, anciensNoms.getValue(c.id), c.nom, c.code) }
    }
}
