package fr.demo.caisse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.net.EtatReseau
import fr.demo.caisse.util.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Réglages du bouton « i », lus dans /etc/default/storepos-kiosk (les mêmes que l'ancien bouton du paquet kiosque) :
 *   INFO_BUTTON_ENABLED=yes|no     INFO_BUTTON_POS=bottom-right|bottom-left|top-right|top-left     INFO_HOLD_SECONDS=2
 */
data class ReglagesInfo(val actif: Boolean = true, val position: String = "bottom-right", val appuiMs: Int = 2000) {
    val alignement: Alignment
        get() = when (position) {
            "bottom-left" -> Alignment.BottomStart
            "top-right" -> Alignment.TopEnd
            "top-left" -> Alignment.TopStart
            else -> Alignment.BottomEnd
        }

    companion object {
        fun lire(contenu: String): ReglagesInfo {
            val cfg = contenu.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { l -> l.substringBefore('=').trim() to l.substringAfter('=').trim().trim('"', '\'') }
            val actif = cfg["INFO_BUTTON_ENABLED"]?.lowercase() in listOf(null, "yes", "true", "1")
            val position = cfg["INFO_BUTTON_POS"]?.takeIf { it in listOf("bottom-right", "bottom-left", "top-right", "top-left") } ?: "bottom-right"
            val ms = cfg["INFO_HOLD_SECONDS"]?.toDoubleOrNull()?.let { (it * 1000).toInt() }?.coerceIn(500, 10_000) ?: 2000
            return ReglagesInfo(actif, position, ms)
        }

        fun depuisSysteme(): ReglagesInfo = runCatching { lire(File("/etc/default/storepos-kiosk").readText()) }.getOrDefault(ReglagesInfo())
    }
}

/** Sortie de `storepos-kiosk-info` (« Libellé : valeur » par ligne) -> paires. Vide si la commande n'existe pas (poste de développement). */
internal fun lignesInfo(sortie: String): List<Pair<String, String>> =
    sortie.lineSequence().filter { it.contains(':') }.map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }.filter { it.first.isNotEmpty() }.toList()

private fun lireInfoSysteme(): List<Pair<String, String>> = runCatching {
    val p = ProcessBuilder("storepos-kiosk-info").redirectErrorStream(true).start()
    if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching emptyList() }
    lignesInfo(p.inputStream.bufferedReader().readText())
}.getOrDefault(emptyList())

/**
 * Bouton « i » discret : rond, translucide, avec un anneau qui se remplit pendant l'appui maintenu (2 s par défaut).
 * Un appui bref n'ouvre rien (un client ne doit pas l'ouvrir par erreur) et affiche seulement une indication.
 */
@Composable
fun BoutonInfo(state: AppState, reglages: ReglagesInfo, onOuvrir: () -> Unit, modifier: Modifier = Modifier) {
    val progression = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var indice by remember { mutableStateOf(false) }
    LaunchedEffect(indice) { if (indice) { delay(1800); indice = false } }

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(48.dp).shadow(6.dp, CircleShape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                .clip(CircleShape).background(Color(0x8C0B1B34)).border(1.dp, Color(0x40FFFFFF), CircleShape)
                .pointerInput(reglages.appuiMs) {
                    detectTapGestures(
                        onTap = { indice = true },
                        onPress = {
                            val minuteur = scope.launch {
                                progression.snapTo(0f)
                                progression.animateTo(1f, tween(reglages.appuiMs, easing = LinearEasing))
                                onOuvrir()
                            }
                            tryAwaitRelease()
                            minuteur.cancel()
                            scope.launch { progression.animateTo(0f, tween(150)) }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(48.dp)) {
                val e = 3.dp.toPx()
                // anneau de progression
                if (progression.value > 0f) {
                    drawArc(
                        CouleursLogin.Vert, startAngle = -90f, sweepAngle = 360f * progression.value, useCenter = false,
                        topLeft = Offset(e / 2, e / 2), size = Size(size.width - e, size.height - e), style = Stroke(e, cap = StrokeCap.Round),
                    )
                }
                // pictogramme « i » : un point et une barre arrondie
                val c = size.width / 2f
                drawCircle(Color.White.copy(alpha = 0.95f), radius = size.width * 0.055f, center = Offset(c, size.height * 0.31f))
                drawRoundRect(
                    Color.White.copy(alpha = 0.95f), topLeft = Offset(c - size.width * 0.05f, size.height * 0.42f),
                    size = Size(size.width * 0.10f, size.height * 0.28f), cornerRadius = CornerRadius(size.width * 0.05f),
                )
            }
        }
        // indication après un appui bref
        AnimatedVisibility(indice, enter = fadeIn(tween(150)), exit = fadeOut(tween(250)), modifier = Modifier.align(Alignment.CenterStart).padding(end = 60.dp)) {
            Text(
                "Maintenez ${(reglages.appuiMs / 1000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} s",
                fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium,
                modifier = Modifier.width(120.dp).clip(RoundedCornerShape(50)).background(Color(0xCC0B1B34)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Carte d'informations de la caisse (technicien) : système (adresse IP, TeamViewer, versions…) puis application (magasin, réseau, synchronisation). */
@Composable
fun CarteInfoCaisse(state: AppState, onFermer: () -> Unit) {
    var systeme by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) { systeme = withContext(Dispatchers.IO) { lireInfoSysteme() } }
    LaunchedEffect(Unit) { delay(60_000); onFermer() }     // se ferme seule, comme l'ancienne fenêtre

    val etat by state.sync.etat.collectAsState()
    val derniere by state.sync.derniereSync.collectAsState()
    val enAttente by state.sync.enAttente.collectAsState()
    val application = listOf(
        "Application" to "Caisse Démo ${SystemInfo.VERSION}",
        "Magasin" to state.sync.magasin.ifBlank { "non activé" },
        "Caisse" to state.db.config("tpv").orEmpty().ifBlank { "—" },
        "Réseau" to when (etat) { EtatReseau.EN_LIGNE -> "En ligne"; EtatReseau.HORS_LIGNE -> "Hors ligne"; EtatReseau.NON_CONFIGURE -> "Mode local (aucun serveur)" },
        "Dernière synchronisation" to derniere,
        "Tickets en attente d'envoi" to enAttente.toString(),
    )

    Box(
        Modifier.fillMaxSize().background(Color(0xA6000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFermer() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).padding(20.dp).shadow(16.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(CouleursLogin.Menthe), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(24.dp)) {
                        val c = size.width / 2f
                        drawCircle(CouleursLogin.Vert, radius = size.width * 0.07f, center = Offset(c, size.height * 0.24f))
                        drawRoundRect(CouleursLogin.Vert, topLeft = Offset(c - size.width * 0.07f, size.height * 0.38f), size = Size(size.width * 0.14f, size.height * 0.46f), cornerRadius = CornerRadius(size.width * 0.07f))
                    }
                }
                Text("Informations de la caisse", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CouleursLogin.Marine, modifier = Modifier.padding(start = 14.dp).weight(1f))
            }
            val defile = rememberScrollState()
            Column(Modifier.weight(1f, fill = false).verticalScroll(defile), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Rubrique("Poste")
                val s = systeme
                when {
                    s == null -> Text("Lecture en cours…", fontSize = 16.sp, color = CouleursLogin.Gris, modifier = Modifier.padding(vertical = 8.dp))
                    s.isEmpty() -> Text("Informations système indisponibles sur ce poste.", fontSize = 16.sp, color = CouleursLogin.Gris, modifier = Modifier.padding(vertical = 8.dp))
                    else -> s.forEach { (l, v) -> LigneInfo(l, v) }
                }
                Rubrique("Application")
                application.forEach { (l, v) -> LigneInfo(l, v) }
            }
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(CouleursLogin.Vert).clickable { onFermer() },
                contentAlignment = Alignment.Center,
            ) { Text("Fermer", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
        }
    }
}

@Composable
private fun Rubrique(titre: String) {
    Text(titre.uppercase(), fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = CouleursLogin.Vert, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun LigneInfo(libelle: String, valeur: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CouleursLogin.Touche.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(libelle, fontSize = 15.sp, color = CouleursLogin.Gris, modifier = Modifier.width(210.dp))
        Text(valeur.ifBlank { "—" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = CouleursLogin.Marine)
    }
}

/** Calque posé sur l'application : le bouton « i » et, quand il est maintenu, la carte d'informations. */
@Composable
fun CalqueInfo(state: AppState, modifier: Modifier = Modifier) {
    val reglages = remember { ReglagesInfo.depuisSysteme() }
    var ouvert by remember { mutableStateOf(false) }
    if (!reglages.actif) return
    Box(modifier) {
        if (!ouvert) BoutonInfo(state, reglages, onOuvrir = { ouvert = true }, modifier = Modifier.align(reglages.alignement).padding(8.dp))
        if (ouvert) CarteInfoCaisse(state) { ouvert = false }
    }
}
