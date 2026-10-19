package fr.demo.caisse.domain

import fr.demo.caisse.model.LigneTicket
import fr.demo.caisse.model.Produit
import fr.demo.caisse.model.TypeLigne

data class VentilationTva(val tvaBp: Int, val ttcCents: Long, val htCents: Long) {
    val tvaCents: Long get() = ttcCents - htCents
}

/**
 * Panier immuable : chaque opération renvoie un nouveau panier (simple à tester et à observer).
 * `remiseCents` est une remise globale sur le ticket (montant TTC), répartie au prorata sur les taux de TVA.
 */
data class Panier(val lignes: List<LigneTicket> = emptyList(), val remiseCents: Long = 0) {

    val estVide: Boolean get() = lignes.isEmpty()
    val sousTotalCents: Long get() = lignes.sumOf { it.totalCents }

    /** Une remise ne dépasse jamais le sous-total et n'a pas de sens sur un ticket de retour. */
    val remiseEffectiveCents: Long get() = if (sousTotalCents > 0) remiseCents.coerceIn(0, sousTotalCents) else 0
    val totalCents: Long get() = sousTotalCents - remiseEffectiveCents
    val nbArticles: Int get() = lignes.sumOf { it.qte }
    val estRetour: Boolean get() = totalCents < 0

    fun ajouter(produit: Produit, retour: Boolean = false, quantite: Int = 1): Panier {
        val type = if (retour) TypeLigne.RETOUR else TypeLigne.PRODUIT
        val i = lignes.indexOfFirst { it.code == produit.code && it.type == type && it.remisePct == 0 }
        return if (i >= 0) {
            copy(lignes = lignes.toMutableList().also { it[i] = it[i].copy(qte = it[i].qte + quantite) })
        } else {
            copy(lignes = lignes + LigneTicket(produit.code, produit.nom, quantite, produit.prixCents, produit.tvaBp, 0, type))
        }
    }

    fun changerQuantite(index: Int, delta: Int): Panier {
        val l = lignes.getOrNull(index) ?: return this
        val q = l.qte + delta
        return if (q <= 0) supprimer(index) else copy(lignes = lignes.toMutableList().also { it[index] = l.copy(qte = q) })
    }

    /** Fixe la quantité d'une ligne (pavé numérique). Une quantité nulle ne change rien : seule la touche ⌫ supprime. */
    fun fixerQuantite(index: Int, qte: Int): Panier {
        val l = lignes.getOrNull(index) ?: return this
        return if (qte <= 0) this else copy(lignes = lignes.toMutableList().also { it[index] = l.copy(qte = qte.coerceAtMost(9999)) })
    }

    /** Remplace le prix unitaire TTC d'une ligne (action réservée au responsable). */
    fun fixerPrix(index: Int, prixCents: Long): Panier {
        val l = lignes.getOrNull(index) ?: return this
        return copy(lignes = lignes.toMutableList().also { it[index] = l.copy(prixCents = prixCents.coerceAtLeast(0)) })
    }

    /** Bascule une ligne entre vente et retour (touche ±). */
    fun basculerRetour(index: Int): Panier {
        val l = lignes.getOrNull(index) ?: return this
        val type = if (l.type == TypeLigne.RETOUR) TypeLigne.PRODUIT else TypeLigne.RETOUR
        return copy(lignes = lignes.toMutableList().also { it[index] = l.copy(type = type) })
    }

    /** Position de la ligne correspondant à ce produit (celle qu'on vient d'ajouter ou d'incrémenter). */
    fun indexDe(produit: Produit, retour: Boolean): Int {
        val type = if (retour) TypeLigne.RETOUR else TypeLigne.PRODUIT
        return lignes.indexOfLast { it.code == produit.code && it.type == type }
    }

    fun remiseLigne(index: Int, pct: Int): Panier {
        val l = lignes.getOrNull(index) ?: return this
        return copy(lignes = lignes.toMutableList().also { it[index] = l.copy(remisePct = pct.coerceIn(0, 100)) })
    }

    fun avecRemiseTicket(cents: Long): Panier = copy(remiseCents = cents.coerceAtLeast(0))

    fun supprimer(index: Int): Panier =
        if (index in lignes.indices) copy(lignes = lignes.filterIndexed { i, _ -> i != index }) else this

    fun ventilationTva(): List<VentilationTva> = ventiler(lignes, remiseEffectiveCents)

    companion object {
        /**
         * Ventilation par taux de TVA. Le HT est calculé sur le total TTC de chaque taux (pas ligne à ligne).
         * La remise ticket est répartie au prorata du TTC de chaque taux ; le reliquat d'arrondi va au dernier taux,
         * pour que la somme des TTC ventilés soit toujours égale au total à payer.
         */
        fun ventiler(lignes: List<LigneTicket>, remiseTicketCents: Long = 0): List<VentilationTva> {
            val parTaux = lignes.groupBy { it.tvaBp }.toSortedMap().map { (taux, ls) -> taux to ls.sumOf { it.totalCents } }
            val sousTotal = parTaux.sumOf { it.second }
            var reste = if (sousTotal > 0) remiseTicketCents.coerceIn(0, sousTotal) else 0L
            val remiseTotale = reste
            return parTaux.mapIndexed { i, (taux, ttc) ->
                val part = when {
                    remiseTotale == 0L || sousTotal <= 0 -> 0L
                    i == parTaux.lastIndex -> reste
                    else -> ((remiseTotale * ttc) + sousTotal / 2) / sousTotal
                }
                reste -= part
                val net = ttc - part
                VentilationTva(taux, net, Money.ht(net, taux))
            }
        }
    }
}
