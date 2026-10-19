package fr.demo.caisse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Réglages et règle de la mise en veille. */
object Veille {
    const val DEFAUT_MINUTES = 5

    /** Délais proposés, en minutes (0 = jamais). */
    val CHOIX = listOf(0, 2, 5, 10, 15)

    fun libelle(minutes: Int) = if (minutes == 0) "Jamais" else "$minutes min"

    /** Vrai quand la dernière activité date d'au moins `minutes` minutes (0 = la veille est désactivée). */
    fun doitDormir(derniereActiviteMs: Long, maintenantMs: Long, minutes: Int): Boolean =
        minutes > 0 && maintenantMs - derniereActiviteMs >= minutes * 60_000L
}

// Positions successives de l'heure à l'écran (en dp, par rapport au centre) : l'image ne reste jamais fixe, ce qui évite de marquer l'écran.
private val DEPLACEMENTS = listOf(0 to 0, -140 to -70, 130 to 60, -100 to 80, 110 to -80)

/**
 * Écran de veille : noir, avec l'heure. Un toucher réveille ; si un caissier est connecté, son code PIN est redemandé
 * (le ticket en cours, les paiements et la session restent dessous, rien n'est perdu).
 */
@Composable
fun VeilleScreen(state: AppState, demarrerParLeCode: Boolean = false) {
    var saisieCode by remember { mutableStateOf(demarrerParLeCode) }
    val caissier = state.caissier
    Box(
        Modifier.fillMaxSize().background(PaletteSombre.FondVeille)
            // consomme les touchers : rien ne doit atteindre la caisse en dessous
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (!saisieCode) { if (caissier == null) state.reveiller() else saisieCode = true }
            },
    ) {
        if (!saisieCode || caissier == null) Horloge() else Deverrouillage(state, caissier) { saisieCode = false }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.HorlogeContenu(maintenant: LocalDateTime) {
    val (dx, dy) = DEPLACEMENTS[maintenant.minute % DEPLACEMENTS.size]
    Column(Modifier.align(Alignment.Center).offset(dx.dp, dy.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(DatesFr.heure(maintenant), fontSize = 120.sp, fontWeight = FontWeight.Light, color = PaletteSombre.Texte.copy(alpha = 0.85f))
        Text(DatesFr.jourComplet(maintenant), fontSize = 22.sp, color = PaletteSombre.TexteDiscret)
        Text("Touchez l'écran pour reprendre", fontSize = 16.sp, color = PaletteSombre.TexteDiscret.copy(alpha = 0.7f), modifier = Modifier.padding(top = 36.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Horloge() {
    var maintenant by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { maintenant = LocalDateTime.now(); delay(5_000) } }
    HorlogeContenu(maintenant)
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Deverrouillage(state: AppState, caissier: fr.demo.caisse.model.Caissier, onRetour: () -> Unit) {
    // Sans activité pendant 30 s, retour à l'horloge
    LaunchedEffect(Unit) { delay(30_000); onRetour() }
    // Le même pavé que la connexion, sur sa carte blanche, posé sur le fond sombre de la veille
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PaletteSombre.FondVeille, PaletteSombre.FondDroite))), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxHeight(0.94f).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Caisse verrouillée", fontSize = 15.sp, color = PaletteSombre.TexteDiscret)
            CarteBlanche(Modifier.fillMaxWidth().weight(1f)) {
                // tailles réduites : la carte du déverrouillage est plus basse que celle de la connexion (sinon la dernière rangée est coupée)
                SaisiePin(state, caissier, compact = true, onRetour = onRetour, onSucces = { state.reveiller() })
            }
            Text(
                "Changer de caissier", fontSize = 15.sp, color = PaletteSombre.TexteDiscret,
                modifier = Modifier.clickable { state.verrouiller(); state.reveiller() }.padding(10.dp),
            )
        }
    }
}

/** Couleurs de l'écran de veille (sombre, pour ne pas éclairer le magasin). */
internal object PaletteSombre {
    val FondVeille = Color(0xFF03090B)
    val FondDroite = Color(0xFF0A1F25)
    val Texte = Color(0xFFF1F7F6)
    val TexteDiscret = Color(0xFF8DB4AE)
}
