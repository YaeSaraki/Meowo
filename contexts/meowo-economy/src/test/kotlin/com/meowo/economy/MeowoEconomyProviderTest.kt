package com.meowo.economy

import com.meowo.api.event.BalanceChanged
import com.meowo.economy.application.EconomyApplicationService
import com.meowo.economy.domain.service.EconomyDomainService
import com.meowo.economy.infrastructure.persistence.SqliteWalletRepository
import com.meowo.shared.event.EventBus
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * The provider shop plugins will actually call.
 *
 * The property worth protecting is the **round trip**: Vault speaks `double` and the ledger speaks
 * `BigDecimal`, so a shop charging a tenth of a coin must not find the balance has drifted. The
 * scale on both sides is 2 and these tests hold them to it.
 */
class MeowoEconomyProviderTest {

    @TempDir
    lateinit var dir: Path

    private val events = mutableListOf<BalanceChanged>()
    private lateinit var server: ServerMock
    private lateinit var alice: org.bukkit.entity.Player

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        // One player for the whole test: `addPlayer` mints a new UUID every time, so calling it
        // per assertion would silently talk about a different account each time.
        alice = server.addPlayer("alice")
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() { MockBukkit.unmock() }
    private var fileCount = 0

    private fun provider(): MeowoEconomyProvider {
        events.clear()
        val repo = SqliteWalletRepository(dir.resolve("p${fileCount++}.db"))
        // A bus only dispatches to subscribers it was told about, so the collector has to subscribe
        // like any real listener would.
        val bus = EventBus { _, _ -> }
        bus.subscribe(BalanceChanged::class.java) { events += it }
        val service = EconomyApplicationService(EconomyDomainService(repo), bus, repo::close)
        return MeowoEconomyProvider(service, "Meo")
    }

    private fun offline(): OfflinePlayer = alice

    @Test fun `an account always exists, because it is made on first use`() {
        val p = provider()
        assertTrue(p.isEnabled)
        assertTrue(p.hasAccount(offline()), "a shop asking before a purchase must not be told no")
        assertEquals("Meowo", p.name)
        assertEquals(0.0, p.getBalance(offline()), 0.0)
    }

    @Test fun `money survives the double round trip exactly`() {
        val p = provider()
        assertEquals(EconomyResponse.ResponseType.SUCCESS, p.depositPlayer(offline(), 1234.56).type)
        assertEquals(1234.56, p.getBalance(offline()), 0.0)
        assertEquals("1234.56", p.format(1234.56))
    }

    @Test fun `a tenth of a coin is neither lost nor invented`() {
        val p = provider()
        p.depositPlayer(offline(), 0.10)
        p.depositPlayer(offline(), 0.20)
        assertEquals(0.30, p.getBalance(offline()), 0.0)
    }

    @Test fun `a withdrawal is refused rather than driving the balance negative`() {
        val p = provider()
        p.depositPlayer(offline(), 10.0)
        val out = p.withdrawPlayer(offline(), 50.0)
        assertEquals(EconomyResponse.ResponseType.FAILURE, out.type)
        assertEquals(10.0, p.getBalance(offline()), 0.0, "a failed withdrawal must not have taken anything")
    }

    @Test fun `a negative amount is refused`() {
        val p = provider()
        assertEquals(EconomyResponse.ResponseType.FAILURE, p.depositPlayer(offline(), -5.0).type)
    }

    /**
     * The line a shop reads to decide whether its purchase worked. Reporting the balance *before*
     * the change is the classic way to make a shop charge twice or not at all.
     */
    @Test fun `a purchase takes the money and reports the new balance`() {
        val p = provider()
        p.depositPlayer(offline(), 100.0)
        events.clear()
        val out = p.withdrawPlayer(offline(), 30.0)
        assertEquals(EconomyResponse.ResponseType.SUCCESS, out.type)
        assertEquals(70.0, out.balance, 0.0, "Vault must report the post-transaction balance")
        assertEquals(70.0, p.getBalance(offline()), 0.0)
        assertEquals(1, events.size, "the same event the plugin's own API publishes")
    }

    /**
     * `NOT_IMPLEMENTED` rather than `FAILURE`: a shop that sees `FAILURE` may tell the player the
     * bank name was taken, and Vault distinguishes the two for exactly this.
     */
    @Test fun `banks are refused as not implemented rather than as a failure`() {
        val p = provider()
        assertFalse(p.hasBankSupport())
        assertEquals(EconomyResponse.ResponseType.NOT_IMPLEMENTED, p.createBank("b", offline()).type)
        assertEquals(EconomyResponse.ResponseType.NOT_IMPLEMENTED, p.bankBalance("b").type)
        assertEquals(EconomyResponse.ResponseType.NOT_IMPLEMENTED, p.bankWithdraw("b", 1.0).type)
        assertTrue(p.banks.isEmpty())
    }

    @Test fun `the currency name comes from configuration`() {
        val p = provider()
        assertEquals("Meo", p.currencyNamePlural())
        assertEquals("Meo", p.currencyNameSingular())
        assertEquals(2, p.fractionalDigits())
    }

    @Test fun `a name resolves to the same account as the uuid`() {
        val p = provider()
        p.depositPlayer("alice", 50.0)
        assertEquals(50.0, p.getBalance("alice"), 0.0)
        assertTrue(p.has("alice", 50.0))
        assertFalse(p.has("alice", 50.01), "a tenth over must not pass")
    }
}
