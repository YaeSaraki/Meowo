package com.meowo.economy.infrastructure.persistence

import com.meowo.api.*
import com.meowo.economy.domain.model.*
import com.meowo.economy.domain.repository.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/** Integer cents, IMMEDIATE transactions and durable teleport escrow. Existing wallets are retained. */
class SqliteWalletRepository(path: Path) : WalletRepository, SettlementRepository {
    private val connection: Connection
    init {
        path.toAbsolutePath().parent.toFile().mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try {
            connection.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL")
                s.execute("PRAGMA busy_timeout=5000")
                s.execute("CREATE TABLE IF NOT EXISTS wallets (player TEXT PRIMARY KEY, balance INTEGER NOT NULL CHECK(balance >= 0 AND balance <= ${Money.MAX_MINOR}))")
                s.execute("CREATE TABLE IF NOT EXISTS transactions (id TEXT PRIMARY KEY, player TEXT NOT NULL, before_amount INTEGER NOT NULL, after_amount INTEGER NOT NULL, operation TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
                s.execute("CREATE INDEX IF NOT EXISTS transactions_player ON transactions(player)")
                s.execute("CREATE TABLE IF NOT EXISTS teleport_charges (id TEXT PRIMARY KEY, player TEXT NOT NULL, fee INTEGER NOT NULL CHECK(fee >= 0), state TEXT NOT NULL CHECK(state IN ('PENDING','COMMITTED','REFUNDED')), created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS teleport_pending_player ON teleport_charges(player) WHERE state='PENDING'")
                s.execute("CREATE TABLE IF NOT EXISTS eco_treasury (id INTEGER PRIMARY KEY CHECK(id=1), balance INTEGER NOT NULL CHECK(balance >= 0 AND balance <= ${Money.MAX_MINOR}))")
                s.execute("INSERT OR IGNORE INTO eco_treasury VALUES (1,0)")
                s.execute("CREATE TABLE IF NOT EXISTS eco_ledger (reference TEXT PRIMARY KEY, delta INTEGER NOT NULL, reason TEXT NOT NULL, player TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
                s.execute("CREATE TABLE IF NOT EXISTS daily_claims (player TEXT NOT NULL, day TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount > 0), PRIMARY KEY(player,day))")
                s.execute("CREATE TABLE IF NOT EXISTS transfers (id TEXT PRIMARY KEY, sender TEXT NOT NULL, recipient TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount > 0), created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
            }
            // Additive migration: old pending escrow still refunds its original full amount.
            ensureColumn("teleport_charges", "reason", "TEXT NOT NULL DEFAULT 'TP_TAX'")
            ensureColumn("teleport_charges", "requester", "TEXT")
            ensureColumn("teleport_charges", "teleport_tax", "INTEGER NOT NULL DEFAULT 0 CHECK(teleport_tax >= 0)")
            ensureColumn("teleport_charges", "payment_tax", "INTEGER NOT NULL DEFAULT 0 CHECK(payment_tax >= 0)")
            ensureColumn("transfers", "tax", "INTEGER NOT NULL DEFAULT 0 CHECK(tax >= 0)")
            execute("UPDATE teleport_charges SET requester=player,teleport_tax=fee WHERE requester IS NULL")
        } catch (e: Exception) { connection.close(); throw e }
    }
    private fun ensureColumn(table: String, column: String, declaration: String) {
        val exists = query("PRAGMA table_info($table)") { r ->
            var found = false
            while (r.next()) if (r.getString("name") == column) found = true
            found
        }
        if (!exists) connection.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $column $declaration") }
    }
    private fun requireTreasuryRoom(addition: Money) {
        val held = scalar("SELECT COALESCE(SUM(fee),0) FROM teleport_charges WHERE state='PENDING'")
        if (treasury().minor + held > Money.MAX_MINOR - addition.minor) throw EconomyException(EconomyError.LIMIT_EXCEEDED)
    }
    private fun execute(sql: String, vararg args: Any): Int = connection.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, value.toString()) }; s.executeUpdate()
    }
    private fun <T> query(sql: String, vararg args: Any, read: (ResultSet) -> T): T = connection.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, value -> s.setObject(i + 1, value.toString()) }; s.executeQuery().use(read)
    }
    private fun scalar(sql: String, vararg args: Any): Long = query(sql, *args) { if (it.next()) it.getLong(1) else 0L }
    private fun <T> atomic(action: () -> T): T {
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val value = action()
            connection.createStatement().use { it.execute("COMMIT") }
            return value
        } catch (e: Exception) {
            try { connection.createStatement().use { it.execute("ROLLBACK") } } catch (rollback: Exception) { e.addSuppressed(rollback) }
            throw e
        }
    }
    @Synchronized override fun getOrCreate(player: UUID): Wallet {
        execute("INSERT OR IGNORE INTO wallets(player,balance) VALUES (?,0)", player)
        return Wallet(player, Money.fromMinor(scalar("SELECT balance FROM wallets WHERE player=?", player)))
    }
    private fun pending(player: UUID) = scalar("SELECT COALESCE(SUM(fee),0) FROM teleport_charges WHERE player=? AND state='PENDING'", player)
    private fun write(before: Wallet, after: Money, operation: Operation): Transaction {
        // Keep refund headroom even when a deposit, payment or admin set arrives during teleport.
        if (after.minor > Money.MAX_MINOR - pending(before.player)) throw EconomyException(EconomyError.LIMIT_EXCEEDED)
        val tx = Transaction(UUID.randomUUID(), before.player, before.balance, after, operation)
        check(execute("UPDATE wallets SET balance=? WHERE player=?", after.minor, before.player) == 1)
        execute("INSERT INTO transactions(id,player,before_amount,after_amount,operation) VALUES (?,?,?,?,?)",
            tx.id, tx.player, tx.before.minor, tx.after.minor, operation.name)
        return tx
    }
    @Synchronized override fun change(player: UUID, operation: Operation, update: (Wallet) -> Wallet): Transaction = atomic {
        val before = getOrCreate(player)
        val after = update(before)
        require(after.player == player)
        write(before, after.balance, operation)
    }
    @Synchronized override fun transfer(from: UUID, to: UUID, amount: Money, policy: EconomyPolicy): Settlement<TransferReceipt> = atomic {
        if (from == to) throw EconomyException(EconomyError.SELF_TRANSFER)
        if (amount.minor == 0L) throw EconomyException(EconomyError.INVALID_AMOUNT)
        val tax = policy.paymentFee(amount)
        val sender = getOrCreate(from); val recipient = getOrCreate(to)
        val debit = write(sender, sender.balance - (amount + tax), Operation.PAY_SENT)
        val credit = write(recipient, recipient.balance + amount, Operation.PAY_RECEIVED)
        requireTreasuryRoom(tax)
        execute("UPDATE eco_treasury SET balance=balance+? WHERE id=1", tax.minor)
        execute("INSERT INTO eco_ledger(reference,delta,reason,player) VALUES (?,?,'PAY_TAX',?)", "pay:${debit.id}", tax.minor, from)
        execute("INSERT INTO transfers(id,sender,recipient,amount,tax) VALUES (?,?,?,?,?)", debit.id, from, to, amount.minor, tax.minor)
        Settlement(TransferReceipt(amount.decimal(), debit.after.decimal(), credit.after.decimal(), tax.decimal()), listOf(debit, credit))
    }
    private fun charge(ticket: UUID): TeleportCharge? = query("SELECT player,fee,state,requester,teleport_tax,payment_tax FROM teleport_charges WHERE id=?", ticket) {
        if (it.next()) TeleportCharge(ticket, UUID.fromString(it.getString(1)), Money.fromMinor(it.getLong(2)).decimal(), ChargeState.valueOf(it.getString(3)),
            UUID.fromString(it.getString(4)), Money.fromMinor(it.getLong(5)).decimal(), Money.fromMinor(it.getLong(6)).decimal()) else null
    }
    @Synchronized override fun reserve(ticket: UUID, player: UUID, policy: EconomyPolicy): Settlement<TeleportCharge> = reserve(ticket, player, player, policy)
    @Synchronized override fun reserve(ticket: UUID, requester: UUID, payer: UUID, policy: EconomyPolicy): Settlement<TeleportCharge> = atomic {
        charge(ticket)?.let { require(it.player == payer && it.requester == requester); return@atomic Settlement(it) }
        if (scalar("SELECT COUNT(*) FROM teleport_charges WHERE (player=? OR requester=?) AND state='PENDING'", payer, requester) > 0)
            throw EconomyException(EconomyError.TRANSFER_PENDING)
        val traveller = getOrCreate(requester)
        val wallet = if (payer == requester) traveller else getOrCreate(payer)
        val teleportTax = policy.fee(traveller.balance)
        val paymentTax = if (payer == requester) Money.ZERO else policy.paymentFee(teleportTax)
        val fee = teleportTax + paymentTax
        val after = wallet.balance - fee
        requireTreasuryRoom(fee)
        val tx = write(wallet, after, Operation.TP_RESERVE)
        execute("INSERT INTO teleport_charges(id,player,fee,state,requester,teleport_tax,payment_tax) VALUES (?,?,?,'PENDING',?,?,?)",
            ticket, payer, fee.minor, requester, teleportTax.minor, paymentTax.minor)
        Settlement(TeleportCharge(ticket, payer, fee.decimal(), ChargeState.PENDING, requester, teleportTax.decimal(), paymentTax.decimal()), listOf(tx))
    }
    @Synchronized override fun chargeStatus(ticket: UUID): TeleportCharge? = charge(ticket)
    @Synchronized override fun reserveTax(ticket: UUID, player: UUID, assessment: TaxAssessment): Settlement<TeleportCharge> = atomic {
        charge(ticket)?.let {
            require(it.player == player && query("SELECT reason FROM teleport_charges WHERE id=?", ticket) { r -> r.next(); r.getString(1) } == assessment.reason.name)
            return@atomic Settlement(it)
        }
        if (scalar("SELECT COUNT(*) FROM teleport_charges WHERE (player=? OR requester=?) AND state='PENDING'", player, player) > 0)
            throw EconomyException(EconomyError.TRANSFER_PENDING)
        val wallet = getOrCreate(player)
        val fee = Money.of(assessment.amount(wallet.balance.decimal()))
        requireTreasuryRoom(fee)
        val tx = write(wallet, wallet.balance - fee, Operation.TP_RESERVE)
        execute("INSERT INTO teleport_charges(id,player,fee,state,requester,teleport_tax,payment_tax,reason) VALUES (?,?,?,'PENDING',?,?,0,?)",
            ticket, player, fee.minor, player, fee.minor, assessment.reason.name)
        Settlement(TeleportCharge(ticket, player, fee.decimal(), ChargeState.PENDING), listOf(tx))
    }
    @Synchronized override fun settle(ticket: UUID, success: Boolean): Settlement<TeleportCharge> = atomic { settleInside(ticket, success) }
    private fun settleInside(ticket: UUID, success: Boolean): Settlement<TeleportCharge> {
        val old = requireNotNull(charge(ticket)) { "Unknown teleport charge: $ticket" }
        if (old.state != ChargeState.PENDING) return Settlement(old)
        val next = old.copy(state = if (success) ChargeState.COMMITTED else ChargeState.REFUNDED)
        execute("UPDATE teleport_charges SET state=? WHERE id=?", next.state.name, ticket)
        val fee = Money.of(old.fee)
        val changes = if (success) {
            execute("UPDATE eco_treasury SET balance=balance+? WHERE id=1", fee.minor)
            val reason = query("SELECT reason FROM teleport_charges WHERE id=?", ticket) { r -> r.next(); r.getString(1) }
            execute("INSERT INTO eco_ledger(reference,delta,reason,player) VALUES (?,?,?,?)", ticket, Money.of(old.teleportTax).minor, reason, old.requester)
            if (old.paymentTax.signum() > 0) execute("INSERT INTO eco_ledger(reference,delta,reason,player) VALUES (?,?,'PAY_TAX',?)",
                "sponsor:$ticket", Money.of(old.paymentTax).minor, old.player)
            emptyList()
        } else {
            val wallet = getOrCreate(old.player)
            listOf(write(wallet, wallet.balance + fee, Operation.TP_REFUND))
        }
        return Settlement(next, changes)
    }
    @Synchronized override fun cancel(ticket: UUID): Settlement<Unit> = atomic {
        Settlement(Unit, if (charge(ticket) == null) emptyList() else settleInside(ticket, false).changes)
    }
    @Synchronized override fun recoverPending(): List<Transaction> = atomic {
        val tickets = query("SELECT id FROM teleport_charges WHERE state='PENDING'") { r -> buildList { while (r.next()) add(UUID.fromString(r.getString(1))) } }
        tickets.flatMap { settleInside(it, false).changes }
    }
    @Synchronized override fun claimDaily(player: UUID, date: LocalDate, amount: Money): Settlement<Money> = atomic {
        val wallet = getOrCreate(player)
        if (scalar("SELECT COUNT(*) FROM daily_claims WHERE player=? AND day=?", player, date) > 0) return@atomic Settlement(Money.ZERO)
        val paid = Money.fromMinor(minOf(amount.minor, treasury().minor, Money.MAX_MINOR - wallet.balance.minor - pending(player)))
        // An empty pool does not consume today's eligibility; /eco daily can retry later.
        if (paid.minor == 0L) return@atomic Settlement(Money.ZERO)
        val tx = write(wallet, wallet.balance + paid, Operation.DAILY_SUBSIDY)
        execute("INSERT INTO daily_claims(player,day,amount) VALUES (?,?,?)", player, date, paid.minor)
        execute("UPDATE eco_treasury SET balance=balance-? WHERE id=1", paid.minor)
        execute("INSERT INTO eco_ledger(reference,delta,reason,player) VALUES (?,?,'DAILY_SUBSIDY',?)", "daily:$player:$date", -paid.minor, player)
        Settlement(paid, listOf(tx))
    }
    @Synchronized override fun treasury(): Money = Money.fromMinor(scalar("SELECT balance FROM eco_treasury WHERE id=1"))
    @Synchronized override fun close() { if (!connection.isClosed) connection.close() }
}
