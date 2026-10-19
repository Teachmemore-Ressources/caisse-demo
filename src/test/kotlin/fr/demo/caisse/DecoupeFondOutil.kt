package fr.demo.caisse

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Outil, pas un vrai test : découpe la partie « photo » d'une maquette (webp, png, jpg) pour en faire le fond de l'écran d'accueil.
 * Inactif sans variables d'environnement. Exemple :
 *   DECOUPE_SRC=maquette.webp DECOUPE_OUT=src/main/resources/fonds/accueil.jpg DECOUPE_ZONE=640,122,1033,992 bash tools/wsl-build.sh cleanTest test --tests '*DecoupeFondOutil*'
 */
class DecoupeFondOutil {
    @Test
    fun `decoupe de la photo`() {
        val src = System.getenv("DECOUPE_SRC") ?: return
        val out = File(System.getenv("DECOUPE_OUT") ?: error("DECOUPE_OUT manquant"))
        val (x0, y0, x1, y1) = (System.getenv("DECOUPE_ZONE") ?: "640,122,1033,992").split(",").map { it.trim().toFloat() }
        val image = Image.makeFromEncoded(File(src).readBytes())
        println("maquette : ${image.width}x${image.height}")
        val largeur = (x1 - x0).toInt()
        val hauteur = (y1 - y0).toInt()
        val surface = Surface.makeRasterN32Premul(largeur, hauteur)
        surface.canvas.drawImageRect(image, Rect.makeLTRB(x0, y0, x1, y1), Rect.makeWH(largeur.toFloat(), hauteur.toFloat()))
        val octets = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 92)!!.bytes
        out.parentFile.mkdirs()
        out.writeBytes(octets)
        println("photo découpée : ${largeur}x$hauteur, ${octets.size} octets -> $out")
        assertTrue(octets.size > 5000)
    }
}
