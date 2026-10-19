package fr.demo.caisse.ui

import java.time.LocalDateTime

/**
 * Dates en français, écrites en dur. Le runtime embarqué dans le paquet (jlink) ne contient pas les données de langue du JDK
 * (module jdk.localedata) : `Locale.FRENCH` y donnait « Mon 19 Oct 2026 ». Ici, rien ne dépend du système.
 */
internal object DatesFr {
    private val jours = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val mois = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

    /** « Lundi 19 octobre 2026 » */
    fun jourComplet(d: LocalDateTime): String =
        "${jours[d.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }} ${if (d.dayOfMonth == 1) "1er" else d.dayOfMonth.toString()} ${mois[d.monthValue - 1]} ${d.year}"

    /** « 06:46 » */
    fun heure(d: LocalDateTime): String = "%02d:%02d".format(d.hour, d.minute)
}
