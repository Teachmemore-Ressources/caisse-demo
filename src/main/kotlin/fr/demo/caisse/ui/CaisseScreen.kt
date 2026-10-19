package fr.demo.caisse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.domain.Money
import fr.demo.caisse.model.Produit
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.model.TypeLigne
import fr.demo.caisse.net.EtatReseau
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private enum class Panneau { RECHERCHE, CLIENT }

/**
 * Écran de caisse, disposition classique des caisses tactiles : le ticket et son pavé numérique à gauche
 * (Qté, % Remise, Prix, ±, gros bouton Paiement), le catalogue à droite (catégories puis produits).
 */
@Composable
fun CaisseScreen(state: AppState, categorieInitiale: String? = null) {
    var categorie by remember { mutableStateOf(categorieInitiale) }
    var panneau by remember { mutableStateOf<Panneau?>(null) }
    var menu by remember { mutableStateOf(false) }
    var paiement by remember { mutableStateOf(false) }
    var remise by remember { mutableStateOf(false) }
    var viderDemande by remember { mutableStateOf(false) }
    var ticketEncaisse by remember { mutableStateOf<Ticket?>(null) }

    val modalOuverte = panneau != null || menu || paiement || remise || viderDemande || ticketEncaisse != null || state.autorisation != null
    // Le lecteur de codes-barres n'écoute que lorsqu'aucune fenêtre n'est ouverte
    LaunchedEffect(modalOuverte) { state.scanActif = !modalOuverte }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BarreCaisse(state, onMenu = { menu = true })
            Adaptatif { compact ->
                Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanneauTicket(
                        state, compact,
                        onPayer = { paiement = true },
                        onRemise = { remise = true },
                        onClient = { panneau = Panneau.CLIENT },
                        onVider = { viderDemande = true },
                        modifier = Modifier.weight(if (compact) 1f else 0.9f).fillMaxHeight(),
                    )
                    Catalogue(state, categorie, { categorie = it }, { panneau = Panneau.RECHERCHE }, Modifier.weight(if (compact) 1.15f else 1.35f).fillMaxHeight())
                }
            }
        }

        when (panneau) {
            Panneau.RECHERCHE -> PanneauRechercheProduits(state, onFermer = { panneau = null })
            Panneau.CLIENT -> PanneauRechercheClients(state, onFermer = { panneau = null })
            null -> {}
        }
        if (menu) MenuLateral(state, onFermer = { menu = false })
        if (remise) {
            DialogueRemise(
                state,
                onFermer = { remise = false },
                onDemanderResponsable = { cents -> state.exigerResponsable("Remise supérieure à 20 % du ticket") { state.remiseTicket(cents); remise = false } },
            )
        }
        if (viderDemande) {
            Modale(onFermer = null, largeurMax = 460.dp) {
                Text("Vider le ticket ?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${state.panier.nbArticles} article(s) seront retirés du ticket en cours.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton("Annuler", { viderDemande = false }, Modifier.weight(1f), style = Style.TONAL)
                    AppButton("Vider", { state.viderPanier(); viderDemande = false }, Modifier.weight(1f), style = Style.DANGER)
                }
            }
        }
        if (paiement) {
            EcranPaiement(
                state,
                onAnnuler = { paiement = false },
                onTermine = { t -> paiement = false; ticketEncaisse = t },
            )
        }
        ticketEncaisse?.let { t -> DialogueImpression(state, t) { ticketEncaisse = null } }
    }
}

// ---- barre supérieure -------------------------------------------------------------------------------

@Composable
private fun BarreCaisse(state: AppState, onMenu: () -> Unit) {
    val etat by state.sync.etat.collectAsState()
    val enAttente by state.sync.enAttente.collectAsState()
    var heure by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { heure = LocalTime.now(); delay(10_000) } }

    Adaptatif(Modifier.fillMaxWidth()) { compact ->
        Row(
            Modifier.fillMaxWidth().background(Ext.barre).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppButton("☰", onMenu, Modifier.width(56.dp), style = Style.BARRE, hauteur = 48.dp, taille = 24.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text("C", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Text("Caisse Démo", color = Ext.surBarre, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f))
            if (!compact) {
                state.dernierTicket?.let { d ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Dernier ticket", fontSize = 12.sp, color = Ext.surBarreDiscret)
                        Text("n° ${d.numero} · ${Money.format(d.totalCents)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ext.surBarre)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Tickets du jour", fontSize = 12.sp, color = Ext.surBarreDiscret)
                    Text("${state.nbTicketsJour}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ext.surBarre)
                }
            }
            val (couleur, libelle) = when (etat) {
                EtatReseau.EN_LIGNE -> Color(0xFF4ADE80) to "En ligne"
                EtatReseau.HORS_LIGNE -> Color(0xFFFBBF24) to "Hors ligne"
                EtatReseau.NON_CONFIGURE -> Ext.surBarreDiscret to "Mode autonome"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(couleur, CircleShape))
                Text(libelle + if (enAttente > 0) " · $enAttente" else "", fontSize = 14.sp, color = Ext.surBarre)
            }
            Row(
                Modifier.background(Ext.surBarre.copy(alpha = 0.12f), RoundedCornerShape(24.dp)).padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(state.caissier?.initiales.orEmpty(), if (state.caissier?.estResponsable == true) Color(0xFFD97706) else MaterialTheme.colorScheme.primary, 36.dp)
                if (!compact) Text(state.caissier?.nom.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ext.surBarre, maxLines = 1)
            }
            Text(heure.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 22.sp, fontWeight = FontWeight.Light, color = Ext.surBarre)
        }
    }
}

// ---- catalogue (à droite) -----------------------------------------------------------------------------

@Composable
private fun Catalogue(state: AppState, categorie: String?, onCategorie: (String?) -> Unit, onRecherche: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val categories = remember(state.produits) { state.produits.map { it.categorie }.distinct() }
    val actif = categorie?.takeIf { it in categories }
    val visibles = remember(state.produits, actif) { if (actif == null) emptyList() else state.produits.filter { it.categorie == actif } }
    val quantites = state.panier.lignes.groupBy { it.code }.mapValues { (_, ls) -> ls.sumOf { it.qte } }

    Column(modifier.background(cs.surface, RoundedCornerShape(16.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // fil d'Ariane + recherche
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Catégories", { onCategorie(null) }, style = if (actif == null) Style.PRIMAIRE else Style.TONAL, hauteur = 48.dp, taille = 16.sp)
            if (actif != null) {
                Text("›", fontSize = 24.sp, color = cs.onSurfaceVariant)
                Box(Modifier.height(48.dp).background(accentCategorie(actif), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(actif, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.weight(1f))
            Row(
                Modifier.width(250.dp).height(48.dp).background(cs.background, RoundedCornerShape(14.dp)).border(1.dp, cs.outline, RoundedCornerShape(14.dp))
                    .clickable(onClick = onRecherche).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("⌕", fontSize = 22.sp, color = cs.onSurfaceVariant)
                Text("Rechercher", fontSize = 16.sp, color = cs.onSurfaceVariant, maxLines = 1)
            }
        }

        val etatCategories = rememberLazyGridState()
        val etatProduits = rememberLazyGridState()
        if (actif == null) {
          Row(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                GridCells.Adaptive(180.dp), Modifier.weight(1f).fillMaxHeight(), state = etatCategories,
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(categories, key = { it }) { c ->
                    val accent = accentCategorie(c)
                    val n = state.produits.count { it.categorie == c }
                    val dansTicket = state.produits.filter { it.categorie == c }.sumOf { quantites[it.code] ?: 0 }
                    Box(
                        Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(16.dp)).background(accent).clickable { onCategorie(c) }.padding(14.dp),
                    ) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(c, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 22.sp)
                            Text("$n produit${if (n > 1) "s" else ""}", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                        }
                        if (dansTicket > 0) {
                            Box(Modifier.align(Alignment.TopEnd).size(28.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                Text("$dansTicket", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            BarreV(etatCategories)
          }
        } else {
          Row(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                GridCells.Adaptive(150.dp), Modifier.weight(1f).fillMaxHeight(), state = etatProduits,
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 6.dp),
            ) {
                items(visibles, key = { it.code }) { p -> CarteProduit(p, quantites[p.code] ?: 0, state.modeRetour) { state.ajouter(p) } }
            }
            BarreV(etatProduits)
          }
        }
    }
}

@Composable
private fun CarteProduit(p: Produit, qte: Int, retour: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accent = accentCategorie(p.categorie)
    val initiales = p.nom.split(' ', '-', '\'').filter { it.length > 1 }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { p.nom.take(2).uppercase() }
    Column(
        Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(16.dp)).background(cs.surface)
            .border(if (qte > 0) 2.dp else 1.dp, if (qte > 0) accent else cs.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(88.dp).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text(initiales, fontSize = 36.sp, fontWeight = FontWeight.Black, color = accent)
            if (qte > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$qte", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(p.nom, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Text(Money.format(p.prixCents), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (retour) cs.error else cs.primary)
        }
    }
}

// ---- ticket et pavé numérique (à gauche) ----------------------------------------------------------------

@Composable
private fun PanneauTicket(
    state: AppState,
    compact: Boolean,
    onPayer: () -> Unit,
    onRemise: () -> Unit,
    onClient: () -> Unit,
    onVider: () -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val panier = state.panier
    val liste = rememberLazyListState()
    LaunchedEffect(state.selection, panier.lignes.size) { state.selection?.takeIf { it in panier.lignes.indices }?.let { liste.animateScrollToItem(it) } }
    val tva = panier.ventilationTva().sumOf { it.tvaCents }

    Column(
        modifier.background(cs.surface, RoundedCornerShape(16.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // en-tête du ticket
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (state.modeRetour) "Retour · ticket n° ${state.prochainNumero}" else "Ticket n° ${state.prochainNumero}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (state.modeRetour) cs.error else cs.onSurface)
                if (panier.nbArticles > 0) Text("${panier.nbArticles} article${if (panier.nbArticles > 1) "s" else ""}", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
            Row(
                Modifier.background(if (state.client != null) cs.primaryContainer else cs.surfaceVariant, RoundedCornerShape(12.dp)).clickable(onClick = onClient).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.client?.nom ?: "Client", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (state.client != null) cs.onPrimaryContainer else cs.onSurface)
                state.client?.let { Pastille("${it.points} pts", Ext.infoFond, Ext.info) }
            }
        }

        // lignes
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (panier.estVide) {
                EtatVide("Ticket vide", "Touchez un produit\nou scannez un code-barres")
            } else {
                Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = liste, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(panier.lignes) { i, l ->
                        val choisie = state.selection == i
                        val retour = l.type == TypeLigne.RETOUR
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (choisie) cs.primaryContainer else Color.Transparent)
                                .clickable { state.selectionner(i) }.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.width(4.dp).height(36.dp).background(if (choisie) cs.primary else Color.Transparent, RoundedCornerShape(2.dp)))
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (retour) Pastille("RETOUR", cs.errorContainer, cs.error)
                                    Text(l.nom, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    "${l.qte} × ${Money.format(l.prixCents)}" + if (l.remisePct > 0) "  ·  remise -${l.remisePct} %" else "",
                                    fontSize = 13.sp, color = if (l.remisePct > 0) Ext.succes else cs.onSurfaceVariant,
                                )
                            }
                            Text(Money.format(l.totalCents), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (retour) cs.error else cs.onSurface)
                        }
                    }
                }
                BarreV(liste)
                }
            }
        }

        // total
        Row(Modifier.fillMaxWidth().background(cs.background, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Total", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                val sous = if (panier.remiseEffectiveCents > 0) "Remise -${Money.format(panier.remiseEffectiveCents)} · " else ""
                Text(sous + "dont TVA ${Money.format(tva)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            Text(Money.format(panier.totalCents), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = if (panier.estRetour) cs.error else cs.primary)
        }

        // fonctions
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val h = 44.dp
            AppButton("Remise", onRemise, Modifier.weight(1f), style = Style.TONAL, hauteur = h, taille = 14.sp, enabled = !panier.estVide && !panier.estRetour)
            AppButton(
                if (state.modeRetour) "Retour ✓" else "Retour",
                { if (state.modeRetour) state.activerRetour(false) else state.exigerResponsable("Passer en mode retour") { state.activerRetour(true) } },
                Modifier.weight(1f), style = if (state.modeRetour) Style.DANGER else Style.TONAL, hauteur = h, taille = 14.sp,
            )
            AppButton("Vider", onVider, Modifier.weight(1f), style = Style.TONAL, hauteur = h, taille = 14.sp, enabled = !panier.estVide)
        }

        // pavé numérique à la manière des caisses : chiffres, puis Qté / % Remise / Prix
        val hauteurTouche = if (compact) 46.dp else 52.dp
        Row(Modifier.fillMaxWidth().height(hauteurTouche * 4 + 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BoutonPaiement(panier.totalCents, panier.estVide, Modifier.weight(1.05f).fillMaxHeight(), onPayer)
            Column(Modifier.weight(3.6f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val rangees = listOf(
                    listOf("1", "2", "3", "Qté"),
                    listOf("4", "5", "6", "% Remise"),
                    listOf("7", "8", "9", "Prix"),
                    listOf("±", "0", ",", "⌫"),
                )
                rangees.forEach { rangee ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rangee.forEach { k ->
                            val mode = ModeSaisie.entries.firstOrNull { it.libelle == k }
                            ToucheCaisse(
                                k, Modifier.weight(if (mode != null) 1.35f else 1f), hauteurTouche,
                                actif = mode != null && state.modeSaisie == mode,
                                special = mode != null || k == "±" || k == "⌫",
                            ) {
                                when {
                                    mode != null -> state.choisirModeSaisie(mode)
                                    k == "⌫" -> state.toucheEffacer()
                                    k == "±" -> state.toucheSigne()
                                    else -> state.toucheNumerique(k)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToucheCaisse(texte: String, modifier: Modifier, hauteur: Dp, actif: Boolean, special: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.height(hauteur).clip(RoundedCornerShape(12.dp))
            .background(if (actif) cs.primary else if (special) cs.secondaryContainer else cs.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texte, fontSize = if (texte.length > 2) 13.sp else 22.sp, softWrap = false, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            color = if (actif) cs.onPrimary else cs.onSurface, maxLines = 1,
        )
    }
}

@Composable
private fun BoutonPaiement(totalCents: Long, vide: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val retour = totalCents < 0
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (vide) cs.surfaceVariant else if (retour) cs.error else cs.primary)
            .clickable(enabled = !vide, onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("›", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = if (vide) cs.onSurfaceVariant else if (retour) cs.onError else cs.onPrimary)
        Text(if (retour) "Rembourser" else "Payer", fontSize = 14.sp, softWrap = false, fontWeight = FontWeight.Bold, color = if (vide) cs.onSurfaceVariant else if (retour) cs.onError else cs.onPrimary, textAlign = TextAlign.Center)
    }
}
