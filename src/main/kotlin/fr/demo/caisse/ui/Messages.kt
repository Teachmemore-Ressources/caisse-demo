package fr.demo.caisse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.model.Caissier
import kotlinx.coroutines.delay

/** Bandeau de message en haut de l'écran. Les messages BLOQUANT restent jusqu'à ce qu'on les ferme. */
@Composable
fun BandeauMessage(state: AppState) {
    val m = state.message ?: return
    LaunchedEffect(m.id) {
        if (m.niveau != Niveau.BLOQUANT) {
            delay(if (m.niveau == Niveau.ERREUR) 6000 else 4000)
            if (state.message?.id == m.id) state.fermerMessage()
        }
    }
    val cs = MaterialTheme.colorScheme
    val (fond, couleur, etiquette) = when (m.niveau) {
        Niveau.INFO -> Triple(Ext.infoFond, Ext.info, "INFO")
        Niveau.SUCCES -> Triple(Ext.succesFond, Ext.succes, "SUCCÈS")
        Niveau.ERREUR -> Triple(cs.errorContainer, cs.error, "ERREUR")
        Niveau.BLOQUANT -> Triple(cs.error, cs.onError, "BLOQUANT")
    }
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        Surface(shape = RoundedCornerShape(14.dp), color = fond, shadowElevation = 8.dp, modifier = Modifier.widthIn(max = 720.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(12.dp).background(couleur, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(etiquette + (m.code?.let { " · $it" } ?: ""), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = couleur)
                    Text(m.texte, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (m.niveau == Niveau.BLOQUANT) cs.onError else cs.onSurface)
                }
                AppButton("Fermer", { state.fermerMessage() }, style = Style.TONAL, hauteur = 40.dp, taille = 14.sp)
            }
        }
    }
}

/** Autorisation « responsable » : un responsable saisit son code pour valider une action protégée. */
@Composable
fun AutorisationResponsable(state: AppState, action: String, onValide: (Caissier) -> Unit, onAnnuler: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var erreur by remember { mutableStateOf("") }

    Modale(onFermer = null, largeurMax = 440.dp) {
        Text("Réservé responsable", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(action, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(4) { i ->
                Box(Modifier.padding(horizontal = 8.dp).size(20.dp).background(if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
            }
        }
        Text(erreur.ifEmpty { "Code d'un responsable" }, fontSize = 15.sp, color = if (erreur.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        PaveNumerique(
            onChiffre = { d ->
                erreur = ""
                pin = (pin + d).take(4)
                if (pin.length == 4) {
                    val c = state.db.verifierPin(pin)
                    if (c != null && c.estResponsable) onValide(c) else {
                        erreur = if (c == null) "Code incorrect" else "Ce compte n'est pas responsable"
                        pin = ""
                    }
                }
            },
            onEffacer = { pin = ""; erreur = "" },
            hauteurTouche = 56.dp,
        )
        AppButton("Annuler", onAnnuler, Modifier.fillMaxWidth(), style = Style.TONAL)
    }
}
