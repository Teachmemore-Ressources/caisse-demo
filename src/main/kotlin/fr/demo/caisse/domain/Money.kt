package fr.demo.caisse.domain

import kotlin.math.abs

/** Tous les montants sont en centimes (Long) : jamais de Double pour de l'argent. */
object Money {
    /** Applique une remise en pourcentage entier (0..100), arrondi au centime le plus proche (symétrique). */
    fun appliquerRemise(cents: Long, remisePct: Int): Long {
        val pct = remisePct.coerceIn(0, 100)
        val signe = if (cents < 0) -1 else 1
        return signe * ((abs(cents) * (100 - pct) + 50) / 100)
    }

    /** Montant HT d'un montant TTC pour un taux en points de base (550 = 5,5 %), arrondi symétrique. */
    fun ht(ttcCents: Long, tvaBp: Int): Long {
        val diviseur = 10_000L + tvaBp
        val signe = if (ttcCents < 0) -1 else 1
        return signe * ((abs(ttcCents) * 10_000L + diviseur / 2) / diviseur)
    }

    fun format(cents: Long): String {
        val signe = if (cents < 0) "-" else ""
        val a = abs(cents)
        return "$signe${a / 100},${(a % 100).toString().padStart(2, '0')} €"
    }

    /** Format compact sans symbole : « 12,50 ». */
    fun nombre(cents: Long): String {
        val signe = if (cents < 0) "-" else ""
        val a = abs(cents)
        return "$signe${a / 100},${(a % 100).toString().padStart(2, '0')}"
    }

    fun formatTaux(tvaBp: Int): String {
        val entier = tvaBp / 100
        val dec = tvaBp % 100
        return if (dec == 0) "$entier %" else "$entier,${dec.toString().padStart(2, '0').trimEnd('0')} %"
    }
}
