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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.domain.Money
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.util.Receipt

private enum class Filtre(val libelle: String) { TOUS("Tous"), VENTES("Ventes"), RETOURS("Retours") }

@Composable
fun JournalScreen(state: AppState) {
    val cs = MaterialTheme.colorScheme
    var version by remember { mutableStateOf(0) }
    var filtre by remember { mutableStateOf(Filtre.TOUS) }
    var choisi by remember { mutableStateOf<Int?>(null) }
    var confirmerRetour by remember { mutableStateOf(false) }
    var protegee by remember { mutableStateOf(false) }

    val tickets = remember(version) { state.db.ticketsDuJour() }
    val affiches = tickets.filter { when (filtre) { Filtre.TOUS -> true; Filtre.VENTES -> !it.estRetour; Filtre.RETOURS -> it.estRetour } }
    val ticket = tickets.firstOrNull { it.numero == choisi } ?: affiches.firstOrNull()
    val dejaRetourne = ticket?.let { remember(it.numero, version) { state.db.dejaRetourne(it.numero) } } ?: false
    val total = tickets.sumOf { it.totalCents }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            EnTeteEcran("Journal de caisse", { state.aller(Screen.CAISSE) }, "${tickets.size} ticket(s) aujourd'hui · ${Money.format(total)}") {
                Filtre.entries.forEach { f -> Puce(f.libelle, filtre == f, { filtre = f }) }
            }
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cadre(Modifier.weight(1.1f).fillMaxSize()) {
                    if (affiches.isEmpty()) EtatVide("Aucun ticket enregistré aujourd'hui sur cette caisse") else {
                        ListeV(Modifier.fillMaxSize()) {
                            items(affiches, key = { it.uuid }) { t ->
                                val actif = t.numero == ticket?.numero
                                Row(
                                    Modifier.fillMaxWidth().background(if (actif) cs.primaryContainer else cs.background, RoundedCornerShape(12.dp))
                                        .border(if (actif) 2.dp else 0.dp, if (actif) cs.primary else cs.background, RoundedCornerShape(12.dp))
                                        .clickable { choisi = t.numero }.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("n° ${t.numero}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                            if (t.estRetour) Pastille("RETOUR", cs.errorContainer, cs.error)
                                        }
                                        Text(
                                            Receipt.date(t.dateIso).substringAfter(' ') + " · " + t.caissier + (t.client?.let { " · ${it.nom}" } ?: ""),
                                            fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1,
                                        )
                                    }
                                    Pastille(
                                        t.paiements.singleOrNull()?.mode?.libelle ?: "Mixte", cs.secondaryContainer, cs.onSecondaryContainer,
                                    )
                                    Text(Money.format(t.totalCents), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (t.estRetour) cs.error else cs.onSurface)
                                    Pastille(if (t.synced) "Envoyé" else "En attente", if (t.synced) Ext.succesFond else Ext.alerteFond, if (t.synced) Ext.succes else Ext.alerte)
                                }
                            }
                        }
                    }
                }
                Cadre(Modifier.weight(1f).fillMaxSize()) {
                    if (ticket == null) EtatVide("Sélectionnez un ticket pour en voir le détail") else {
                        Text("Détail du ticket n° ${ticket.numero}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        ApercuTexte(Receipt.ticket(ticket, state.sync.magasin), Modifier.weight(1f).fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton("Imprimer", { state.notifier(Niveau.INFO, Receipt.sauvegarderEtImprimer(ticket.numero, Receipt.ticket(ticket, state.sync.magasin))) }, Modifier.weight(1f), style = Style.TONAL)
                            AppButton(
                                if (dejaRetourne) "Déjà retourné" else "Retour ticket",
                                { if (state.caissier?.estResponsable == true) confirmerRetour = true else protegee = true },
                                Modifier.weight(1f), style = Style.DANGER, enabled = !ticket.estRetour && !dejaRetourne,
                            )
                        }
                        if (state.caissier?.estResponsable != true) Text("Le retour d'un ticket est réservé au responsable.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }

        if (confirmerRetour && ticket != null) {
            Modale(onFermer = null, largeurMax = 520.dp) {
                Text("Retourner le ticket n° ${ticket.numero} ?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Un ticket de retour sera créé et ${Money.format(ticket.totalCents)} remboursés (${ticket.paiements.singleOrNull()?.mode?.libelle?.lowercase() ?: "espèces"}).",
                    fontSize = 16.sp, color = cs.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton("Annuler", { confirmerRetour = false }, Modifier.weight(1f), style = Style.TONAL)
                    AppButton("Retourner", {
                        val r = state.retourTicket(ticket)
                        confirmerRetour = false; choisi = r.numero; version++
                        state.notifier(Niveau.SUCCES, "Retour du ticket n° ${ticket.numero} enregistré (ticket n° ${r.numero})")
                    }, Modifier.weight(1f), style = Style.DANGER)
                }
            }
        }
        if (protegee) AutorisationResponsable(state, "Retour du ticket n° ${ticket?.numero}", onValide = { protegee = false; confirmerRetour = true }, onAnnuler = { protegee = false })
    }
}
