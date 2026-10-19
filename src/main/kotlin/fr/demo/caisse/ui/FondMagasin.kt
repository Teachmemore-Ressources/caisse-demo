package fr.demo.caisse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Fond de l'écran d'accueil : un intérieur de magasin flou avec un poste de caisse.
 *
 * Pour mettre une VRAIE photo, sans reconstruire l'application, déposer l'un de ces fichiers (jpg, png ou webp, ~1600×1000) :
 *   - le chemin indiqué par la variable d'environnement CAISSE_FOND_ACCUEIL ;
 *   - /etc/caisse-demo/accueil.jpg (ou .png, .webp).
 * Sans photo, un décor dessiné en code est utilisé (aucun fichier nécessaire).
 */
@Composable
fun FondMagasin(modifier: Modifier = Modifier) {
    val photo = remember { chargerPhoto() }
    Box(modifier.clipToBounds()) {
        if (photo != null) {
            // Cadrage sur la droite de la photo : c'est là que se trouve le poste de caisse (le texte est à gauche, sous le voile blanc)
            Image(photo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = androidx.compose.ui.Alignment.CenterEnd)
        } else {
            Canvas(Modifier.fillMaxSize().blur(5.dp)) { decor() }
            Canvas(Modifier.fillMaxSize()) { poste() }
            Canvas(Modifier.fillMaxSize().blur(16.dp)) { lumieres() }
        }
    }
}

private object FondMagasinMarqueur

private fun chargerPhoto(): ImageBitmap? {
    val fichiers = buildList {
        System.getenv("CAISSE_FOND_ACCUEIL")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        listOf("jpg", "jpeg", "png", "webp").forEach { add(File("/etc/caisse-demo/accueil.$it")) }
    }
    fichiers.filter { it.isFile }.forEach { f -> runCatching { f.inputStream().use { loadImageBitmap(it) } }.getOrNull()?.let { return it } }
    listOf("jpg", "jpeg", "png", "webp").forEach { ext ->
        val flux = FondMagasinMarqueur::class.java.getResourceAsStream("/fonds/accueil.$ext") ?: return@forEach
        runCatching { flux.use { loadImageBitmap(it) } }.getOrNull()?.let { return it }
    }
    return null
}

private val Produits = listOf(
    Color(0xFFC0392B), Color(0xFFE67E22), Color(0xFFF1C40F), Color(0xFF27AE60), Color(0xFF2980B9), Color(0xFFECE0C8), Color(0xFF8D6E63), Color(0xFFE74C3C), Color(0xFF16A085),
)

private fun DrawScope.decor() {
    val w = size.width
    val h = size.height
    // mur et plafond clairs
    drawRect(Brush.verticalGradient(listOf(Color(0xFFFFFCF6), Color(0xFFF3E9D8), Color(0xFFE6D3B5)), startY = 0f, endY = h))

    // rayonnages : fond sombre chaud, montants, rangées de produits colorés
    val gx = w * 0.30f
    val gy = h * 0.27f
    val gw = w * 0.80f
    val gh = h * 0.50f
    drawRect(Brush.verticalGradient(listOf(Color(0xFF7A5A43), Color(0xFF4E3A2C))), topLeft = Offset(gx, gy), size = Size(gw, gh))
    val rangees = 5
    for (r in 0 until rangees) {
        val y0 = gy + gh * (r / rangees.toFloat())
        val hr = gh / rangees
        var x = gx + w * 0.01f
        var i = r * 3
        while (x < w * 1.0f) {
            val lw = w * (0.035f + ((i * 7) % 5) * 0.012f)
            val lh = hr * (0.55f + ((i * 3) % 4) * 0.10f)
            drawRoundRect(Produits[i % Produits.size].copy(alpha = 0.92f), topLeft = Offset(x, y0 + hr - lh - hr * 0.08f), size = Size(lw, lh), cornerRadius = CornerRadius(lw * 0.2f))
            x += lw + w * 0.006f
            i++
        }
        // planche de rayon
        drawRect(Color(0xFFCDB89A).copy(alpha = 0.85f), topLeft = Offset(gx, y0 + hr - hr * 0.08f), size = Size(gw, hr * 0.10f))
    }
    // verdure en haut à droite
    listOf(0.88f to 0.22f, 0.94f to 0.26f, 0.84f to 0.27f, 0.97f to 0.19f).forEach { (cx, cy) ->
        drawCircle(Brush.radialGradient(listOf(Color(0xFF4F9A4B), Color(0xFF2E6B33)), center = Offset(w * cx, h * cy), radius = w * 0.07f), radius = w * 0.07f, center = Offset(w * cx, h * cy))
    }

    // comptoir en bois
    val comptoir = Path().apply { moveTo(0f, h * 0.84f); lineTo(w, h * 0.70f); lineTo(w, h); lineTo(0f, h); close() }
    drawPath(comptoir, Brush.linearGradient(listOf(Color(0xFFE8C9A0), Color(0xFFC79A66), Color(0xFFA87A4B)), start = Offset(0f, h * 0.75f), end = Offset(w, h)))
    drawLine(Color.White.copy(alpha = 0.55f), Offset(0f, h * 0.84f), Offset(w, h * 0.70f), strokeWidth = 3f)

    // plante verte posée sur le comptoir
    val px = w * 0.56f
    val py = h * 0.70f
    drawRoundRect(Color(0xFFF2EFE8), topLeft = Offset(px - w * 0.035f, py), size = Size(w * 0.07f, h * 0.075f), cornerRadius = CornerRadius(8f))
    listOf(-0.10f to -0.07f, 0.02f to -0.12f, 0.10f to -0.05f, -0.03f to -0.04f, 0.06f to -0.09f).forEach { (dx, dy) ->
        drawOval(Color(0xFF3E8E41), topLeft = Offset(px + w * dx - w * 0.05f, py + h * dy - h * 0.03f), size = Size(w * 0.10f, h * 0.06f))
    }
}

/** Poste de caisse net (au premier plan) : écran, lecteur de cartes, base. */
private fun DrawScope.poste() {
    val w = size.width
    val h = size.height
    // écran incliné, à droite
    rotate(-9f, pivot = Offset(w * 0.86f, h * 0.56f)) {
        drawRoundRect(Color(0xFF15171B), topLeft = Offset(w * 0.72f, h * 0.40f), size = Size(w * 0.30f, h * 0.30f), cornerRadius = CornerRadius(14f))
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF39404E), Color(0xFF1B1F27)), start = Offset(w * 0.74f, h * 0.42f), end = Offset(w * 0.98f, h * 0.66f)),
            topLeft = Offset(w * 0.735f, h * 0.415f), size = Size(w * 0.27f, h * 0.265f), cornerRadius = CornerRadius(8f),
        )
        // reflet
        drawRoundRect(Color.White.copy(alpha = 0.06f), topLeft = Offset(w * 0.74f, h * 0.42f), size = Size(w * 0.12f, h * 0.25f), cornerRadius = CornerRadius(8f))
    }
    // pied de l'écran
    val pied = Path().apply { moveTo(w * 0.83f, h * 0.68f); lineTo(w * 0.93f, h * 0.66f); lineTo(w * 0.95f, h * 0.76f); lineTo(w * 0.80f, h * 0.78f); close() }
    drawPath(pied, Color(0xFF111317))
    // base / imprimante
    drawRoundRect(Color(0xFF14161A), topLeft = Offset(w * 0.52f, h * 0.775f), size = Size(w * 0.48f, h * 0.085f), cornerRadius = CornerRadius(16f))
    drawRoundRect(Color.White.copy(alpha = 0.07f), topLeft = Offset(w * 0.53f, h * 0.78f), size = Size(w * 0.46f, h * 0.012f), cornerRadius = CornerRadius(6f))
    // lecteur de cartes, légèrement penché
    rotate(-6f, pivot = Offset(w * 0.66f, h * 0.74f)) {
        drawRoundRect(Color(0xFF1B1E24), topLeft = Offset(w * 0.56f, h * 0.66f), size = Size(w * 0.18f, h * 0.115f), cornerRadius = CornerRadius(14f))
        drawRoundRect(Color(0xFF5DD39E), topLeft = Offset(w * 0.595f, h * 0.675f), size = Size(w * 0.10f, h * 0.035f), cornerRadius = CornerRadius(5f))
        for (i in 0 until 3) for (j in 0 until 3) drawCircle(Color(0xFF3A3F48), radius = 3.2f, center = Offset(w * (0.605f + i * 0.022f), h * (0.725f + j * 0.012f)))
    }
}

private fun DrawScope.lumieres() {
    val w = size.width
    val h = size.height
    listOf(
        Triple(0.64f, 0.02f, 0.06f), Triple(0.56f, 0.13f, 0.04f), Triple(0.92f, 0.12f, 0.05f), Triple(0.76f, 0.20f, 0.04f), Triple(0.34f, 0.06f, 0.05f),
    ).forEach { (x, y, r) ->
        val c = Offset(w * x, h * y)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.55f), Color.Transparent), center = c, radius = w * r), radius = w * r, center = c)
    }
}

// ---- petites icônes dessinées (aucune image) ---------------------------------------------------------------------------

private val Vert = Color(0xFF0A9A62)
private val Marine = Color(0xFF0B1B34)

/** Logo : un panier de courses avec une feuille. */
@Composable
fun LogoPanier(taille: Dp = 56.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(taille)) {
        val s = size.width
        // feuille
        val feuille = Path().apply {
            moveTo(s * 0.62f, s * 0.30f)
            cubicTo(s * 0.62f, s * 0.08f, s * 0.82f, s * 0.04f, s * 0.92f, s * 0.07f)
            cubicTo(s * 0.92f, s * 0.24f, s * 0.80f, s * 0.34f, s * 0.62f, s * 0.30f)
            close()
        }
        drawPath(feuille, Brush.linearGradient(listOf(Color(0xFF6BC04B), Vert), start = Offset(s * 0.6f, s * 0.05f), end = Offset(s * 0.9f, s * 0.3f)))
        // panier
        val trait = Stroke(s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val poignee = Path().apply { moveTo(s * 0.04f, s * 0.20f); lineTo(s * 0.20f, s * 0.20f); lineTo(s * 0.34f, s * 0.68f); lineTo(s * 0.80f, s * 0.68f) }
        drawPath(poignee, Vert, style = trait)
        val corps = Path().apply { moveTo(s * 0.25f, s * 0.34f); lineTo(s * 0.90f, s * 0.34f); lineTo(s * 0.80f, s * 0.60f); lineTo(s * 0.32f, s * 0.60f) }
        drawPath(corps, Vert, style = trait)
        drawLine(Vert, Offset(s * 0.58f, s * 0.36f), Offset(s * 0.56f, s * 0.58f), strokeWidth = s * 0.05f, cap = StrokeCap.Round)
        drawCircle(Marine, radius = s * 0.065f, center = Offset(s * 0.40f, s * 0.84f))
        drawCircle(Marine, radius = s * 0.065f, center = Offset(s * 0.74f, s * 0.84f))
    }
}

/** Épingle de localisation. */
@Composable
fun IconePosition(taille: Dp = 22.dp, couleur: Color = Marine, modifier: Modifier = Modifier) {
    Canvas(modifier.size(taille)) {
        val s = size.width
        val goutte = Path().apply {
            moveTo(s * 0.5f, s * 0.94f)
            cubicTo(s * 0.14f, s * 0.56f, s * 0.16f, s * 0.08f, s * 0.5f, s * 0.08f)
            cubicTo(s * 0.84f, s * 0.08f, s * 0.86f, s * 0.56f, s * 0.5f, s * 0.94f)
            close()
        }
        drawPath(goutte, couleur, style = Stroke(s * 0.09f, join = StrokeJoin.Round))
        drawCircle(couleur, radius = s * 0.10f, center = Offset(s * 0.5f, s * 0.40f), style = Stroke(s * 0.08f))
    }
}
