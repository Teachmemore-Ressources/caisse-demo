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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.domain.Money
import fr.demo.caisse.model.Comptage
import fr.demo.caisse.model.ModePaiement
import fr.demo.caisse.model.TypeComptage
import fr.demo.caisse.model.TypeMouvement
import fr.demo.caisse.util.Receipt

private val BILLETS = listOf(50_000L, 20_000L, 10_000L, 5_000L, 2_000L, 1_000L, 500L)
private val PIECES = listOf(200L, 100L, 50L, 20L, 10L, 5L, 2L, 1L)
private val MOTIFS = listOf("Fond de caisse complémentaire", "Remise en banque", "Achat divers", "Autre")

private class ResultatComptage(val comptage: Comptage, val texte: String, val cloture: Boolean)

@Composable
fun ComptageScreen(state: AppState) {
    val cs = MaterialTheme.colorScheme
    var version by remember { mutableStateOf(0) }
    val fond = remember(version) { state.db.fondOuverture() }
    var type by remember { mutableStateOf(if (fond == 0L) TypeComptage.OUVERTURE else TypeComptage.CONTROLE) }
    val quantites = remember { mutableStateMapOf<Long, Int>() }
    val autres = remember { mutableStateMapOf<ModePaiement, String>() }
    var champ by remember { mutableStateOf<ModePaiement?>(null) }
    var confirmer by remember { mutableStateOf(false) }
    var mouvements by remember { mutableStateOf(false) }
    var resultat by remember { mutableStateOf<ResultatComptage?>(null) }
    var protegee by remember { mutableStateOf<(() -> Unit)?>(null) }

    val theorique = remember(version) { state.db.theoriqueEspeces() }
    val theoriquesAutres = remember(version) { state.db.theoriquesParMoyen() }
    val especes = quantites.entries.sumOf { (v, n) -> v * n }
    val totalAutres = ModePaiement.entries.filter { it != ModePaiement.ESPECES }.sumOf { autres[it]?.toLongOrNull() ?: 0L }
    val attendu = if (type == TypeComptage.OUVERTURE) especes else theorique
    val ecart = especes - attendu

    fun choisirType(t: TypeComptage) {
        if (t == TypeComptage.CLOTURE && state.caissier?.estResponsable != true) protegee = { type = t } else type = t
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            EnTeteEcran("Comptage de fonds de caisse", { state.aller(Screen.CAISSE) }, "Comptez le tiroir par dénominations") {
                TypeComptage.entries.forEach { t -> Puce(t.libelle, type == t, { choisirType(t) }) }
                AppButton("Mouvements", { mouvements = true }, style = Style.TONAL, hauteur = 48.dp, taille = 16.sp)
            }
            Adaptatif { compact ->
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // dénominations
                    Cadre(Modifier.weight(1.35f).fillMaxSize()) {
                        ColonneV(8.dp) {
                            Text("BILLETS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                            BILLETS.chunked(if (compact) 1 else 2).forEach { rangee ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rangee.forEach { v -> LigneDenomination(v, quantites[v] ?: 0, Modifier.weight(1f)) { quantites[v] = it } }
                                    if (rangee.size == 1 && !compact) Box(Modifier.weight(1f))
                                }
                            }
                            Text("PIÈCES", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            PIECES.chunked(if (compact) 1 else 2).forEach { rangee ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rangee.forEach { v -> LigneDenomination(v, quantites[v] ?: 0, Modifier.weight(1f)) { quantites[v] = it } }
                                    if (rangee.size == 1 && !compact) Box(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    // synthèse
                    Cadre(Modifier.weight(1f).fillMaxSize()) {
                        ColonneV(10.dp) {
                            LigneValeur("Espèces comptées", Money.format(especes), gras = true, taille = 20.sp)
                            if (type == TypeComptage.OUVERTURE) {
                                Text("Ce montant devient le fond de caisse de la journée.", fontSize = 14.sp, color = cs.onSurfaceVariant)
                            } else {
                                LigneValeur("Montant théorique en caisse", Money.format(theorique), taille = 16.sp)
                                LigneValeur("Fond de caisse", Money.format(fond), taille = 14.sp)
                                Row(
                                    Modifier.fillMaxWidth().background(if (ecart == 0L) Ext.succesFond else if (ecart > 0) Ext.infoFond else cs.errorContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (ecart == 0L) "Caisse juste" else if (ecart > 0) "Excédent" else "Reste à justifier", fontSize = 17.sp, modifier = Modifier.weight(1f))
                                    Text(Money.format(ecart), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (ecart == 0L) Ext.succes else if (ecart > 0) Ext.info else cs.error)
                                }
                            }
                            if (type != TypeComptage.OUVERTURE) {
                                Text("AUTRES MOYENS DE PAIEMENT", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                ModePaiement.entries.filter { it != ModePaiement.ESPECES }.forEach { m ->
                                    val actif = champ == m
                                    Row(
                                        Modifier.fillMaxWidth().background(cs.background, RoundedCornerShape(12.dp)).border(2.dp, if (actif) cs.primary else cs.background, RoundedCornerShape(12.dp))
                                            .clickable { champ = m }.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(m.libelle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                            Text("Attendu ${Money.format(theoriquesAutres[m] ?: 0)}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                                        }
                                        Text(Money.format(autres[m]?.toLongOrNull() ?: 0L), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                champ?.let { m ->
                                    PaveNumerique({ autres[m] = ((autres[m] ?: "") + it).trimStart('0').take(8) }, { autres[m] = "" }, hauteurTouche = 46.dp)
                                }
                            }
                            AppButton("Confirmer le comptage", { confirmer = true }, Modifier.fillMaxWidth(), hauteur = 60.dp, taille = 19.sp, enabled = especes > 0 || type != TypeComptage.OUVERTURE)
                        }
                    }
                }
            }
        }

        if (confirmer) {
            Modale(onFermer = null, largeurMax = 560.dp) {
                Text("Confirmer le comptage ${type.libelle.lowercase()} ?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Cette action génère un ticket et ne peut pas être annulée ensuite.", fontSize = 16.sp, color = cs.onSurfaceVariant)
                if (type == TypeComptage.CLOTURE) Text("La clôture fige tous les tickets, mouvements et comptages de la journée.", fontSize = 16.sp, color = Ext.alerte)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton("Annuler", { confirmer = false }, Modifier.weight(1f), style = Style.TONAL)
                    AppButton("Confirmer", {
                        val detail = (BILLETS + PIECES).filter { (quantites[it] ?: 0) > 0 }.map { Money.format(it) + " × " + (quantites[it] ?: 0) to Money.format(it * (quantites[it] ?: 0)) }
                        val c = state.db.enregistrerComptage(type, state.caissier?.nom ?: "?", especes, totalAutres, attendu, detail.joinToString(";") { it.first })
                        var texte = Receipt.comptage(c, state.sync.magasin, detail)
                        val z = if (type == TypeComptage.CLOTURE) state.db.cloturer(state.caissier?.nom ?: "?") else null
                        if (z != null) texte += "\n" + Receipt.cloture(z, state.sync.magasin)
                        confirmer = false
                        quantites.clear(); autres.clear(); champ = null
                        version++
                        state.rafraichirStats()
                        resultat = ResultatComptage(c, texte, z != null)
                        if (type == TypeComptage.OUVERTURE) type = TypeComptage.CONTROLE
                    }, Modifier.weight(1f))
                }
            }
        }
        resultat?.let { r ->
            Modale(onFermer = null, largeurMax = 640.dp) {
                Text(if (r.cloture) "Clôture effectuée" else "Comptage enregistré", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ext.succes)
                ApercuTexte(r.texte, Modifier.fillMaxWidth().height(360.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton("Imprimer", { state.notifier(Niveau.INFO, Receipt.sauvegarderEtImprimer(200000 + r.comptage.id.toInt(), r.texte)) }, Modifier.weight(1f), style = Style.TONAL)
                    AppButton("Terminé", { resultat = null; if (r.cloture) state.aller(Screen.CAISSE) }, Modifier.weight(1f))
                }
            }
        }
        if (mouvements) DialogueMouvements(state, onFermer = { mouvements = false; version++ })
        protegee?.let { action ->
            AutorisationResponsable(state, "Clôture de la caisse", onValide = { protegee = null; action() }, onAnnuler = { protegee = null })
        }
    }
}

@Composable
private fun LigneDenomination(valeur: Long, qte: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.background(if (qte > 0) cs.primaryContainer else cs.background, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (valeur >= 100) "${valeur / 100} €" else "${Money.nombre(valeur)} €", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
        SelecteurQuantite(qte, { if (qte > 0) onChange(qte - 1) }, { onChange(qte + 1) })
        Box(Modifier.weight(1f))
        Text(Money.format(valeur * qte), fontSize = 16.sp, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun DialogueMouvements(state: AppState, onFermer: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var type by remember { mutableStateOf(TypeMouvement.ENTREE) }
    var saisie by remember { mutableStateOf("") }
    var motif by remember { mutableStateOf(MOTIFS.first()) }
    var version by remember { mutableStateOf(0) }
    var protegee by remember { mutableStateOf<(() -> Unit)?>(null) }
    val historique = remember(version) { state.db.mouvementsOuverts() }
    val montant = saisie.toLongOrNull() ?: 0L

    Modale(onFermer = null, largeurMax = 760.dp) {
        Text("Mouvements d'espèces", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeMouvement.entries.forEach { t ->
                Box(Modifier.weight(1f)) {
                    Puce(
                        t.libelle, type == t,
                        { if (t == TypeMouvement.PRELEVEMENT && state.caissier?.estResponsable != true) protegee = { type = t } else type = t },
                        Modifier.fillMaxWidth(), accent = if (t.signe < 0) cs.error else Ext.succes,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().background(cs.background, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Montant", fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text(Money.format(montant), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                MOTIFS.forEach { m ->
                    Puce(m, motif == m, { motif = m }, Modifier.fillMaxWidth().height(44.dp))
                }
            }
            PaveNumerique({ saisie = (saisie + it).trimStart('0').take(7) }, { saisie = "" }, Modifier.weight(1f), hauteurTouche = 54.dp)
        }
        if (historique.isNotEmpty()) {
            Text("Depuis la dernière clôture", fontSize = 14.sp, color = cs.onSurfaceVariant)
            historique.take(3).forEach { m ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${m.type.libelle} · ${m.motif}", fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text((if (m.type.signe > 0) "+" else "-") + Money.format(m.montantCents), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (m.type.signe > 0) Ext.succes else cs.error)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppButton("Fermer", onFermer, Modifier.weight(1f), style = Style.TONAL)
            AppButton("Enregistrer", {
                state.db.enregistrerMouvement(type, montant, state.caissier?.nom ?: "?", motif)
                state.notifier(Niveau.SUCCES, "${type.libelle} de ${Money.format(montant)} enregistré")
                saisie = ""; version++
            }, Modifier.weight(1.4f), enabled = montant > 0)
        }
    }
    protegee?.let { action -> AutorisationResponsable(state, "Prélèvement d'espèces", onValide = { protegee = null; action() }, onAnnuler = { protegee = null }) }
}
