package fr.demo.caisse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.util.SystemInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Menu de la caisse (bouton ☰) : accès au journal, au comptage et à la configuration, apparence, et les actions
 * réservées au responsable (réinitialiser, quitter). On y retrouve ce que l'ancienne page d'accueil proposait,
 * sans passer par un écran de plus avant de vendre.
 */
@Composable
fun MenuLateral(state: AppState, onFermer: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val c = state.caissier
    var confirmerReinit by remember { mutableStateOf(false) }
    var confirmerQuitter by remember { mutableStateOf(false) }
    val quitter = LocalQuitter.current

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(360.dp).fillMaxHeight().background(cs.surface).border(1.dp, cs.outlineVariant)) {
            Row(Modifier.fillMaxWidth().background(Ext.barre).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(c?.initiales.orEmpty(), if (c?.estResponsable == true) Color(0xFFD97706) else cs.primary, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(c?.nom.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ext.surBarre)
                    Text(if (c?.estResponsable == true) "Responsable" else "Caissier", fontSize = 13.sp, color = Ext.surBarreDiscret)
                }
                AppButton("✕", onFermer, Modifier.width(52.dp), style = Style.BARRE, hauteur = 44.dp, taille = 18.sp)
            }
            val defile = rememberScrollState()
            Row(Modifier.weight(1f)) {
            Column(Modifier.weight(1f).verticalScroll(defile).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Rubrique("OPÉRATIONS")
                Entree("Journal de caisse", "${state.nbTicketsJour} ticket(s) aujourd'hui") { onFermer(); state.aller(Screen.JOURNAL) }
                Entree("Comptage de fonds", "Ouverture, contrôle, clôture, mouvements") { onFermer(); state.aller(Screen.COMPTAGE) }
                Rubrique("SYSTÈME")
                Entree("Configuration", "Serveur, activation, synchronisation") { onFermer(); state.aller(Screen.SYSTEME) }
                Column(Modifier.fillMaxWidth().background(cs.background, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Apparence", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeMode.entries.forEach { m -> Box(Modifier.weight(1f)) { Puce(m.libelle, state.themeMode == m, { state.changerTheme(m) }, Modifier.fillMaxWidth(), margeH = 4.dp) } }
                    }
                }
                Column(Modifier.fillMaxWidth().background(cs.background, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mise en veille après", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Veille.CHOIX.forEach { m ->
                            Box(Modifier.weight(1f)) {
                                Puce(Veille.libelle(m), state.veilleMinutes == m, { if (c?.estResponsable == true) state.changerVeille(m) else state.exigerResponsable("Régler la mise en veille") { state.changerVeille(m) } }, Modifier.fillMaxWidth(), margeH = 2.dp)
                            }
                        }
                    }
                    Text("L'écran se verrouille : le code est redemandé, le ticket en cours est conservé.", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Rubrique("RÉSERVÉ RESPONSABLE")
                Entree("Réinitialiser la caisse", "Efface tickets, comptages et clôtures", danger = true, reserve = c?.estResponsable != true) {
                    state.exigerResponsable("Réinitialiser la caisse") { confirmerReinit = true }
                }
                Entree("Quitter l'application", "Retour au système", danger = true, reserve = c?.estResponsable != true) {
                    state.exigerResponsable("Quitter l'application") { confirmerQuitter = true }
                }
            }
            BarreV(defile)
            }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Changer de caissier", { onFermer(); state.verrouiller() }, Modifier.fillMaxWidth(), style = Style.TONAL)
                Text("Caisse Démo ${SystemInfo.VERSION}", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x99000000)).clickable { onFermer() })
    }

    if (confirmerReinit) {
        Modale(onFermer = null, largeurMax = 520.dp) {
            Text("Réinitialiser la caisse ?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Tous les tickets, comptages et clôtures de cette caisse seront supprimés. Les produits, clients et caissiers sont conservés. Action irréversible.", fontSize = 16.sp, color = cs.onSurfaceVariant)
            BoutonMaintenir("Maintenez pour réinitialiser", Style.DANGER) {
                state.db.reinitialiser(); state.viderPanier(); state.rafraichirStats(); state.sync.rafraichirCompteur()
                confirmerReinit = false
                onFermer()
                state.notifier(Niveau.SUCCES, "Caisse réinitialisée")
            }
            AppButton("Annuler", { confirmerReinit = false }, Modifier.fillMaxWidth(), style = Style.TONAL)
        }
    }
    if (confirmerQuitter) {
        Modale(onFermer = null, largeurMax = 480.dp) {
            Text("Quitter l'application ?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("En mode kiosque, le service la relancera automatiquement.", fontSize = 16.sp, color = cs.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton("Annuler", { confirmerQuitter = false }, Modifier.weight(1f), style = Style.TONAL)
                AppButton("Quitter", quitter, Modifier.weight(1f), style = Style.DANGER)
            }
        }
    }
}

@Composable
private fun Rubrique(titre: String) {
    Text(titre, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

@Composable
private fun Entree(titre: String, detail: String, danger: Boolean = false, reserve: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.background).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(titre, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (danger) cs.error else cs.onSurface)
        Text(detail, fontSize = 13.sp, color = cs.onSurfaceVariant)
        if (reserve) Pastille("Code responsable requis", Ext.alerteFond, Ext.alerte, Modifier.padding(top = 4.dp))
    }
}

/** Bouton à maintenir enfoncé pour confirmer une action destructrice (évite le toucher accidentel). */
@Composable
fun BoutonMaintenir(texte: String, style: Style = Style.DANGER, dureeMs: Int = 1500, onConfirme: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var progres by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val fond = if (style == Style.DANGER) cs.error else cs.primary
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(fond.copy(alpha = 0.25f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    progres = 0f
                    val tache = scope.launch {
                        val pas = 30
                        repeat(pas) { i -> delay(dureeMs.toLong() / pas); progres = (i + 1f) / pas }
                        onConfirme()
                        progres = 0f
                    }
                    tryAwaitRelease()
                    tache.cancel()
                    progres = 0f
                })
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth(progres.coerceIn(0f, 1f)).fillMaxSize().background(fond))
        }
        Text(texte, Modifier.fillMaxWidth(), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (progres > 0.5f) Color.White else cs.onSurface)
    }
}
