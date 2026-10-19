package fr.demo.caisse

import fr.demo.caisse.ui.DatesFr
import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DatesFrTest {
    @Test
    fun `les dates sont en francais quelle que soit la langue du systeme`() {
        val avant = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)          // simule le runtime embarqué, sans données de langue françaises
            assertEquals("Lundi 19 octobre 2026", DatesFr.jourComplet(LocalDateTime.of(2026, 10, 19, 6, 46)))
            assertEquals("Dimanche 1er février 2026", DatesFr.jourComplet(LocalDateTime.of(2026, 2, 1, 12, 0)))
            assertEquals("Jeudi 25 décembre 2025", DatesFr.jourComplet(LocalDateTime.of(2025, 12, 25, 0, 0)))
            assertEquals("06:46", DatesFr.heure(LocalDateTime.of(2026, 10, 19, 6, 46)))
            assertEquals("23:05", DatesFr.heure(LocalDateTime.of(2026, 10, 19, 23, 5)))
        } finally {
            Locale.setDefault(avant)
        }
    }
}
