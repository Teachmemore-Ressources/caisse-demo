package fr.demo.caisse.data

import fr.demo.caisse.model.Caissier
import fr.demo.caisse.model.Client
import fr.demo.caisse.model.Cloture
import fr.demo.caisse.model.Comptage
import fr.demo.caisse.model.LigneTicket
import fr.demo.caisse.model.ModePaiement
import fr.demo.caisse.model.Mouvement
import fr.demo.caisse.model.Paiement
import fr.demo.caisse.model.Produit
import fr.demo.caisse.model.Role
import fr.demo.caisse.model.Ticket
import fr.demo.caisse.model.TypeComptage
import fr.demo.caisse.model.TypeLigne
import fr.demo.caisse.model.TypeMouvement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Hash du PIN partagé avec le serveur (SaaS) : sha256("caisse-demo:" + pin). */
fun hashPin(pin: String): String =
    MessageDigest.getInstance("SHA-256").digest("caisse-demo:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

/** Base SQLite locale de la caisse (un seul fichier, accès sérialisé, migrations additives). */
class Database(val path: Path) {
    private val conn: Connection

    init {
        path.parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA foreign_keys=ON")
        }
        creerSchema()
    }

    private inline fun <T> sync(block: () -> T): T = synchronized(this) { block() }

    private fun colonneExiste(table: String, colonne: String): Boolean =
        conn.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info($table)").use { rs ->
                var trouve = false
                while (rs.next()) if (rs.getString("name") == colonne) trouve = true
                trouve
            }
        }

    /** Migration additive : une base créée par une version précédente reçoit les nouvelles colonnes. */
    private fun assurerColonne(table: String, colonne: String, definition: String) {
        if (!colonneExiste(table, colonne)) conn.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $colonne $definition") }
    }

    private fun creerSchema() = sync {
        conn.createStatement().use { s ->
            s.execute("CREATE TABLE IF NOT EXISTS caissier(id INTEGER PRIMARY KEY, nom TEXT NOT NULL, pin_sha256 TEXT NOT NULL, role TEXT NOT NULL, code TEXT NOT NULL DEFAULT '')")
            s.execute("CREATE TABLE IF NOT EXISTS produit(code TEXT PRIMARY KEY, nom TEXT NOT NULL, prix_cents INTEGER NOT NULL, tva_bp INTEGER NOT NULL, categorie TEXT NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS client(id INTEGER PRIMARY KEY, nom TEXT NOT NULL, carte TEXT NOT NULL, points INTEGER NOT NULL DEFAULT 0, actif INTEGER NOT NULL DEFAULT 1)")
            s.execute(
                "CREATE TABLE IF NOT EXISTS ticket(uuid TEXT PRIMARY KEY, numero INTEGER NOT NULL, date_iso TEXT NOT NULL, caissier TEXT NOT NULL, " +
                    "total_cents INTEGER NOT NULL, mode TEXT NOT NULL, recu_cents INTEGER NOT NULL, rendu_cents INTEGER NOT NULL, " +
                    "synced INTEGER NOT NULL DEFAULT 0, cloture_id INTEGER)",
            )
            s.execute(
                "CREATE TABLE IF NOT EXISTS ligne(id INTEGER PRIMARY KEY AUTOINCREMENT, ticket_uuid TEXT NOT NULL REFERENCES ticket(uuid), " +
                    "code TEXT NOT NULL, nom TEXT NOT NULL, qte INTEGER NOT NULL, prix_cents INTEGER NOT NULL, tva_bp INTEGER NOT NULL, remise_pct INTEGER NOT NULL)",
            )
            s.execute("CREATE TABLE IF NOT EXISTS paiement(id INTEGER PRIMARY KEY AUTOINCREMENT, ticket_uuid TEXT NOT NULL REFERENCES ticket(uuid), mode TEXT NOT NULL, montant INTEGER NOT NULL, rendu INTEGER NOT NULL DEFAULT 0)")
            s.execute(
                "CREATE TABLE IF NOT EXISTS cloture(id INTEGER PRIMARY KEY AUTOINCREMENT, date_iso TEXT NOT NULL, caissier TEXT NOT NULL, " +
                    "nb_tickets INTEGER NOT NULL, total_especes INTEGER NOT NULL, total_carte INTEGER NOT NULL)",
            )
            s.execute("CREATE TABLE IF NOT EXISTS mouvement(id INTEGER PRIMARY KEY AUTOINCREMENT, date_iso TEXT NOT NULL, type TEXT NOT NULL, montant INTEGER NOT NULL, caissier TEXT NOT NULL, motif TEXT NOT NULL DEFAULT '', cloture_id INTEGER)")
            s.execute(
                "CREATE TABLE IF NOT EXISTS comptage(id INTEGER PRIMARY KEY AUTOINCREMENT, date_iso TEXT NOT NULL, type TEXT NOT NULL, caissier TEXT NOT NULL, " +
                    "total_especes INTEGER NOT NULL, total_autres INTEGER NOT NULL, theorique INTEGER NOT NULL, detail TEXT NOT NULL DEFAULT '', cloture_id INTEGER)",
            )
            s.execute("CREATE TABLE IF NOT EXISTS config(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        }
        // colonnes ajoutées depuis la première version
        assurerColonne("caissier", "code", "TEXT NOT NULL DEFAULT ''")
        assurerColonne("ticket", "client_nom", "TEXT")
        assurerColonne("ticket", "client_carte", "TEXT")
        assurerColonne("ticket", "remise_cents", "INTEGER NOT NULL DEFAULT 0")
        assurerColonne("ticket", "retour_de", "INTEGER")
        assurerColonne("ligne", "type", "TEXT NOT NULL DEFAULT 'PRODUIT'")
        assurerColonne("cloture", "total_cheque", "INTEGER NOT NULL DEFAULT 0")
        assurerColonne("cloture", "total_resto", "INTEGER NOT NULL DEFAULT 0")
    }

    // ---- configuration -------------------------------------------------------------------

    fun config(cle: String): String? = sync {
        conn.prepareStatement("SELECT v FROM config WHERE k=?").use { ps ->
            ps.setString(1, cle)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    fun setConfig(cle: String, valeur: String) = sync {
        conn.prepareStatement("INSERT INTO config(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v").use { ps ->
            ps.setString(1, cle)
            ps.setString(2, valeur)
            ps.executeUpdate()
        }
    }

    // ---- référentiel (produits, caissiers, clients) -------------------------------------

    fun caissiers(): List<Caissier> = sync {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT id, nom, role, code FROM caissier ORDER BY role DESC, CASE WHEN code = '' THEN nom ELSE code END").use { rs ->
                buildList { while (rs.next()) add(Caissier(rs.getLong(1), rs.getString(2), Role.valueOf(rs.getString(3)), rs.getString(4))) }
            }
        }
    }

    /**
     * Met à jour l'identité d'un caissier de démonstration créé par une version précédente (nom réel d'origine -> nouveaux nom et code).
     * Ne touche que les lignes encore au nom d'origine : un nom modifié par le serveur (SaaS) n'est jamais écrasé.
     */
    fun renommerCaissierSiAncien(id: Long, anciensNoms: List<String>, nom: String, code: String) = sync {
        val marques = anciensNoms.joinToString(",") { "?" }
        conn.prepareStatement("UPDATE caissier SET nom=?, code=? WHERE id=? AND nom IN ($marques)").use { ps ->
            ps.setString(1, nom); ps.setString(2, code); ps.setLong(3, id)
            anciensNoms.forEachIndexed { i, n -> ps.setString(4 + i, n) }
            ps.executeUpdate()
        }
    }

    /** Vérifie le PIN d'un caissier précis (écran de connexion : on choisit d'abord son nom). */
    fun verifierPinCaissier(id: Long, pin: String): Caissier? = sync {
        conn.prepareStatement("SELECT id, nom, role, code FROM caissier WHERE id=? AND pin_sha256=?").use { ps ->
            ps.setLong(1, id); ps.setString(2, hashPin(pin))
            ps.executeQuery().use { rs -> if (rs.next()) Caissier(rs.getLong(1), rs.getString(2), Role.valueOf(rs.getString(3)), rs.getString(4)) else null }
        }
    }

    /** Vérifie un PIN sans connaître le caissier : sert à l'autorisation « responsable ». */
    fun verifierPin(pin: String): Caissier? = sync {
        conn.prepareStatement("SELECT id, nom, role, code FROM caissier WHERE pin_sha256=?").use { ps ->
            ps.setString(1, hashPin(pin))
            ps.executeQuery().use { rs -> if (rs.next()) Caissier(rs.getLong(1), rs.getString(2), Role.valueOf(rs.getString(3)), rs.getString(4)) else null }
        }
    }

    fun produits(): List<Produit> = sync {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT code, nom, prix_cents, tva_bp, categorie FROM produit ORDER BY categorie, nom").use { rs ->
                buildList { while (rs.next()) add(Produit(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getString(5))) }
            }
        }
    }

    fun nbProduits(): Int = sync {
        conn.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM produit").use { it.next(); it.getInt(1) } }
    }

    fun clients(): List<Client> = sync {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT id, nom, carte, points, actif FROM client ORDER BY nom").use { rs ->
                buildList { while (rs.next()) add(Client(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5) == 1)) }
            }
        }
    }

    fun nbClients(): Int = sync {
        conn.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM client").use { it.next(); it.getInt(1) } }
    }

    fun remplacerClients(clients: List<Client>) = sync {
        conn.autoCommit = false
        try {
            conn.createStatement().use { it.execute("DELETE FROM client") }
            conn.prepareStatement("INSERT INTO client(id,nom,carte,points,actif) VALUES(?,?,?,?,?)").use { ps ->
                clients.forEach { c ->
                    ps.setLong(1, c.id); ps.setString(2, c.nom); ps.setString(3, c.carte); ps.setInt(4, c.points); ps.setInt(5, if (c.actif) 1 else 0)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Remplace tout le référentiel dans une transaction (le serveur est la source de vérité). */
    fun remplacerReferentiel(produits: List<Produit>, caissiers: List<Pair<Caissier, String>>) = sync {
        conn.autoCommit = false
        try {
            conn.createStatement().use { it.execute("DELETE FROM produit"); it.execute("DELETE FROM caissier") }
            conn.prepareStatement("INSERT INTO produit(code,nom,prix_cents,tva_bp,categorie) VALUES(?,?,?,?,?)").use { ps ->
                produits.forEach { p ->
                    ps.setString(1, p.code); ps.setString(2, p.nom); ps.setLong(3, p.prixCents); ps.setInt(4, p.tvaBp); ps.setString(5, p.categorie)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.prepareStatement("INSERT INTO caissier(id,nom,pin_sha256,role,code) VALUES(?,?,?,?,?)").use { ps ->
                caissiers.forEach { (c, pinHash) ->
                    ps.setLong(1, c.id); ps.setString(2, c.nom); ps.setString(3, pinHash); ps.setString(4, c.role.name); ps.setString(5, c.code)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    // ---- tickets -------------------------------------------------------------------------

    fun prochainNumero(): Int = sync {
        conn.createStatement().use { s -> s.executeQuery("SELECT COALESCE(MAX(numero),0)+1 FROM ticket").use { it.next(); it.getInt(1) } }
    }

    fun enregistrerTicket(t: Ticket) = sync {
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT INTO ticket(uuid,numero,date_iso,caissier,total_cents,mode,recu_cents,rendu_cents,synced,client_nom,client_carte,remise_cents,retour_de) " +
                    "VALUES(?,?,?,?,?,?,?,?,0,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, t.uuid); ps.setInt(2, t.numero); ps.setString(3, t.dateIso); ps.setString(4, t.caissier)
                ps.setLong(5, t.totalCents); ps.setString(6, t.mode); ps.setLong(7, t.recuCents); ps.setLong(8, t.renduCents)
                ps.setString(9, t.client?.nom); ps.setString(10, t.client?.carte); ps.setLong(11, t.remiseCents)
                if (t.retourDe != null) ps.setInt(12, t.retourDe) else ps.setNull(12, java.sql.Types.INTEGER)
                ps.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO ligne(ticket_uuid,code,nom,qte,prix_cents,tva_bp,remise_pct,type) VALUES(?,?,?,?,?,?,?,?)").use { ps ->
                t.lignes.forEach { l ->
                    ps.setString(1, t.uuid); ps.setString(2, l.code); ps.setString(3, l.nom); ps.setInt(4, l.qte)
                    ps.setLong(5, l.prixCents); ps.setInt(6, l.tvaBp); ps.setInt(7, l.remisePct); ps.setString(8, l.type.name)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.prepareStatement("INSERT INTO paiement(ticket_uuid,mode,montant,rendu) VALUES(?,?,?,?)").use { ps ->
                t.paiements.forEach { p ->
                    ps.setString(1, t.uuid); ps.setString(2, p.mode.name); ps.setLong(3, p.montantCents); ps.setLong(4, p.renduCents)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun lignesDe(uuid: String): List<LigneTicket> =
        conn.prepareStatement("SELECT code,nom,qte,prix_cents,tva_bp,remise_pct,type FROM ligne WHERE ticket_uuid=? ORDER BY id").use { ps ->
            ps.setString(1, uuid)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(LigneTicket(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getInt(5), rs.getInt(6), TypeLigne.valueOf(rs.getString(7))))
                }
            }
        }

    private fun paiementsDe(uuid: String): List<Paiement> =
        conn.prepareStatement("SELECT mode,montant,rendu FROM paiement WHERE ticket_uuid=? ORDER BY id").use { ps ->
            ps.setString(1, uuid)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Paiement(ModePaiement.valueOf(rs.getString(1)), rs.getLong(2), rs.getLong(3))) }
            }
        }

    private fun versTicket(rs: ResultSet): Ticket {
        val uuid = rs.getString("uuid")
        val total = rs.getLong("total_cents")
        val nom = rs.getString("client_nom")
        val client = nom?.let { Client(0, it, rs.getString("client_carte").orEmpty(), 0) }
        var paiements = paiementsDe(uuid)
        if (paiements.isEmpty()) { // ticket de la première version : un seul paiement porté par la table ticket
            val mode = runCatching { ModePaiement.valueOf(rs.getString("mode")) }.getOrDefault(ModePaiement.ESPECES)
            paiements = listOf(Paiement(mode, total, rs.getLong("rendu_cents")))
        }
        val retour = rs.getInt("retour_de").takeIf { !rs.wasNull() }
        return Ticket(
            uuid, rs.getInt("numero"), rs.getString("date_iso"), rs.getString("caissier"), client, lignesDe(uuid),
            rs.getLong("remise_cents"), total, paiements, rs.getInt("synced") == 1, retour,
        )
    }

    private fun tickets(where: String, limite: Int? = null, desc: Boolean = false): List<Ticket> =
        conn.prepareStatement(
            "SELECT * FROM ticket WHERE $where ORDER BY numero ${if (desc) "DESC" else "ASC"}" + (limite?.let { " LIMIT $it" } ?: ""),
        ).use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(versTicket(rs)) } }
        }

    fun ticketsNonSynchronises(limite: Int = 100): List<Ticket> = sync { tickets("synced=0", limite) }

    fun nbTicketsNonSynchronises(): Int = sync {
        conn.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM ticket WHERE synced=0").use { it.next(); it.getInt(1) } }
    }

    fun marquerSynchronises(uuids: Collection<String>) = sync {
        if (uuids.isEmpty()) return@sync
        conn.prepareStatement("UPDATE ticket SET synced=1 WHERE uuid=?").use { ps ->
            uuids.forEach { ps.setString(1, it); ps.addBatch() }
            ps.executeBatch()
        }
    }

    fun ticketsNonClotures(): List<Ticket> = sync { tickets("cloture_id IS NULL") }

    /** Tickets de la journée (plus récents d'abord) pour le journal de caisse. */
    fun ticketsDuJour(): List<Ticket> = sync { tickets("date_iso LIKE '${LocalDate.now()}%'", desc = true) }

    fun ticket(numero: Int): Ticket? = sync { tickets("numero=$numero", 1).firstOrNull() }

    /** Un ticket ne peut être retourné qu'une fois. */
    fun dejaRetourne(numero: Int): Boolean = sync {
        conn.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM ticket WHERE retour_de=$numero").use { it.next(); it.getInt(1) > 0 } }
    }

    /** Totaux attendus par moyen de paiement hors espèces (tickets non clôturés), pour le comptage. */
    fun theoriquesParMoyen(): Map<ModePaiement, Long> = sync {
        val tickets = tickets("cloture_id IS NULL")
        ModePaiement.entries.associateWith { m -> tickets.sumOf { t -> t.paiements.filter { it.mode == m }.sumOf { it.montantCents } } }
    }

    fun dernierTicket(): Ticket? = sync { tickets("1=1", 1, desc = true).firstOrNull() }

    /** Chiffre d'affaires du jour en centimes (les retours sont négatifs). */
    fun ventesDuJourCents(): Long = sync {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT COALESCE(SUM(total_cents),0) FROM ticket WHERE date_iso LIKE '${LocalDate.now()}%'").use { it.next(); it.getLong(1) }
        }
    }

    fun nbTicketsDuJour(): Int = sync {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM ticket WHERE date_iso LIKE '${LocalDate.now()}%'").use { it.next(); it.getInt(1) }
        }
    }

    // ---- mouvements d'espèces, comptages, clôture ------------------------------------------

    private fun maintenant(): String = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

    fun enregistrerMouvement(type: TypeMouvement, montantCents: Long, caissier: String, motif: String) = sync {
        conn.prepareStatement("INSERT INTO mouvement(date_iso,type,montant,caissier,motif) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, maintenant()); ps.setString(2, type.name); ps.setLong(3, montantCents); ps.setString(4, caissier); ps.setString(5, motif)
            ps.executeUpdate()
        }
    }

    fun mouvementsOuverts(): List<Mouvement> = sync {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT id,date_iso,type,montant,caissier,motif FROM mouvement WHERE cloture_id IS NULL ORDER BY id DESC").use { rs ->
                buildList { while (rs.next()) add(Mouvement(rs.getLong(1), rs.getString(2), TypeMouvement.valueOf(rs.getString(3)), rs.getLong(4), rs.getString(5), rs.getString(6))) }
            }
        }
    }

    /** Fond de caisse : espèces comptées à la dernière ouverture non clôturée. */
    fun fondOuverture(): Long = sync {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT total_especes FROM comptage WHERE type='OUVERTURE' AND cloture_id IS NULL ORDER BY id DESC LIMIT 1").use { if (it.next()) it.getLong(1) else 0L }
        }
    }

    /** Espèces attendues dans le tiroir : fond + espèces encaissées (net) + entrées - sorties - prélèvements. */
    fun theoriqueEspeces(): Long = sync {
        val especes = tickets("cloture_id IS NULL").sumOf { t -> t.paiements.filter { it.mode == ModePaiement.ESPECES }.sumOf { it.montantCents } }
        val mouvements = conn.createStatement().use { s ->
            s.executeQuery("SELECT type, montant FROM mouvement WHERE cloture_id IS NULL").use { rs ->
                var total = 0L
                while (rs.next()) total += TypeMouvement.valueOf(rs.getString(1)).signe * rs.getLong(2)
                total
            }
        }
        fondOuverture() + especes + mouvements
    }

    fun enregistrerComptage(type: TypeComptage, caissier: String, especesCents: Long, autresCents: Long, theoriqueCents: Long, detail: String): Comptage = sync {
        val date = maintenant()
        val id = conn.prepareStatement(
            "INSERT INTO comptage(date_iso,type,caissier,total_especes,total_autres,theorique,detail) VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, date); ps.setString(2, type.name); ps.setString(3, caissier)
            ps.setLong(4, especesCents); ps.setLong(5, autresCents); ps.setLong(6, theoriqueCents); ps.setString(7, detail)
            ps.executeUpdate()
            ps.generatedKeys.use { it.next(); it.getLong(1) }
        }
        Comptage(id, date, type, caissier, especesCents, autresCents, theoriqueCents)
    }

    fun comptagesRecents(n: Int = 8): List<Comptage> = sync {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT id,date_iso,type,caissier,total_especes,total_autres,theorique FROM comptage ORDER BY id DESC LIMIT $n").use { rs ->
                buildList { while (rs.next()) add(Comptage(rs.getLong(1), rs.getString(2), TypeComptage.valueOf(rs.getString(3)), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7))) }
            }
        }
    }

    /** Clôture tous les tickets non clôturés : un « Z » de caisse. Renvoie null s'il n'y a rien à clôturer. */
    fun cloturer(caissier: String): Cloture? = sync {
        val tickets = tickets("cloture_id IS NULL")
        if (tickets.isEmpty()) return@sync null
        val totaux = ModePaiement.entries.associateWith { m -> tickets.sumOf { t -> t.paiements.filter { it.mode == m }.sumOf { it.montantCents } } }
        val date = maintenant()
        conn.autoCommit = false
        try {
            val id = conn.prepareStatement(
                "INSERT INTO cloture(date_iso,caissier,nb_tickets,total_especes,total_carte,total_cheque,total_resto) VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setString(1, date); ps.setString(2, caissier); ps.setInt(3, tickets.size)
                ps.setLong(4, totaux.getValue(ModePaiement.ESPECES)); ps.setLong(5, totaux.getValue(ModePaiement.CARTE))
                ps.setLong(6, totaux.getValue(ModePaiement.CHEQUE)); ps.setLong(7, totaux.getValue(ModePaiement.TICKET_RESTO))
                ps.executeUpdate()
                ps.generatedKeys.use { it.next(); it.getLong(1) }
            }
            conn.createStatement().use {
                it.executeUpdate("UPDATE ticket SET cloture_id=$id WHERE cloture_id IS NULL")
                it.executeUpdate("UPDATE mouvement SET cloture_id=$id WHERE cloture_id IS NULL")
                it.executeUpdate("UPDATE comptage SET cloture_id=$id WHERE cloture_id IS NULL")
            }
            conn.commit()
            Cloture(id, date, caissier, tickets.size, totaux)
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun clotures(n: Int = 10): List<Cloture> = sync {
        conn.createStatement().use { s ->
            s.executeQuery("SELECT id,date_iso,caissier,nb_tickets,total_especes,total_carte,total_cheque,total_resto FROM cloture ORDER BY id DESC LIMIT $n").use { rs ->
                buildList {
                    while (rs.next()) add(
                        Cloture(
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            mapOf(ModePaiement.ESPECES to rs.getLong(5), ModePaiement.CARTE to rs.getLong(6), ModePaiement.CHEQUE to rs.getLong(7), ModePaiement.TICKET_RESTO to rs.getLong(8)),
                        ),
                    )
                }
            }
        }
    }

    /** Efface toute l'activité de la caisse (tickets, comptages, clôtures) ; produits, caissiers et clients sont conservés. */
    fun reinitialiser() = sync {
        conn.autoCommit = false
        try {
            conn.createStatement().use { s ->
                listOf("paiement", "ligne", "ticket", "mouvement", "comptage", "cloture").forEach { s.execute("DELETE FROM $it") }
                s.execute("DELETE FROM config WHERE k='panier_en_cours'")
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun close() = sync { conn.close() }
}
