package fr.demo.caisse.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.model.Caissier
import fr.demo.caisse.net.EtatReseau
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val LONGUEUR_PIN = 4

// Écran de connexion d'un terminal de caisse : toujours sombre (peu éblouissant en magasin), indépendant du thème de l'application.
/** Palette sombre partagée par la connexion et la veille. */
internal object PaletteSombre {
    val FondPanneau = listOf(Color(0xFF0F5F59), Color(0xFF0B3A40), Color(0xFF071A1F))
    val FondDroite = Color(0xFF0A1F25)
    val FondVeille = Color(0xFF03090B)
    val Accent = Color(0xFF2DD4BF)
    val Ambre = Color(0xFFF59E0B)
    val Texte = Color(0xFFF1F7F6)
    val TexteDiscret = Color(0xFF8DB4AE)
    val Carte = Color(0x12FFFFFF)
    val Trait = Color(0x22FFFFFF)
    val Danger = Color(0xFFF87171)
    val Profil = Color(0xFFBFD9D5)
}
private val FondPanneau = PaletteSombre.FondPanneau
private val FondDroite = PaletteSombre.FondDroite
private val Accent = PaletteSombre.Accent
private val Ambre = PaletteSombre.Ambre
private val Texte = PaletteSombre.Texte
private val TexteDiscret = PaletteSombre.TexteDiscret
private val Carte = PaletteSombre.Carte
private val Trait = PaletteSombre.Trait
private val Danger = PaletteSombre.Danger
private val Profil = PaletteSombre.Profil

@Composable
fun LoginScreen(state: AppState, caissierInitial: Caissier? = null) {
    var choisi by remember { mutableStateOf(caissierInitial) }
    val caissiers = remember(state.produits) { state.db.caissiers() }

    Adaptatif { compact ->
        Row(Modifier.fillMaxSize().background(FondDroite)) {
            PanneauMarque(state, compact, Modifier.weight(if (compact) 0.85f else 1f).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 470.dp).padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val c = choisi
                    if (c == null) ChoixCaissier(caissiers) { choisi = it } else SaisiePin(state, c, compact, onRetour = { choisi = null })
                }
            }
        }
    }
}

@Composable
private fun PanneauMarque(state: AppState, compact: Boolean, modifier: Modifier) {
    val etat by state.sync.etat.collectAsState()
    var maintenant by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { maintenant = LocalDateTime.now(); delay(10_000) } }

    Box(modifier.background(Brush.linearGradient(FondPanneau))) {
        Column(Modifier.padding(if (compact) 36.dp else 48.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Accent, Color(0xFF0F766E)))), contentAlignment = Alignment.Center) {
                    Text("C", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color(0xFF042F2E))
                }
                Text("Caisse Démo", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Texte, modifier = Modifier.padding(start = 14.dp))
                Spacer(Modifier.weight(1f))
                PastilleReseau(etat)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bon retour", fontSize = if (compact) 40.sp else 46.sp, fontWeight = FontWeight.SemiBold, color = Texte)
                Text("Connectez-vous pour ouvrir votre caisse.", fontSize = 19.sp, color = Texte.copy(alpha = 0.78f))
            }
            Column {
                Text(maintenant.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = if (compact) 54.sp else 62.sp, fontWeight = FontWeight.Light, color = Texte)
                Text(
                    maintenant.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRENCH).replaceFirstChar { it.uppercase() } + " " +
                        maintenant.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)),
                    fontSize = 18.sp, color = Texte.copy(alpha = 0.72f),
                )
                if (etat == EtatReseau.NON_CONFIGURE) {
                    Text("Aucun serveur configuré : les ventes restent sur cette caisse.", fontSize = 14.sp, color = TexteDiscret, modifier = Modifier.padding(top = 14.dp))
                } else if (state.sync.magasin.isNotBlank()) {
                    Text(state.sync.magasin, fontSize = 14.sp, color = TexteDiscret, modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
    }
}

/** État de la liaison avec le serveur (SaaS). « Mode autonome » : aucun serveur n'est configuré, la caisse vend seule et ne transmet rien. */
@Composable
private fun PastilleReseau(etat: EtatReseau) {
    val (couleur, libelle) = when (etat) {
        EtatReseau.EN_LIGNE -> Color(0xFF4ADE80) to "En ligne"
        EtatReseau.HORS_LIGNE -> Ambre to "Hors ligne"
        EtatReseau.NON_CONFIGURE -> TexteDiscret to "Mode autonome"
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(couleur, CircleShape))
        Text(libelle, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Texte, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ChoixCaissier(caissiers: List<Caissier>, onChoix: (Caissier) -> Unit) {
    Text("Qui est à la caisse ?", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Texte)
    Text("Sélectionnez votre profil.", fontSize = 17.sp, color = TexteDiscret)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(caissiers, key = { it.id }) { c ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Carte).border(1.dp, Trait, RoundedCornerShape(16.dp))
                    .clickable { onChoix(c) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AvatarProfil(c.estResponsable, 54.dp)
                Column(Modifier.weight(1f)) {
                    Text(c.libelleConnexion, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Texte)
                    Text(if (c.estResponsable) "Accès responsable" else "Accès caisse", fontSize = 14.sp, color = if (c.estResponsable) Ambre.copy(alpha = 0.9f) else TexteDiscret)
                }
                Text("›", fontSize = 30.sp, color = TexteDiscret)
            }
        }
    }
}

/** Profil anonyme : une silhouette, pas d'initiales (l'identité réelle n'est pas affichée à la connexion). */
@Composable
private fun AvatarProfil(manager: Boolean, taille: Dp) {
    val couleur = if (manager) Ambre else Profil
    Box(Modifier.size(taille).clip(CircleShape).background(couleur.copy(alpha = 0.12f)).border(1.5.dp, couleur.copy(alpha = 0.45f), CircleShape)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            drawCircle(couleur.copy(alpha = 0.85f), radius = w * 0.16f, center = Offset(w / 2f, w * 0.38f))
            drawArc(couleur.copy(alpha = 0.85f), startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.2f, w * 0.60f), size = Size(w * 0.6f, w * 0.5f))
        }
    }
}

/** Saisie du code d'un caissier. Réussite : connexion (écran de connexion) ou réveil (écran de veille). */
@Composable
internal fun SaisiePin(state: AppState, caissier: Caissier, compact: Boolean, onRetour: () -> Unit, onSucces: (Caissier) -> Unit = { state.connecter(it) }) {
    var pin by remember { mutableStateOf("") }
    var erreurs by remember { mutableStateOf(0) }       // compteur : chaque faute relance l'animation
    var erreur by remember { mutableStateOf(false) }
    val secousse = remember { Animatable(0f) }
    LaunchedEffect(erreurs) {
        if (erreurs > 0) {
            repeat(3) { secousse.animateTo(16f, tween(45)); secousse.animateTo(-16f, tween(45)) }
            secousse.animateTo(0f, tween(45))
        }
    }

    fun saisir(chiffre: String) {
        erreur = false
        if (pin.length >= LONGUEUR_PIN) return
        pin += chiffre
        if (pin.length == LONGUEUR_PIN) {
            val ok = state.db.verifierPinCaissier(caissier.id, pin)
            if (ok != null) onSucces(ok) else { erreur = true; erreurs++; pin = "" }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AvatarProfil(caissier.estResponsable, 58.dp)
        Column {
            Text(caissier.libelleConnexion, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Texte)
            Text(if (erreur) "Code incorrect, réessayez" else "Saisissez votre code", fontSize = 16.sp, color = if (erreur) Danger else TexteDiscret, fontWeight = if (erreur) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
    Row(Modifier.fillMaxWidth().offset { IntOffset(secousse.value.roundToInt(), 0) }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
        repeat(LONGUEUR_PIN) { i ->
            val rempli = i < pin.length
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .background(if (rempli) (if (erreur) Danger else Accent) else Color.Transparent)
                    .border(2.dp, if (rempli) Color.Transparent else if (erreur) Danger else Color.White.copy(alpha = 0.35f), CircleShape),
            )
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        PavePin(
            taille = if (compact) 72.dp else 80.dp,
            onChiffre = { saisir(it) },
            onEffacer = { if (pin.isNotEmpty()) pin = pin.dropLast(1); erreur = false },
            onRetour = onRetour,
        )
    }
}

/** Pavé de code PIN : touches rondes, chiffres fins, « Retour » = changer de caissier, « ⌫ » = effacer un chiffre. */
@Composable
private fun PavePin(taille: Dp, onChiffre: (String) -> Unit, onEffacer: () -> Unit, onRetour: () -> Unit) {
    val rangees = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Retour", "0", "⌫"))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rangees.forEach { rangee ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                rangee.forEach { k ->
                    val action = k == "Retour" || k == "⌫"
                    Box(
                        Modifier.size(taille).clip(CircleShape)
                            .background(if (action) Color.Transparent else Carte)
                            .border(1.dp, if (action) Color.Transparent else Trait, CircleShape)
                            .clickable { when (k) { "Retour" -> onRetour(); "⌫" -> onEffacer(); else -> onChiffre(k) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            k, fontSize = if (k == "Retour") 16.sp else if (action) 28.sp else 32.sp,
                            fontWeight = if (k == "Retour") FontWeight.Medium else if (action) FontWeight.Normal else FontWeight.Light,
                            color = if (action) TexteDiscret else Texte,
                        )
                    }
                }
            }
        }
    }
}
