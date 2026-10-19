@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package fr.demo.caisse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Génère l'icône de l'application (terminal de caisse stylisé) en plusieurs tailles dans build/icons.
 * Dessin vectoriel : aucune image tierce, aucune ressource d'un autre logiciel.
 */
class IconeTest {
    @Test
    fun `generation de l icone de l application`() {
        val sortie = dossier().also { Files.createDirectories(it) }
        for (taille in listOf(512, 256, 128, 64, 48, 32)) {
            val scene = ImageComposeScene(taille, taille, Density(1f)) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = size.width
                    // fond : carré arrondi dégradé turquoise
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(Color(0xFF2DD4BF), Color(0xFF0F766E))),
                        size = Size(s, s), cornerRadius = CornerRadius(s * 0.22f),
                    )
                    // écran du terminal
                    drawRoundRect(Color.White, Offset(s * 0.18f, s * 0.15f), Size(s * 0.64f, s * 0.30f), CornerRadius(s * 0.05f))
                    // lignes du ticket affiché
                    val teal = Color(0xFF0F766E)
                    drawRoundRect(teal, Offset(s * 0.25f, s * 0.21f), Size(s * 0.34f, s * 0.045f), CornerRadius(s * 0.02f))
                    drawRoundRect(teal.copy(alpha = 0.45f), Offset(s * 0.25f, s * 0.29f), Size(s * 0.22f, s * 0.035f), CornerRadius(s * 0.02f))
                    drawRoundRect(teal, Offset(s * 0.63f, s * 0.355f), Size(s * 0.12f, s * 0.05f), CornerRadius(s * 0.02f))
                    // pavé de touches 3 x 3
                    val touche = s * 0.145f
                    val ecart = s * 0.04f
                    val x0 = (s - (3 * touche + 2 * ecart)) / 2f
                    for (r in 0 until 3) for (c in 0 until 3) {
                        drawRoundRect(
                            Color.White.copy(alpha = if (r == 2 && c == 2) 1f else 0.88f),
                            Offset(x0 + c * (touche + ecart), s * 0.53f + r * (touche * 0.78f + ecart)),
                            Size(touche, touche * 0.78f), CornerRadius(s * 0.03f),
                        )
                    }
                }
            }
            try {
                scene.render(0)
                val octets = scene.render(100_000_000).encodeToData()!!.bytes
                Files.write(sortie.resolve("caisse-demo-$taille.png"), octets)
                assertTrue(octets.size > 300)
            } finally {
                scene.close()
            }
        }
    }

    private fun dossier() = Paths.get("build", "icons")
}
