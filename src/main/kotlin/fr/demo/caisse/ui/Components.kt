package fr.demo.caisse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Barre de défilement large, déplaçable au doigt : sur un écran tactile ce moteur ne fait pas défiler une liste
 * en la faisant glisser, il faut donc une poignée visible (comme sur la caisse d'origine).
 */
@Composable
private fun PoigneeDefilement(adapter: androidx.compose.foundation.v2.ScrollbarAdapter, visible: Boolean) {
    if (!visible) return
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.VerticalScrollbar(
        adapter,
        Modifier.fillMaxHeight().width(18.dp).padding(start = 4.dp),
        style = androidx.compose.foundation.defaultScrollbarStyle().copy(
            minimalHeight = 56.dp, thickness = 14.dp, shape = RoundedCornerShape(7.dp),
            unhoverColor = cs.primary.copy(alpha = 0.55f), hoverColor = cs.primary,
        ),
    )
}

@Composable
fun BarreV(state: androidx.compose.foundation.lazy.LazyListState) =
    PoigneeDefilement(androidx.compose.foundation.rememberScrollbarAdapter(state), state.canScrollForward || state.canScrollBackward)

@Composable
fun BarreV(state: androidx.compose.foundation.lazy.grid.LazyGridState) =
    PoigneeDefilement(androidx.compose.foundation.rememberScrollbarAdapter(state), state.canScrollForward || state.canScrollBackward)

@Composable
fun BarreV(state: androidx.compose.foundation.ScrollState) =
    PoigneeDefilement(androidx.compose.foundation.rememberScrollbarAdapter(state), state.canScrollForward || state.canScrollBackward)

/** Liste verticale avec poignée de défilement tactile. */
@Composable
fun ListeV(modifier: Modifier = Modifier.fillMaxSize(), espace: Dp = 6.dp, contenu: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val etat = androidx.compose.foundation.lazy.rememberLazyListState()
    Row(modifier) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = etat, verticalArrangement = Arrangement.spacedBy(espace), content = contenu)
        BarreV(etat)
    }
}

/** Colonne défilante avec poignée de défilement tactile. */
@Composable
fun ColonneV(espace: Dp = 8.dp, contenu: @Composable ColumnScope.() -> Unit) {
    val etat = androidx.compose.foundation.rememberScrollState()
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(etat), verticalArrangement = Arrangement.spacedBy(espace), content = contenu)
        BarreV(etat)
    }
}

val LocalQuitter = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

enum class Style { PRIMAIRE, TONAL, DANGER, SUCCES, DISCRET, BARRE }

/** Bouton tactile : 56 dp de haut par défaut, coins arrondis, couleur selon le rôle. */
@Composable
fun AppButton(
    texte: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: Style = Style.PRIMAIRE,
    enabled: Boolean = true,
    hauteur: Dp = 56.dp,
    taille: TextUnit = 18.sp,
) {
    val cs = MaterialTheme.colorScheme
    val (fond, texteCouleur) = when (style) {
        Style.PRIMAIRE -> cs.primary to cs.onPrimary
        Style.TONAL -> cs.secondaryContainer to cs.onSecondaryContainer
        Style.DANGER -> cs.error to cs.onError
        Style.SUCCES -> Ext.succes to Color.White
        Style.DISCRET -> Color.Transparent to cs.onSurface
        Style.BARRE -> Ext.surBarre.copy(alpha = 0.14f) to Ext.surBarre
    }
    Button(
        onClick = onClick,
        modifier = modifier.height(hauteur),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = fond, contentColor = texteCouleur,
            disabledContainerColor = cs.surfaceVariant, disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        elevation = null,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        border = if (style == Style.DISCRET) androidx.compose.foundation.BorderStroke(1.dp, cs.outline) else null,
    ) {
        Text(texte, fontSize = taille, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Puce sélectionnable (catégories, filtres, modes). */
@Composable
fun Puce(texte: String, actif: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary, margeH: Dp = 20.dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.height(48.dp)
            .background(if (actif) accent else cs.surface, RoundedCornerShape(24.dp))
            .border(1.dp, if (actif) accent else cs.outline, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(horizontal = margeH),
        contentAlignment = Alignment.Center,
    ) {
        Text(texte, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (actif) Color.White else cs.onSurface, maxLines = 1)
    }
}

@Composable
fun Pastille(texte: String, fond: Color, couleur: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(fond, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(texte, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = couleur, maxLines = 1)
    }
}

@Composable
fun EtatVide(titre: String, detail: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(titre, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (detail.isNotEmpty()) Text(detail, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun Avatar(initiales: String, couleur: Color, taille: Dp = 48.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(taille).background(couleur, CircleShape), contentAlignment = Alignment.Center) {
        Text(initiales, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (taille.value * 0.38f).sp)
    }
}

/** En-tête d'écran : retour, titre, actions à droite. */
@Composable
fun EnTeteEcran(titre: String, onRetour: (() -> Unit)?, sousTitre: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Ext.barre).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onRetour != null) AppButton("‹  Caisse", onRetour, style = Style.BARRE, hauteur = 48.dp, taille = 16.sp)
        Column(Modifier.weight(1f)) {
            Text(titre, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ext.surBarre)
            if (sousTitre.isNotEmpty()) Text(sousTitre, fontSize = 13.sp, color = Ext.surBarreDiscret)
        }
        actions()
    }
}

/** Fenêtre modale dans la même fenêtre (pas de seconde fenêtre système : plus fiable en kiosque). */
@Composable
fun Modale(onFermer: (() -> Unit)?, largeurMax: Dp = 760.dp, contenu: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xA6000000)).clickable(enabled = true) { onFermer?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            modifier = Modifier.widthIn(max = largeurMax).padding(20.dp).clickable(enabled = false) {},
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = contenu)
        }
    }
}

/** Panneau latéral droit (recherche produits/clients…) avec voile sur le reste de l'écran. */
@Composable
fun PanneauLateral(titre: String, onFermer: () -> Unit, largeur: Dp = 560.dp, contenu: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0x99000000)).clickable { onFermer() })
        Column(Modifier.width(largeur).fillMaxHeight().background(cs.surface).border(1.dp, cs.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(titre, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                AppButton("✕", onFermer, style = Style.TONAL, hauteur = 44.dp, modifier = Modifier.width(56.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = contenu)
        }
    }
}

/** Pavé numérique tactile. */
@Composable
fun PaveNumerique(onChiffre: (String) -> Unit, onEffacer: () -> Unit, modifier: Modifier = Modifier, hauteurTouche: Dp = 64.dp) {
    val rangees = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("C", "0", "00"))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rangees.forEach { rangee ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rangee.forEach { k ->
                    AppButton(
                        k, { if (k == "C") onEffacer() else onChiffre(k) }, Modifier.weight(1f),
                        style = if (k == "C") Style.DANGER else Style.TONAL, hauteur = hauteurTouche, taille = 26.sp,
                    )
                }
            }
        }
    }
}

/** Clavier virtuel AZERTY (la caisse n'a pas de clavier physique). */
@Composable
fun ClavierVirtuel(onCaractere: (String) -> Unit, onEffacer: () -> Unit, modifier: Modifier = Modifier, hauteurTouche: Dp = 44.dp) {
    var majuscule by remember { mutableStateOf(false) }
    val lignes = listOf("1234567890", "azertyuiop", "qsdfghjklm", "wxcvbn.-_@:/")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lignes.forEach { l ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                l.forEach { c ->
                    val s = if (majuscule) c.uppercase() else c.toString()
                    AppButton(s, { onCaractere(s) }, Modifier.weight(1f), style = Style.TONAL, hauteur = hauteurTouche, taille = 18.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton(if (majuscule) "ABC" else "abc", { majuscule = !majuscule }, Modifier.weight(1.5f), style = Style.PRIMAIRE, hauteur = hauteurTouche, taille = 16.sp)
            AppButton("Espace", { onCaractere(" ") }, Modifier.weight(4f), style = Style.TONAL, hauteur = hauteurTouche, taille = 16.sp)
            AppButton("⌫", onEffacer, Modifier.weight(1.5f), style = Style.DANGER, hauteur = hauteurTouche, taille = 20.sp)
        }
    }
}

/** Sélecteur de quantité − 1 + */
@Composable
fun SelecteurQuantite(qte: Int, onMoins: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton("−", onMoins, Modifier.width(52.dp), style = Style.TONAL, hauteur = 48.dp, taille = 22.sp)
        Text(qte.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(40.dp))
        AppButton("+", onPlus, Modifier.width(52.dp), style = Style.TONAL, hauteur = 48.dp, taille = 22.sp)
    }
}

/** Contenu qui s'adapte : `compact` vrai sous 1000 dp de large (écrans 1024×768 des caisses). */
@Composable
fun Adaptatif(modifier: Modifier = Modifier.fillMaxSize(), contenu: @Composable (compact: Boolean) -> Unit) {
    BoxWithConstraints(modifier) { contenu(maxWidth < 1000.dp) }
}

@Composable
fun Cadre(modifier: Modifier = Modifier, contenu: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = contenu,
    )
}

@Composable
fun Espace(h: Dp = 0.dp, w: Dp = 0.dp) = Spacer(Modifier.height(h).width(w))

@Composable
fun LigneValeur(libelle: String, valeur: String, gras: Boolean = false, couleur: Color = MaterialTheme.colorScheme.onSurface, taille: TextUnit = 18.sp) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(libelle, fontSize = taille, color = if (gras) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (gras) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.weight(1f))
        Text(valeur, fontSize = taille, fontWeight = FontWeight.Bold, color = couleur)
    }
}

@Composable
fun FillBox(modifier: Modifier = Modifier, contenu: @Composable () -> Unit) = Box(modifier.fillMaxSize()) { contenu() }
