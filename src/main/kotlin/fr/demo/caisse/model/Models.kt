package fr.demo.caisse.model

import fr.demo.caisse.domain.Money

enum class Role { CAISSIER, RESPONSABLE }

/**
 * `nom` est l'identité réelle (tickets, journal, comptages, SaaS). `code` est le nom de code affiché sur l'écran de connexion :
 * on ne montre pas l'identité des personnes à tout le monde devant la caisse.
 */
data class Caissier(val id: Long, val nom: String, val role: Role, val code: String = "") {
    val estResponsable: Boolean get() = role == Role.RESPONSABLE

    /** Ce que l'écran de connexion affiche. Sans code défini par le serveur : « Manager » ou « Caissier 03 ». */
    val libelleConnexion: String get() = code.ifBlank { if (estResponsable) "Manager" else "Caissier ${"%02d".format(id)}" }
    val initiales: String get() = nom.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
}

data class Produit(
    val code: String,
    val nom: String,
    val prixCents: Long,
    val tvaBp: Int,
    val categorie: String,
)

data class Client(
    val id: Long,
    val nom: String,
    val carte: String,
    val points: Int,
    val actif: Boolean = true,
)

enum class TypeLigne { PRODUIT, RETOUR }

enum class ModePaiement(val libelle: String, val rendMonnaie: Boolean) {
    ESPECES("Espèces", true),
    CARTE("Carte bancaire", false),
    CHEQUE("Chèque", false),
    TICKET_RESTO("Ticket restaurant", false),
}

/** Un règlement : `montantCents` est le montant imputé au ticket (net du rendu de monnaie). */
data class Paiement(val mode: ModePaiement, val montantCents: Long, val renduCents: Long = 0) {
    val recuCents: Long get() = montantCents + renduCents
}

data class LigneTicket(
    val code: String,
    val nom: String,
    val qte: Int,
    val prixCents: Long,
    val tvaBp: Int,
    val remisePct: Int = 0,
    val type: TypeLigne = TypeLigne.PRODUIT,
) {
    val signe: Int get() = if (type == TypeLigne.RETOUR) -1 else 1

    /** Total TTC signé de la ligne (négatif pour un retour), remise appliquée. */
    val totalCents: Long get() = signe * Money.appliquerRemise(prixCents * qte, remisePct)
}

data class Ticket(
    val uuid: String,
    val numero: Int,
    val dateIso: String,
    val caissier: String,
    val client: Client?,
    val lignes: List<LigneTicket>,
    val remiseCents: Long,
    val totalCents: Long,
    val paiements: List<Paiement>,
    val synced: Boolean = false,
    val retourDe: Int? = null,
) {
    /** Mode de paiement unique, ou MIXTE si plusieurs moyens ont servi. */
    val mode: String get() = paiements.singleOrNull()?.mode?.name ?: if (paiements.isEmpty()) "AUCUN" else "MIXTE"
    val recuCents: Long get() = paiements.sumOf { it.recuCents }
    val renduCents: Long get() = paiements.sumOf { it.renduCents }
    val estRetour: Boolean get() = totalCents < 0
}

enum class TypeComptage(val libelle: String) {
    OUVERTURE("Ouverture"),
    CONTROLE("Contrôle"),
    CLOTURE("Clôture"),
}

enum class TypeMouvement(val libelle: String, val signe: Int) {
    ENTREE("Entrée d'espèces", 1),
    SORTIE("Sortie d'espèces", -1),
    PRELEVEMENT("Prélèvement", -1),
}

data class Comptage(
    val id: Long,
    val dateIso: String,
    val type: TypeComptage,
    val caissier: String,
    val totalEspecesCents: Long,
    val totalAutresCents: Long,
    val theoriqueCents: Long,
) {
    val ecartCents: Long get() = totalEspecesCents - theoriqueCents
}

data class Mouvement(val id: Long, val dateIso: String, val type: TypeMouvement, val montantCents: Long, val caissier: String, val motif: String)

data class Cloture(
    val id: Long,
    val dateIso: String,
    val caissier: String,
    val nbTickets: Int,
    val totaux: Map<ModePaiement, Long>,
) {
    val totalCents: Long get() = totaux.values.sum()
    fun total(mode: ModePaiement): Long = totaux[mode] ?: 0L
}
