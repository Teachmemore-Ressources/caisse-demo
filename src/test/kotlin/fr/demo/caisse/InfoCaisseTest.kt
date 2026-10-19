package fr.demo.caisse

import androidx.compose.ui.Alignment
import fr.demo.caisse.ui.ReglagesInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfoCaisseTest {
    @Test
    fun `reglages par defaut sans fichier`() {
        val r = ReglagesInfo.lire("")
        assertTrue(r.actif)
        assertEquals("bottom-right", r.position)
        assertEquals(2000, r.appuiMs)
        assertEquals(Alignment.BottomEnd, r.alignement)
    }

    @Test
    fun `reglages lus dans le fichier du paquet kiosque`() {
        val r = ReglagesInfo.lire(
            """
            # commentaire
            INFO_BUTTON_ENABLED=yes
            INFO_BUTTON_POS="top-left"
            INFO_HOLD_SECONDS=3
            AUTRE=1
            """.trimIndent(),
        )
        assertTrue(r.actif)
        assertEquals(Alignment.TopStart, r.alignement)
        assertEquals(3000, r.appuiMs)
    }

    @Test
    fun `le bouton se desactive et les valeurs aberrantes sont ignorees`() {
        assertFalse(ReglagesInfo.lire("INFO_BUTTON_ENABLED=no").actif)
        val r = ReglagesInfo.lire("INFO_BUTTON_POS=milieu\nINFO_HOLD_SECONDS=abc")
        assertEquals("bottom-right", r.position)
        assertEquals(2000, r.appuiMs)
        assertEquals(500, ReglagesInfo.lire("INFO_HOLD_SECONDS=0.01").appuiMs, "appui minimal borné : un effleurement ne doit pas ouvrir")
    }
}
