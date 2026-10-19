package fr.demo.caisse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.domain.Money
import fr.demo.caisse.model.ModePaiement
import fr.demo.caisse.model.Paiement
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.util.Receipt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class EtatCarte { ATTENTE, ACCEPTEE }

private data class LignePaiement(val id: Int, val mode: ModePaiement, val montantCents: Long, val carte: EtatCarte? = null)

private enum class EtatTpe { ATTENTE, EN_COURS, REFUSE }

private fun centimes(texte: String): Long {
    val s = texte.replace('.', ',')
    val entier = s.substringBefore(',').toLongOrNull() ?: 0L
    val dec = s.substringAfter(',', "").padEnd(2, '0').take(2).toLongOrNull() ?: 0L
    return entier * 100 + dec
}

/**
 * Écran de paiement : les moyens de paiement à gauche, les règlements au centre, le pavé numérique à droite.
 * On touche un moyen pour ajouter un règlement (pré-rempli avec le reste à payer), on corrige le montant au pavé,
 * on peut combiner plusieurs moyens. Un ticket de retour (total négatif) se rembourse en espèces ou sur la carte.
 */
@Composable
fun EcranPaiement(state: AppState, onAnnuler: () -> Unit, onTermine: (Ticket) -> Unit, moyensInitiaux: List<ModePaiement> = emptyList()) {
    val cs = MaterialTheme.colorScheme
    val total = state.panier.totalCents
    val signe = if (total < 0) -1 else 1
    val du = abs(total)
    val remboursement = total < 0
    val moyens = if (remboursement) listOf(ModePaiement.ESPECES, ModePaiement.CARTE) else ModePaiement.entries.toList()

    val lignes = remember { mutableStateListOf<LignePaiement>() }
    var choisie by remember { mutableStateOf<Int?>(null) }
    var saisie by remember { mutableStateOf("") }
    var prochainId by remember { mutableStateOf(1) }
    var tpe by remember { mutableStateOf<Pair<Int, EtatTpe>?>(null) }
    val scope = rememberCoroutineScope()

    val especes = lignes.filter { it.mode == ModePaiement.ESPECES }.sumOf { it.montantCents }
    val autres = lignes.filter { it.mode != ModePaiement.ESPECES }.sumOf { it.montantCents }
    val besoinEspeces = maxOf(0L, du - autres)
    val paye = autres + especes
    val reste = maxOf(0L, du - paye)
    val rendu = if (especes > 0) maxOf(0L, especes - besoinEspeces) else 0L
    val depassement = autres > du                                   // seules les espèces rendent la monnaie
    val cartesOk = lignes.none { it.mode == ModePaiement.CARTE && it.carte != EtatCarte.ACCEPTEE }
    val aucuneLigneVide = lignes.none { it.montantCents <= 0L }
    val surplusRemboursement = remboursement && paye > du
    val peutValider = !depassement && !surplusRemboursement && paye >= du && cartesOk && aucuneLigneVide && lignes.isNotEmpty()

    fun ajouterMoyen(m: ModePaiement) {
        // reste recalculé à partir des règlements déjà saisis (pas de la valeur de la dernière recomposition)
        val resteActuel = maxOf(0L, du - lignes.sumOf { it.montantCents })
        val l = LignePaiement(prochainId++, m, resteActuel, if (m == ModePaiement.CARTE) EtatCarte.ATTENTE else null)
        lignes.add(l)
        choisie = l.id
        saisie = ""
    }

    fun modifierChoisie(f: (LignePaiement) -> LignePaiement) {
        val i = lignes.indexOfFirst { it.id == choisie }
        if (i >= 0 && lignes[i].carte != EtatCarte.ACCEPTEE) lignes[i] = f(lignes[i])
    }

    LaunchedEffect(Unit) { moyensInitiaux.forEach { ajouterMoyen(it) } }

    fun touche(c: String) {
        when {
            c == "⌫" -> { saisie = saisie.dropLast(1); modifierChoisie { it.copy(montantCents = centimes(saisie)) } }
            c == "," -> if (!saisie.contains(',')) { saisie = if (saisie.isEmpty()) "0," else "$saisie,"; modifierChoisie { it.copy(montantCents = centimes(saisie)) } }
            else -> {
                if (saisie.contains(',') && saisie.substringAfter(',').length >= 2) return
                saisie = (saisie + c).trimStart('0').ifEmpty { if (c == "0") "" else c }.take(9)
                modifierChoisie { it.copy(montantCents = centimes(saisie)) }
            }
        }
    }

    fun valider() {
        // Les espèces couvrent ce que les autres moyens ne couvrent pas ; la monnaie rendue est portée par la ligne espèces.
        var aCouvrir = besoinEspeces
        val paiements = lignes.map { l ->
            if (l.mode == ModePaiement.ESPECES) {
                val impute = minOf(l.montantCents, aCouvrir)
                aCouvrir -= impute
                Paiement(l.mode, signe * impute, l.montantCents - impute)
            } else Paiement(l.mode, signe * l.montantCents, 0)
        }
        onTermine(state.finaliser(paiements))
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize()) {
            EnTeteEcran(
                if (remboursement) "Remboursement" else "Paiement", { if (lignes.any { it.carte == EtatCarte.ACCEPTEE }) state.notifier(Niveau.ERREUR, "Une carte est déjà acceptée : terminez le paiement", "E-PAI-003") else onAnnuler() },
                "Ticket n° ${state.prochainNumero}" + (state.client?.let { " · ${it.nom}" } ?: ""),
            )
            Adaptatif { compact ->
                Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // moyens de paiement
                    Cadre(Modifier.width(if (compact) 230.dp else 280.dp).fillMaxHeight()) {
                        Text("Moyens de paiement", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                        moyens.forEach { m ->
                            Box(
                                Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant)
                                    .clickable { ajouterMoyen(m) }.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) { Text(m.libelle, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    // règlements
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Stat(if (remboursement) "À rembourser" else "Montant dû", Money.format(du), Modifier.weight(1f))
                            if (rendu > 0 && reste == 0L) Stat("Monnaie à rendre", Money.format(rendu), Modifier.weight(1f), Ext.succes, Ext.succesFond)
                            else Stat(if (remboursement) "Reste à rembourser" else "Reste à payer", Money.format(reste), Modifier.weight(1f), if (reste == 0L) Ext.succes else cs.primary)
                        }
                        Cadre(Modifier.fillMaxWidth().weight(1f)) {
                            if (lignes.isEmpty()) {
                                EtatVide("Aucun règlement", "Choisissez un moyen de paiement\nà gauche")
                            } else {
                                ListeV(Modifier.fillMaxSize(), 8.dp) {
                                    items(lignes, key = { it.id }) { l ->
                                        val actif = l.id == choisie
                                        Row(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (actif) cs.primaryContainer else cs.background)
                                                .border(if (actif) 2.dp else 0.dp, if (actif) cs.primary else Color.Transparent, RoundedCornerShape(14.dp))
                                                .clickable { choisie = l.id; saisie = "" }.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(l.mode.libelle, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                                if (l.mode == ModePaiement.CARTE) {
                                                    Text(if (l.carte == EtatCarte.ACCEPTEE) "Acceptée" else "En attente du terminal", fontSize = 13.sp, color = if (l.carte == EtatCarte.ACCEPTEE) Ext.succes else Ext.alerte)
                                                }
                                            }
                                            if (l.mode == ModePaiement.CARTE && l.carte == EtatCarte.ATTENTE) {
                                                AppButton("Terminal", { choisie = l.id; if (l.montantCents > 0) tpe = l.id to EtatTpe.ATTENTE else state.notifier(Niveau.ERREUR, "Saisissez un montant", "E-PAI-001") }, style = Style.PRIMAIRE, hauteur = 44.dp, taille = 15.sp)
                                            }
                                            Text(Money.format(l.montantCents), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                            if (l.carte != EtatCarte.ACCEPTEE) AppButton("✕", { lignes.remove(l); if (choisie == l.id) choisie = null }, Modifier.width(48.dp), style = Style.TONAL, hauteur = 44.dp, taille = 16.sp)
                                        }
                                    }
                                }
                            }
                        }
                        if (depassement) Text("Ce moyen de paiement ne rend pas la monnaie : le total dépasse le montant dû.", fontSize = 14.sp, color = cs.error)
                    }
                    // pavé + validation
                    Cadre(Modifier.width(if (compact) 300.dp else 350.dp).fillMaxHeight()) {
                        val rangees = listOf(listOf("1", "2", "3", "+10"), listOf("4", "5", "6", "+20"), listOf("7", "8", "9", "+50"), listOf(",", "0", "⌫", "Exact"))
                        rangees.forEach { rangee ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rangee.forEach { k ->
                                    val rapide = k.startsWith("+") || k == "Exact"
                                    ToucheCaisse(k, Modifier.weight(1f), if (compact) 52.dp else 58.dp, actif = false, special = rapide) {
                                        when {
                                            k == "Exact" -> modifierChoisie { it.copy(montantCents = it.montantCents + reste) }
                                            k.startsWith("+") -> modifierChoisie { it.copy(montantCents = it.montantCents + k.drop(1).toLong() * 100) }
                                            else -> touche(k)
                                        }
                                        if (rapide) saisie = ""
                                    }
                                }
                            }
                        }
                        Box(Modifier.weight(1f))
                        if (!peutValider && lignes.isNotEmpty() && !cartesOk) Text("Envoyez la carte au terminal pour continuer.", fontSize = 14.sp, color = Ext.alerte)
                        AppButton(
                            if (remboursement) "Valider le remboursement" else "Valider",
                            { valider() }, Modifier.fillMaxWidth(), style = Style.SUCCES, enabled = peutValider, hauteur = 68.dp, taille = 20.sp,
                        )
                    }
                }
            }
        }

        // terminal de paiement (simulé)
        tpe?.let { (id, etat) ->
            val ligne = lignes.firstOrNull { it.id == id }
            if (ligne == null) tpe = null else Modale(onFermer = null, largeurMax = 520.dp) {
                Text("Terminal de paiement", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(Money.format(ligne.montantCents), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                Text(
                    when (etat) {
                        EtatTpe.ATTENTE -> "Présentez la carte sur le terminal"
                        EtatTpe.EN_COURS -> "Dialogue avec le terminal de paiement…"
                        EtatTpe.REFUSE -> "Paiement refusé par la banque"
                    },
                    fontSize = 19.sp, color = if (etat == EtatTpe.REFUSE) cs.error else cs.onSurface,
                )
                Text("Démonstration : le terminal est simulé.", fontSize = 14.sp, color = cs.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Annuler", { tpe = null }, Modifier.weight(1f), style = Style.TONAL, enabled = etat != EtatTpe.EN_COURS, hauteur = 52.dp, taille = 16.sp)
                    AppButton("Simuler un refus", { tpe = id to EtatTpe.EN_COURS; scope.launch { delay(1200); tpe = id to EtatTpe.REFUSE } }, Modifier.weight(1f), style = Style.DANGER, enabled = etat != EtatTpe.EN_COURS, hauteur = 52.dp, taille = 16.sp)
                    AppButton(
                        if (etat == EtatTpe.REFUSE) "Réessayer" else "Simuler l'acceptation",
                        {
                            tpe = id to EtatTpe.EN_COURS
                            scope.launch {
                                delay(1500)
                                val i = lignes.indexOfFirst { it.id == id }
                                if (i >= 0) lignes[i] = lignes[i].copy(carte = EtatCarte.ACCEPTEE)
                                tpe = null
                            }
                        },
                        Modifier.weight(1.3f), style = Style.SUCCES, enabled = etat != EtatTpe.EN_COURS, hauteur = 52.dp, taille = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(libelle: String, valeur: String, modifier: Modifier, couleur: Color = MaterialTheme.colorScheme.onSurface, fond: Color = MaterialTheme.colorScheme.surface) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.background(fond, RoundedCornerShape(14.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(libelle, fontSize = 14.sp, color = cs.onSurfaceVariant)
        Text(valeur, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = couleur)
    }
}

/**
 * « Que souhaite le client ? » : ticket papier, reçu carte seul ou rien. Affiche aussi le rendu de monnaie.
 * Le choix « ne plus demander aujourd'hui » est mémorisé pour la journée.
 */
@Composable
fun DialogueImpression(state: AppState, ticket: Ticket, onFermer: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val texte = remember(ticket) { Receipt.ticket(ticket, state.sync.magasin) }
    var nePlusDemander by remember { mutableStateOf(false) }
    val aCarte = ticket.paiements.any { it.mode == ModePaiement.CARTE }

    fun imprimer(): String = Receipt.sauvegarderEtImprimer(ticket.numero, texte)

    // Choix déjà mémorisé et rien à rendre : on enchaîne sans dialogue
    val memorise = state.choixImpressionValide()
    LaunchedEffect(ticket.uuid) {
        if (memorise != ChoixImpression.DEMANDER && ticket.renduCents == 0L) {
            if (memorise == ChoixImpression.TICKET) state.notifier(Niveau.SUCCES, "Ticket n° ${ticket.numero} encaissé · ${imprimer()}")
            else state.notifier(Niveau.SUCCES, "Ticket n° ${ticket.numero} encaissé")
            onFermer()
        }
    }
    if (memorise != ChoixImpression.DEMANDER && ticket.renduCents == 0L) return

    fun choisir(c: ChoixImpression, message: String) {
        if (nePlusDemander) state.definirChoixImpression(c)
        state.notifier(Niveau.SUCCES, "Ticket n° ${ticket.numero} encaissé · $message")
        onFermer()
    }

    Modale(onFermer = null, largeurMax = 700.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (ticket.estRetour) "Retour enregistré" else "Ticket encaissé", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ext.succes)
                Text("Ticket n° ${ticket.numero}", fontSize = 16.sp, color = cs.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Total TTC", fontSize = 14.sp, color = cs.onSurfaceVariant)
                Text(Money.format(ticket.totalCents), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (ticket.renduCents > 0) {
            Column(Modifier.fillMaxWidth().background(Ext.succesFond, RoundedCornerShape(16.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Rendu monnaie", fontSize = 17.sp, color = Ext.succes, fontWeight = FontWeight.SemiBold)
                Text(Money.format(ticket.renduCents), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Ext.succes)
            }
        }
        Text("Que souhaite le client ?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Choix("Ticket papier", "Imprime le ticket complet", Modifier.weight(1f), true) { choisir(ChoixImpression.TICKET, imprimer()) }
            Choix("Reçu CB uniquement", if (aCarte) "Justificatif carte seul" else "Aucun paiement carte", Modifier.weight(1f), aCarte) {
                val recu = Receipt.sauvegarderEtImprimer(ticket.numero, texte.lines().filter { it.contains("Carte") || it.contains("Ticket n°") || it.contains("TOTAL") }.joinToString("\n"))
                choisir(ChoixImpression.TICKET, recu)
            }
            Choix("Sans ticket", "Ne rien imprimer", Modifier.weight(1f), true) { choisir(ChoixImpression.AUCUN, "sans ticket") }
        }
        Row(
            Modifier.clickable { nePlusDemander = !nePlusDemander }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(28.dp).background(if (nePlusDemander) cs.primary else cs.background, RoundedCornerShape(8.dp)).border(2.dp, cs.primary, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (nePlusDemander) Text("✓", color = cs.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text("Ne plus demander aujourd'hui", fontSize = 16.sp)
        }
    }
}

@Composable
private fun Choix(titre: String, detail: String, modifier: Modifier, actif: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.height(110.dp).background(if (actif) cs.primaryContainer else cs.background, RoundedCornerShape(16.dp))
            .border(1.dp, if (actif) cs.primary else cs.outline, RoundedCornerShape(16.dp))
            .clickable(enabled = actif, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(titre, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (actif) cs.onPrimaryContainer else cs.onSurfaceVariant)
        Text(detail, fontSize = 13.sp, color = if (actif) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant)
    }
}

/** Aperçu monospace d'un ticket (journal de caisse). */
@Composable
fun ApercuTexte(texte: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFFF8F8F2), RoundedCornerShape(10.dp)).padding(12.dp).verticalScroll(rememberScrollState())) {
        Text(texte, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFF111111))
    }
}
