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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.demo.caisse.data.AppPaths
import fr.demo.caisse.net.EtatReseau
import fr.demo.caisse.util.SystemInfo
import kotlinx.coroutines.launch

private enum class Onglet(val libelle: String) { CONFIG("Configuration"), SYNCHRO("Synchronisation"), INFOS("Informations") }

@Composable
fun SystemeScreen(state: AppState) {
    var onglet by remember { mutableStateOf(Onglet.CONFIG) }
    val responsable = state.caissier?.estResponsable == true

    Column(Modifier.fillMaxSize()) {
        EnTeteEcran("Configuration et synchronisation", { state.aller(Screen.CAISSE) }) {
            Onglet.entries.forEach { o -> Puce(o.libelle, o == onglet, { onglet = o }) }
        }
        Box(Modifier.weight(1f).padding(12.dp)) {
            if (!responsable && onglet == Onglet.CONFIG) {
                Cadre(Modifier.fillMaxSize()) { EtatVide("Réservé au responsable", "Changez de caissier pour modifier la configuration.") }
            } else when (onglet) {
                Onglet.CONFIG -> ConfigTab(state)
                Onglet.SYNCHRO -> SynchroTab(state)
                Onglet.INFOS -> InfosTab(state)
            }
        }
    }
}

@Composable
private fun Champ(libelle: String, valeur: String, actif: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(libelle, fontSize = 15.sp, color = cs.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth().background(cs.background, RoundedCornerShape(12.dp))
                .border(2.dp, if (actif) cs.primary else cs.outline, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        ) { Text(valeur.ifEmpty { " " } + if (actif) "▏" else "", fontSize = 20.sp) }
    }
}

@Composable
private fun ConfigTab(state: AppState) {
    val cs = MaterialTheme.colorScheme
    var url by remember { mutableStateOf(state.sync.urlServeur.ifBlank { "http://" }) }
    var code by remember { mutableStateOf("") }
    var champActif by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var occupe by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Cadre(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.sync.estActivee) "Caisse activée : ${state.sync.magasin}" else "Caisse non activée (mode autonome avec les données de démonstration)",
                fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (state.sync.estActivee) Ext.succes else Ext.alerte,
            )
            Champ("Adresse du serveur (SaaS)", url, champActif == 0) { champActif = 0 }
            Champ("Code d'activation de la caisse", code, champActif == 1) { champActif = 1 }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton("Activer la caisse", {
                    occupe = true; message = "Connexion au serveur…"
                    scope.launch {
                        val r = state.sync.activer(url, code)
                        message = r.getOrElse { "Échec : ${it.message}" }
                        if (r.isSuccess) state.notifier(Niveau.SUCCES, "Caisse activée")
                        occupe = false
                    }
                }, enabled = !occupe && url.length > 8 && code.isNotBlank(), hauteur = 52.dp, taille = 17.sp)
                if (state.sync.estActivee) AppButton("Désactiver", { state.sync.desactiver(); message = "Caisse désactivée" }, style = Style.TONAL, hauteur = 52.dp, taille = 17.sp)
                Text(message, fontSize = 16.sp, modifier = Modifier.weight(1f))
            }
            ClavierVirtuel(
                onCaractere = { c -> if (champActif == 0) url += c else code += c },
                onEffacer = { if (champActif == 0) url = url.dropLast(1) else code = code.dropLast(1) },
                modifier = Modifier.fillMaxWidth(), hauteurTouche = 42.dp,
            )
        }
    }
}

@Composable
private fun SynchroTab(state: AppState) {
    val etat by state.sync.etat.collectAsState()
    val enAttente by state.sync.enAttente.collectAsState()
    val derniere by state.sync.derniereSync.collectAsState()
    val message by state.sync.message.collectAsState()

    Cadre(Modifier.fillMaxSize()) {
        Info("État du réseau", when (etat) { EtatReseau.EN_LIGNE -> "En ligne"; EtatReseau.HORS_LIGNE -> "Hors ligne"; EtatReseau.NON_CONFIGURE -> "Non configuré" })
        Info("Tickets en attente d'envoi", enAttente.toString())
        Info("Dernière synchronisation", derniere)
        Info("Message", message.ifBlank { "—" })
        Info("Produits en base", state.produits.size.toString())
        Info("Clients en base", state.clients.size.toString())
        AppButton("Synchroniser maintenant", { state.sync.declencher(rafraichirReferentiel = true) }, enabled = state.sync.estActivee, hauteur = 56.dp)
        Text(
            "Les ventes sont enregistrées localement puis envoyées au serveur dès qu'il est joignable : la caisse continue de vendre hors ligne.",
            fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfosTab(state: AppState) {
    Cadre(Modifier.fillMaxSize()) {
        Info("Version de l'application", SystemInfo.VERSION)
        Info("Nom de la machine", SystemInfo.hostname())
        Info("Adresses IP", SystemInfo.adressesIp().joinToString(", ").ifBlank { "aucune" })
        Info("Magasin", state.sync.magasin.ifBlank { "—" })
        Info("Identifiant de la caisse", state.sync.deviceId)
        Info("Base de données", AppPaths.dbFile.toString())
        Info("Tickets enregistrés dans", AppPaths.ticketsDir.toString())
    }
}

@Composable
private fun Info(libelle: String, valeur: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(libelle, Modifier.width(300.dp), fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valeur, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
