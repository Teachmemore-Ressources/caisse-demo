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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.model.Caissier
import fr.demo.caisse.net.EtatReseau
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.roundToInt

private const val LONGUEUR_PIN = 4

/** Couleurs de l'écran de connexion : clair, vert et bleu marine, comme la maquette du magasin. */
internal object CouleursLogin {
    val Vert = Color(0xFF0A9A62)
    val VertFonce = Color(0xFF087F50)
    val Menthe = Color(0xFFE3F6EC)
    val Marine = Color(0xFF0B1B34)
    val Gris = Color(0xFF5C6B7F)
    val FondDroite = Color(0xFFF3F5F8)
    val Touche = Color(0xFFF0F3F6)
    val Trait = Color(0xFFC9D1DA)
    val Danger = Color(0xFFD64545)
    val Ambre = Color(0xFFD98200)
    val AmbreFond = Color(0xFFFFF0D5)
}
private val Vert = CouleursLogin.Vert
private val Menthe = CouleursLogin.Menthe
private val Marine = CouleursLogin.Marine
private val Gris = CouleursLogin.Gris
private val Touche = CouleursLogin.Touche
private val Danger = CouleursLogin.Danger
private val Ambre = CouleursLogin.Ambre

@Composable
fun LoginScreen(state: AppState, caissierInitial: Caissier? = null) {
    var choisi by remember { mutableStateOf(caissierInitial) }
    val caissiers = remember(state.produits) { state.db.caissiers() }

    Adaptatif { compact ->
        Row(Modifier.fillMaxSize().background(CouleursLogin.FondDroite)) {
            PanneauMarque(state, compact, Modifier.weight(if (compact) 1.55f else 1.85f).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight().padding(if (compact) 14.dp else 26.dp), contentAlignment = Alignment.Center) {
                CarteBlanche(Modifier.fillMaxWidth().widthIn(max = 500.dp).fillMaxHeight()) {
                    val c = choisi
                    if (c == null) ChoixCaissier(caissiers, compact) { choisi = it } else SaisiePin(state, c, compact, onRetour = { choisi = null })
                }
            }
        }
    }
}

/** Carte blanche aux coins arrondis, avec une ombre légère : contient le choix du profil et le pavé du code. */
@Composable
internal fun CarteBlanche(modifier: Modifier = Modifier, contenu: @Composable () -> Unit) {
    Box(
        modifier.shadow(10.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
            .clip(RoundedCornerShape(30.dp)).background(Color.White).padding(horizontal = 26.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) { contenu() }
}

@Composable
private fun PanneauMarque(state: AppState, compact: Boolean, modifier: Modifier) {
    val etat by state.sync.etat.collectAsState()
    var maintenant by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { maintenant = LocalDateTime.now(); delay(10_000) } }
    // Marque du magasin (réglable dans la base : « marque_nom », « marque_slogan ») ; le dernier mot est mis en vert
    val marque = remember { state.db.config("marque_nom").orEmpty().ifBlank { "Test Market" } }
    val slogan = remember { state.db.config("marque_slogan").orEmpty().ifBlank { "TOUT POUR VOTRE QUOTIDIEN" } }
    val mots = marque.trim().split(" ")
    val caisse = state.db.config("tpv").orEmpty()
    val magasin = state.sync.magasin

    Box(modifier.background(Color.White)) {
        FondMagasin(Modifier.fillMaxSize())
        // voile blanc : opaque à gauche (texte lisible), transparent à droite (le décor apparaît), comme la maquette
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.White, 0.40f to Color.White.copy(alpha = 0.97f), 0.70f to Color.White.copy(alpha = 0.40f), 1f to Color.Transparent),
            ),
        )
        Column(Modifier.padding(horizontal = if (compact) 32.dp else 52.dp, vertical = if (compact) 28.dp else 40.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                LogoPanier(if (compact) 52.dp else 62.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Marine)) { append(if (mots.size > 1) mots.dropLast(1).joinToString(" ") + " " else mots.first()) }
                            if (mots.size > 1) withStyle(SpanStyle(color = Vert)) { append(mots.last()) }
                        },
                        fontSize = if (compact) 34.sp else 44.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(slogan, fontSize = if (compact) 10.sp else 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium, color = Marine.copy(alpha = 0.8f), modifier = Modifier.padding(start = 3.dp))
                }
                Spacer(Modifier.weight(1f))
                PastilleReseau(etat)
            }
            Column {
                Text("Bon retour", fontSize = if (compact) 50.sp else 66.sp, fontWeight = FontWeight.Bold, color = Marine, lineHeight = if (compact) 54.sp else 70.sp)
                Text("sur votre caisse", fontSize = if (compact) 50.sp else 66.sp, fontWeight = FontWeight.Bold, color = Vert, lineHeight = if (compact) 54.sp else 70.sp)
                Text(
                    "Connectez-vous pour accéder à votre espace de vente et à toutes les fonctionnalités.",
                    fontSize = if (compact) 17.sp else 21.sp, color = Marine.copy(alpha = 0.85f), modifier = Modifier.padding(top = 18.dp).widthIn(max = 540.dp),
                )
                Row(Modifier.padding(top = if (compact) 22.dp else 34.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 22.dp else 38.dp)) {
                    TuileFonction(Pictogramme.ECLAIR, "Vente rapide", "Simple et efficace", compact)
                    TuileFonction(Pictogramme.BARRES, "Stock en temps réel", "Produits et ventes", compact)
                    TuileFonction(Pictogramme.BOUCLIER, "Sécurisé", "Données protégées", compact)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(DatesFr.heure(maintenant), fontSize = if (compact) 54.sp else 68.sp, fontWeight = FontWeight.SemiBold, color = Marine)
                Text(DatesFr.jourComplet(maintenant), fontSize = if (compact) 17.sp else 21.sp, color = Marine.copy(alpha = 0.85f))
                if (magasin.isNotBlank()) {
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconePosition(22.dp, Marine)
                        Text(magasin, fontSize = if (compact) 15.sp else 18.sp, color = Marine.copy(alpha = 0.9f))
                        if (caisse.isNotBlank()) {
                            Text("|", fontSize = 18.sp, color = Marine.copy(alpha = 0.5f))
                            Text(caisse, fontSize = if (compact) 15.sp else 18.sp, color = Marine.copy(alpha = 0.9f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TuileFonction(type: Pictogramme, titre: String, detail: String, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(if (compact) 54.dp else 66.dp).clip(RoundedCornerShape(18.dp)).background(Menthe), contentAlignment = Alignment.Center) {
            IconeFonction(type, if (compact) 26.dp else 32.dp)
        }
        Text(titre, fontSize = if (compact) 14.sp else 17.sp, fontWeight = FontWeight.Bold, color = Marine, modifier = Modifier.padding(top = 8.dp))
        Text(detail, fontSize = if (compact) 12.sp else 14.sp, color = Gris)
    }
}

/** État de la caisse en haut à droite. « Caisse opérationnelle » : elle vend, avec ou sans serveur ; « Hors ligne » : un serveur est configuré mais injoignable. */
@Composable
private fun PastilleReseau(etat: EtatReseau) {
    val (fond, point, couleur, libelle) = when (etat) {
        EtatReseau.EN_LIGNE -> Quad(Menthe, Vert, CouleursLogin.VertFonce, "Caisse en ligne")
        EtatReseau.NON_CONFIGURE -> Quad(Menthe, Vert, CouleursLogin.VertFonce, "Caisse opérationnelle")
        EtatReseau.HORS_LIGNE -> Quad(CouleursLogin.AmbreFond, Ambre, Color(0xFF8A5300), "Hors ligne")
    }
    Row(Modifier.clip(RoundedCornerShape(50)).background(fond).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(point, CircleShape))
        Text(libelle, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = couleur, modifier = Modifier.padding(start = 10.dp))
    }
}

private data class Quad(val a: Color, val b: Color, val c: Color, val d: String)

@Composable
private fun ChoixCaissier(caissiers: List<Caissier>, compact: Boolean, onChoix: (Caissier) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Qui est à la caisse ?", fontSize = if (compact) 26.sp else 32.sp, fontWeight = FontWeight.Bold, color = Marine, textAlign = TextAlign.Center)
        Text("Sélectionnez votre profil.", fontSize = 17.sp, color = Gris, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(caissiers, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Touche).clickable { onChoix(c) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AvatarProfil(c.estResponsable, 58.dp)
                    Column(Modifier.weight(1f)) {
                        Text(c.libelleConnexion, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Marine)
                        Text(if (c.estResponsable) "Accès responsable" else "Accès caisse", fontSize = 14.sp, color = if (c.estResponsable) Ambre else Gris)
                    }
                    Text("›", fontSize = 30.sp, color = Gris)
                }
            }
        }
    }
}

/** Profil anonyme : une silhouette, pas d'initiales (l'identité réelle n'est pas affichée à la connexion). */
@Composable
internal fun AvatarProfil(manager: Boolean, taille: Dp) {
    val couleur = if (manager) Ambre else Vert
    val fond = if (manager) CouleursLogin.AmbreFond else Menthe
    Box(Modifier.size(taille).clip(CircleShape).background(fond)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            drawCircle(couleur, radius = w * 0.17f, center = Offset(w / 2f, w * 0.38f))
            drawArc(couleur, startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = Offset(w * 0.22f, w * 0.60f), size = Size(w * 0.56f, w * 0.44f))
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

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
        AvatarProfil(caissier.estResponsable, if (compact) 92.dp else 120.dp)
        Text(caissier.libelleConnexion, fontSize = if (compact) 30.sp else 36.sp, fontWeight = FontWeight.Bold, color = Marine, modifier = Modifier.padding(top = 4.dp))
        Text(
            if (erreur) "Code incorrect, réessayez" else "Saisissez votre code d'accès", fontSize = 17.sp,
            color = if (erreur) Danger else Gris, fontWeight = if (erreur) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center,
        )
        Row(Modifier.offset { IntOffset(secousse.value.roundToInt(), 0) }.padding(vertical = if (compact) 6.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 26.dp)) {
            repeat(LONGUEUR_PIN) { i ->
                val rempli = i < pin.length
                Box(
                    Modifier.size(if (compact) 22.dp else 26.dp).clip(CircleShape)
                        .background(if (rempli) (if (erreur) Danger else Vert) else Color.White)
                        .border(2.dp, if (rempli) Color.Transparent else if (erreur) Danger else CouleursLogin.Trait, CircleShape),
                )
            }
        }
        PavePin(
            hauteur = if (compact) 66.dp else 82.dp,
            onChiffre = { saisir(it) },
            onEffacer = { if (pin.isNotEmpty()) pin = pin.dropLast(1); erreur = false },
            onRetour = onRetour,
        )
    }
}

/** Pavé de code : touches arrondies grises, chiffres en bleu marine. « Retour » = changer de caissier, « ⌫ » = effacer un chiffre. */
@Composable
private fun PavePin(hauteur: Dp, onChiffre: (String) -> Unit, onEffacer: () -> Unit, onRetour: () -> Unit) {
    val rangees = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Retour", "0", "⌫"))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rangees.forEach { rangee ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rangee.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(hauteur).clip(RoundedCornerShape(hauteur / 2)).background(Touche)
                            .clickable { when (k) { "Retour" -> onRetour(); "⌫" -> onEffacer(); else -> onChiffre(k) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            k, fontSize = if (k == "Retour") 18.sp else if (k == "⌫") 28.sp else 34.sp,
                            fontWeight = if (k == "Retour") FontWeight.SemiBold else FontWeight.Medium, color = Marine,
                        )
                    }
                }
            }
        }
    }
}
