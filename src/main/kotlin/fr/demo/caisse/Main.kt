package fr.demo.caisse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import fr.demo.caisse.ui.VeilleScreen
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fr.demo.caisse.ui.AppState
import fr.demo.caisse.ui.AppTheme
import fr.demo.caisse.ui.AutorisationResponsable
import fr.demo.caisse.ui.LocalQuitter
import fr.demo.caisse.ui.BandeauMessage
import fr.demo.caisse.ui.CaisseScreen
import fr.demo.caisse.ui.ComptageScreen
import fr.demo.caisse.ui.JournalScreen
import fr.demo.caisse.ui.LoginScreen
import fr.demo.caisse.ui.Screen
import fr.demo.caisse.ui.SystemeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() = application {
    val state = remember { AppState.creer(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    // CAISSE_FULLSCREEN=1 : plein écran sans bordure (en kiosque, Openbox le force de toute façon)
    val pleinEcran = System.getenv("CAISSE_FULLSCREEN") == "1"
    val fenetre = rememberWindowState(
        size = DpSize(1280.dp, 800.dp),
        placement = if (pleinEcran) WindowPlacement.Fullscreen else WindowPlacement.Floating,
    )
    val quitter = {
        state.fermer()
        exitApplication()
    }
    // Icône de la fenêtre : le dessin de l'application (src/main/resources/icon.png), jamais celle d'un autre logiciel
    val icone = remember { runCatching { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }.getOrNull() }

    Window(
        onCloseRequest = quitter, title = "Caisse Démo", state = fenetre, undecorated = pleinEcran,
        icon = icone,
        // Lecteur de codes-barres USB : il « tape » le code puis Entrée
        onPreviewKeyEvent = { ev ->
            state.activite()      // toute frappe compte comme une activité (mise en veille)
            if (ev.type == KeyEventType.KeyDown) {
                val entree = ev.key == Key.Enter || ev.key == Key.NumPadEnter
                val c = ev.utf16CodePoint.takeIf { it in 32..0xFFFF }?.toChar()
                state.toucheClavier(c, entree)
            } else false
        },
    ) {
        AppRoot(state, quitter)
    }
}

@Composable
fun AppRoot(state: AppState, onQuitter: () -> Unit) {
    val version by state.sync.versionCatalogue.collectAsState()
    LaunchedEffect(version) { state.rechargerCatalogue() }
    // Mise en veille : vérifiée toutes les 5 secondes ; chaque toucher remet le compteur à zéro
    LaunchedEffect(Unit) { while (true) { delay(5_000); state.verifierVeille() } }

    AppTheme(state.themeMode) {
      CompositionLocalProvider(LocalQuitter provides onQuitter) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); state.activite() } }
        }) {
            when (state.screen) {
                Screen.LOGIN -> LoginScreen(state)
                Screen.CAISSE -> CaisseScreen(state)
                Screen.COMPTAGE -> ComptageScreen(state)
                Screen.JOURNAL -> JournalScreen(state)
                Screen.SYSTEME -> SystemeScreen(state)
            }
            state.autorisation?.let { d -> AutorisationResponsable(state, d.libelle, onValide = { state.fermerAutorisation(); d.action() }, onAnnuler = { state.fermerAutorisation() }) }
            Box(Modifier.align(Alignment.TopCenter)) { BandeauMessage(state) }
            if (state.veille) VeilleScreen(state)
        }
      }
    }
}
