package fr.demo.caisse.net

import fr.demo.caisse.model.LigneTicket
import fr.demo.caisse.model.Ticket
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// ---- contrat d'API (voir docs/API.md) -----------------------------------------------------

@Serializable
data class ActivationRequest(val code: String, @SerialName("device_id") val deviceId: String, val hostname: String)

@Serializable
data class ActivationResponse(val token: String, val magasin: String, val tpv: String)

@Serializable
data class ProduitDto(
    val code: String,
    val nom: String,
    @SerialName("prix_cents") val prixCents: Long,
    @SerialName("tva_bp") val tvaBp: Int,
    val categorie: String,
)

@Serializable
data class CaissierDto(val id: Long, val nom: String, val role: String, @SerialName("pin_sha256") val pinSha256: String, val code: String = "")

@Serializable
data class ReferentielDto(val version: Int = 0, val produits: List<ProduitDto>, val caissiers: List<CaissierDto>)

@Serializable
data class LigneDto(
    val code: String,
    val nom: String,
    val qte: Int,
    @SerialName("prix_cents") val prixCents: Long,
    @SerialName("tva_bp") val tvaBp: Int,
    @SerialName("remise_pct") val remisePct: Int,
    val type: String = "PRODUIT",
)

@Serializable
data class PaiementDto(val mode: String, @SerialName("montant_cents") val montantCents: Long, @SerialName("rendu_cents") val renduCents: Long)

@Serializable
data class TicketDto(
    val uuid: String,
    val numero: Int,
    val date: String,
    val caissier: String,
    val client: String? = null,
    val lignes: List<LigneDto>,
    @SerialName("remise_cents") val remiseCents: Long = 0,
    @SerialName("total_cents") val totalCents: Long,
    /** ESPECES, CARTE, CHEQUE, TICKET_RESTO ou MIXTE (détail dans `paiements`). */
    val mode: String,
    val paiements: List<PaiementDto> = emptyList(),
    @SerialName("recu_cents") val recuCents: Long,
    @SerialName("rendu_cents") val renduCents: Long,
    @SerialName("retour_de") val retourDe: Int? = null,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class TicketsRequest(val tickets: List<TicketDto>)

@Serializable
data class TicketsResponse(val accepted: List<String>)

fun Ticket.toDto(deviceId: String): TicketDto = TicketDto(
    uuid = uuid, numero = numero, date = dateIso, caissier = caissier, client = client?.nom,
    lignes = lignes.map { l: LigneTicket -> LigneDto(l.code, l.nom, l.qte, l.prixCents, l.tvaBp, l.remisePct, l.type.name) },
    remiseCents = remiseCents, totalCents = totalCents, mode = mode,
    paiements = paiements.map { PaiementDto(it.mode.name, it.montantCents, it.renduCents) },
    recuCents = recuCents, renduCents = renduCents, retourDe = retourDe, deviceId = deviceId,
)

class SaasException(message: String, val status: Int? = null) : Exception(message)

/** Client HTTP du SaaS. Toutes les méthodes sont bloquantes : à appeler depuis Dispatchers.IO. */
class SaasClient(baseUrl: String, private val token: String? = null) {
    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("$base$path"))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }

    private fun send(req: HttpRequest): String {
        val rep = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw SaasException("Serveur injoignable : ${e.message ?: e.javaClass.simpleName}")
        }
        if (rep.statusCode() !in 200..299) throw SaasException("Réponse ${rep.statusCode()} du serveur", rep.statusCode())
        return rep.body()
    }

    fun ping(): Boolean = try {
        send(request("/api/v1/health").GET().build()); true
    } catch (e: SaasException) {
        false
    }

    fun activer(code: String, deviceId: String, hostname: String): ActivationResponse {
        val corps = json.encodeToString(ActivationRequest(code, deviceId, hostname))
        val req = request("/api/v1/tpv/activate").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(corps)).build()
        return json.decodeFromString(send(req))
    }

    fun referentiel(): ReferentielDto = json.decodeFromString(send(request("/api/v1/sync/referentiel").GET().build()))

    /** Envoie des tickets ; renvoie les UUID acceptés (idempotent côté serveur). */
    fun envoyerTickets(tickets: List<TicketDto>): List<String> {
        val corps = json.encodeToString(TicketsRequest(tickets))
        val req = request("/api/v1/tickets").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(corps)).build()
        return json.decodeFromString<TicketsResponse>(send(req)).accepted
    }
}
