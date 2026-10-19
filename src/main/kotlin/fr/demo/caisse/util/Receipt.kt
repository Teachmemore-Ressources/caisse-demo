package fr.demo.caisse.util

import fr.demo.caisse.data.AppPaths
import fr.demo.caisse.domain.Money
import fr.demo.caisse.domain.Panier
import fr.demo.caisse.model.Cloture
import fr.demo.caisse.model.Comptage
import fr.demo.caisse.model.ModePaiement
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.model.TypeLigne
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Mise en forme texte (40 colonnes) des tickets, clôtures et comptages. */
object Receipt {
    private const val LARGEUR = 40

    private fun centre(s: String) = s.padStart((LARGEUR + s.length) / 2).take(LARGEUR)
    private fun ligne(g: String, d: String): String {
        val espace = (LARGEUR - g.length - d.length).coerceAtLeast(1)
        return g.take(LARGEUR - d.length - 1) + " ".repeat(espace) + d
    }
    private fun trait() = "-".repeat(LARGEUR)

    fun date(iso: String): String =
        runCatching { LocalDateTime.parse(iso).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) }.getOrDefault(iso)

    fun ticket(t: Ticket, magasin: String): String = buildString {
        appendLine(centre(magasin.ifBlank { "CAISSE DEMO" }))
        appendLine(centre(if (t.estRetour) "RETOUR - Ticket n° ${t.numero}" else "Ticket n° ${t.numero}"))
        appendLine(centre(date(t.dateIso)))
        appendLine(centre("Caissier : ${t.caissier}"))
        t.client?.let { appendLine(centre("Client : ${it.nom}")) }
        t.retourDe?.let { appendLine(centre("Retour du ticket n° $it")) }
        appendLine(trait())
        t.lignes.forEach { l ->
            val prefixe = if (l.type == TypeLigne.RETOUR) "RETOUR " else ""
            appendLine(ligne("$prefixe${l.qte} x ${l.nom}", Money.format(l.totalCents)))
            if (l.remisePct > 0) appendLine("   remise ${l.remisePct} %")
        }
        if (t.remiseCents > 0) appendLine(ligne("Remise ticket", "-" + Money.format(t.remiseCents)))
        appendLine(trait())
        appendLine(ligne("TOTAL TTC", Money.format(t.totalCents)))
        appendLine(trait())
        appendLine("TVA        HT       TVA      TTC")
        Panier.ventiler(t.lignes, t.remiseCents).forEach { v ->
            appendLine(
                Money.formatTaux(v.tvaBp).padEnd(8) +
                    Money.format(v.htCents).padStart(8) +
                    Money.format(v.tvaCents).padStart(9) +
                    Money.format(v.ttcCents).padStart(9),
            )
        }
        appendLine(trait())
        t.paiements.forEach { p -> appendLine(ligne(p.mode.libelle, Money.format(p.recuCents))) }
        if (t.renduCents > 0) appendLine(ligne("Rendu monnaie", Money.format(t.renduCents)))
        t.client?.let { c -> if (t.totalCents > 0) appendLine(ligne("Points fidélité gagnés", "+${t.totalCents / 100}")) }
        appendLine()
        appendLine(centre("Merci de votre visite"))
    }

    fun cloture(c: Cloture, magasin: String): String = buildString {
        appendLine(centre(magasin.ifBlank { "CAISSE DEMO" }))
        appendLine(centre("CLOTURE DE CAISSE n° ${c.id}"))
        appendLine(centre(date(c.dateIso)))
        appendLine(centre("Par : ${c.caissier}"))
        appendLine(trait())
        appendLine(ligne("Nombre de tickets", c.nbTickets.toString()))
        ModePaiement.entries.forEach { m -> appendLine(ligne(m.libelle, Money.format(c.total(m)))) }
        appendLine(trait())
        appendLine(ligne("TOTAL", Money.format(c.totalCents)))
    }

    fun comptage(c: Comptage, magasin: String, detail: List<Pair<String, String>>): String = buildString {
        appendLine(centre(magasin.ifBlank { "CAISSE DEMO" }))
        appendLine(centre("COMPTAGE - ${c.type.libelle.uppercase()}"))
        appendLine(centre(date(c.dateIso)))
        appendLine(centre("Par : ${c.caissier}"))
        appendLine(trait())
        detail.forEach { (k, v) -> appendLine(ligne(k, v)) }
        appendLine(trait())
        appendLine(ligne("Espèces comptées", Money.format(c.totalEspecesCents)))
        appendLine(ligne("Autres moyens", Money.format(c.totalAutresCents)))
        appendLine(ligne("Montant théorique", Money.format(c.theoriqueCents)))
        appendLine(ligne("Écart", Money.format(c.ecartCents)))
    }

    /** Sauvegarde le ticket dans ~/.local/share/caisse-demo/tickets et tente une impression (`lp`). */
    fun sauvegarderEtImprimer(numero: Int, texte: String): String {
        Files.createDirectories(AppPaths.ticketsDir)
        val f = AppPaths.ticketsDir.resolve("ticket-%06d.txt".format(numero))
        Files.writeString(f, texte)
        val imprime = runCatching {
            val p = ProcessBuilder("lp").redirectErrorStream(true).start()
            p.outputStream.use { it.write(texte.toByteArray()) }
            p.waitFor() == 0
        }.getOrDefault(false)
        return if (imprime) "Ticket envoyé à l'imprimante" else "Ticket enregistré : $f"
    }
}
