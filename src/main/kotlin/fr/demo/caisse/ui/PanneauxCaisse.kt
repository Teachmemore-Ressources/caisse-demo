package fr.demo.caisse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.domain.Money
import java.text.Normalizer

private fun normaliser(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase().trim()

@Composable
private fun ChampRecherche(valeur: String, indication: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(54.dp).background(cs.background, RoundedCornerShape(14.dp)).border(2.dp, cs.primary, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", fontSize = 24.sp, color = cs.onSurfaceVariant)
        Text(
            if (valeur.isEmpty()) "  $indication" else "  $valeur▏",
            fontSize = 19.sp, color = if (valeur.isEmpty()) cs.onSurfaceVariant else cs.onSurface, maxLines = 1,
        )
    }
}

/** Recherche de produits par nom ou code (EAN), avec sélection de quantités puis ajout au ticket. */
@Composable
fun PanneauRechercheProduits(state: AppState, onFermer: () -> Unit) {
    var requete by remember { mutableStateOf("") }
    val quantites: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() }
    val cs = MaterialTheme.colorScheme
    val q = normaliser(requete)
    val resultats = remember(requete, state.produits) {
        if (q.isEmpty()) state.produits.take(40)
        else state.produits.filter { normaliser(it.nom).contains(q) || it.code.contains(q) || normaliser(it.categorie).contains(q) }.take(40)
    }
    val total = quantites.values.sum()

    PanneauLateral("Recherche produits", onFermer, largeur = 620.dp) {
        ChampRecherche(requete, "Nom, code ou catégorie")
        Text(
            if (q.isEmpty()) "${state.produits.size} produits" else "${resultats.size} résultat${if (resultats.size > 1) "s" else ""} sur ${state.produits.size} produits",
            fontSize = 14.sp, color = cs.onSurfaceVariant,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (resultats.isEmpty()) EtatVide("Aucun produit trouvé") else {
                ListeV(Modifier.fillMaxSize()) {
                    items(resultats, key = { it.code }) { p ->
                        val n = quantites[p.code] ?: 0
                        Row(
                            Modifier.fillMaxWidth().background(if (n > 0) cs.primaryContainer else cs.background, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.width(6.dp).height(40.dp).background(accentCategorie(p.categorie), RoundedCornerShape(3.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(p.nom, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("EAN ${p.code} · TVA ${Money.formatTaux(p.tvaBp)} · ${p.categorie}", fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
                            }
                            Text(Money.format(p.prixCents), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            SelecteurQuantite(n, { if (n > 0) quantites[p.code] = n - 1 }, { quantites[p.code] = n + 1 })
                        }
                    }
                }
            }
        }
        AppButton(
            if (total == 0) "Sélectionnez des quantités" else "Ajouter $total article${if (total > 1) "s" else ""} au ticket",
            {
                quantites.forEach { (code, n) -> state.produits.firstOrNull { it.code == code }?.let { if (n > 0) state.ajouter(it, n) } }
                onFermer()
            },
            Modifier.fillMaxWidth(), enabled = total > 0, hauteur = 56.dp,
        )
        ClavierVirtuel({ requete += it }, { requete = requete.dropLast(1) })
    }
}

/** Recherche et sélection du client du ticket (carte de fidélité). */
@Composable
fun PanneauRechercheClients(state: AppState, onFermer: () -> Unit) {
    var requete by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    val q = normaliser(requete)
    val resultats = remember(requete, state.clients) {
        if (q.isEmpty()) state.clients else state.clients.filter { normaliser(it.nom).contains(q) || it.carte.replace(" ", "").contains(q.replace(" ", "")) }
    }

    PanneauLateral("Recherche client", onFermer, largeur = 620.dp) {
        ChampRecherche(requete, "Nom ou numéro de carte")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${resultats.size} client${if (resultats.size > 1) "s" else ""}", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (state.client != null) AppButton("Retirer le client", { state.choisirClient(null); onFermer() }, style = Style.TONAL, hauteur = 44.dp, taille = 15.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (resultats.isEmpty()) EtatVide("Aucun client trouvé") else {
                ListeV(Modifier.fillMaxSize()) {
                    items(resultats, key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().background(if (state.client?.id == c.id) cs.primaryContainer else cs.background, RoundedCornerShape(12.dp))
                                .clickable(enabled = c.actif) { state.choisirClient(c); onFermer() }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Avatar(c.nom.split(" ").take(2).joinToString("") { it.take(1) }, if (c.actif) cs.primary else cs.onSurfaceVariant, 44.dp)
                            Column(Modifier.weight(1f)) {
                                Text(c.nom, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text("Carte ${c.carte} · ${c.points} pts", fontSize = 13.sp, color = cs.onSurfaceVariant)
                            }
                            if (!c.actif) Pastille("Inactif", cs.errorContainer, cs.error) else Text("›", fontSize = 26.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        ClavierVirtuel({ requete += it }, { requete = requete.dropLast(1) })
    }
}

/** Remise sur le ticket : pourcentage rapide ou montant saisi. Au-delà de 20 %, un responsable doit valider. */
@Composable
fun DialogueRemise(state: AppState, onFermer: () -> Unit, onDemanderResponsable: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sousTotal = state.panier.sousTotalCents
    var saisie by remember { mutableStateOf("") }
    val montant = saisie.toLongOrNull() ?: 0L
    val seuil = sousTotal * 20 / 100

    fun appliquer(cents: Long) {
        val c = cents.coerceIn(0, sousTotal)
        if (c > seuil && state.caissier?.estResponsable != true) onDemanderResponsable(c)
        else { state.remiseTicket(c); onFermer() }
    }

    Modale(onFermer = null, largeurMax = 560.dp) {
        Text("Remise sur le ticket", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        LigneValeur("Sous-total", Money.format(sousTotal))
        Text("En pourcentage", fontSize = 15.sp, color = cs.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 20, 30).forEach { pct ->
                AppButton("$pct %", { appliquer((sousTotal * pct + 50) / 100) }, Modifier.weight(1f), style = if (pct > 20) Style.DISCRET else Style.TONAL, hauteur = 52.dp)
            }
        }
        Text("Ou un montant (au-delà de 20 %, autorisation d'un responsable)", fontSize = 15.sp, color = cs.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().background(cs.background, RoundedCornerShape(14.dp)).padding(14.dp)) {
            Text("Montant", fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text(Money.format(montant), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (montant > sousTotal) cs.error else cs.onSurface)
        }
        PaveNumerique({ saisie = (saisie + it).trimStart('0').take(7) }, { saisie = "" }, hauteurTouche = 54.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Annuler", onFermer, Modifier.weight(1f), style = Style.TONAL)
            if (state.panier.remiseCents > 0) AppButton("Retirer", { state.remiseTicket(0); onFermer() }, Modifier.weight(1f), style = Style.DISCRET)
            AppButton("Appliquer", { appliquer(montant) }, Modifier.weight(1.4f), enabled = montant in 1..sousTotal)
        }
    }
}
