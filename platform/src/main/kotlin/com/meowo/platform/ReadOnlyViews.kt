package com.meowo.platform

import com.meowo.api.TeleportCharge
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletionStage

/**
 * Narrowed views of other contexts' APIs, for callers that must not be able to write.
 *
 * ## Why this lives here and not in `api/`
 *
 * `api/` is the public contract for third-party plugins, and it deliberately has no platform
 * dependencies (`api/build.gradle.kts`). It is the right place to say *what the server can do*,
 * which is why `EconomyAPI` carries `balance` next to `deposit`.
 *
 * It is the wrong place to say *what one particular caller is allowed to do*. A read-only view is a
 * **capability restriction**, not a new capability, and declaring it as a new interface in `api/`
 * would have said two wrong things at once: that the system had gained a capability, and that the
 * implementing context had gained a second interface to keep in sync.
 *
 * So the narrowing happens here, at the same layer that already decides what a context may see
 * (`PlatformHost`). The public contract stays exactly as it is, full-featured, for anyone who
 * genuinely needs to move money.
 *
 * ## The guarantee is structural, not a convention
 *
 * A tool that holds a [BalanceReader] cannot call `deposit` — the method is not on any type it can
 * name. This is the same class of guarantee as the one `ReadTool` gives (no write method is
 * expressible on it), and it is enforced by the compiler rather than by review. Nobody has to
 * remember to check: the code does not compile otherwise.
 *
 * ## These interfaces stay pure
 *
 * No Bukkit types appear in any signature here, which is what lets a `pure` context such as
 * `meowo-agents/domain` depend on them without tripping `verifyArchitecture` (red line 01 §1).
 */

/** Everything a cat may read about an economy. Nothing here can change a balance. */
interface BalanceReader {
    fun balance(player: UUID): CompletionStage<BigDecimal>
}

/** Everything a cat may read about a settlement. All ten write methods are absent by design. */
interface SettlementReader {
    fun teleportFee(player: UUID): CompletionStage<BigDecimal>

    fun treasury(): CompletionStage<BigDecimal>

    /** The status of a charge already reserved, if it is still around. */
    fun chargeStatus(ticket: UUID): CompletionStage<TeleportCharge?>
}

/** A player's homes, as a cat is allowed to see them: names and places, never the owner's UUID. */
interface HomeReader {
    fun capacity(owner: UUID): Int

    fun entries(owner: UUID): List<HomeEntry>
}

/**
 * One home, reduced to what a cat may repeat out loud.
 *
 * A separate type from `com.meowo.api.HomeMenuEntry` on purpose: that one is a menu slot, and a
 * menu slot is a rendering concern. This one is a fact, and it has no slot in it to get wrong.
 */
data class HomeEntry(val name: String, val world: String, val x: Double, val y: Double, val z: Double)

/** Turns a full API into the narrow view of it, at wiring time. */
object ReadOnly {

    /**
     * The settlement that goes with a wallet, when there is one.
     *
     * The lookup is a lambda because this file must not reach for Bukkit's ServicesManager, and
     * because a server may legitimately run wallets with no fee system attached.
     */
    fun settlementOrNull(lookup: () -> com.meowo.api.SettlementAPI?): SettlementReader? =
        lookup()?.let { settlement(it) }

    /**
     * Adapts an economy implementation. The three write methods are simply not forwarded, so they
     * are unreachable from anything holding the result.
     */
    fun balance(api: com.meowo.api.EconomyAPI): BalanceReader = object : BalanceReader {
        override fun balance(player: UUID): CompletionStage<BigDecimal> = api.balance(player)
    }

    /**
     * Adapts a settlement implementation.
     *
     * Only the three readers are forwarded. `transfer`, the two `reserveTeleport` overloads,
     * `settleTeleport`, `cancelTeleport`, `claimDaily` and `reserveTax` are not, so a caller holding
     * this type cannot reach them.
     */
    fun settlement(api: com.meowo.api.SettlementAPI): SettlementReader = object : SettlementReader {
        override fun teleportFee(player: UUID): CompletionStage<BigDecimal> = api.teleportFee(player)

        override fun treasury(): CompletionStage<BigDecimal> = api.treasury()

        override fun chargeStatus(ticket: UUID): CompletionStage<TeleportCharge?> = api.chargeStatus(ticket)
    }

    /** Adapts the home API, dropping the menu-only fields. */
    fun homes(api: com.meowo.api.HomeMenuAPI): HomeReader = object : HomeReader {
        override fun capacity(owner: UUID): Int = api.capacity(owner)

        override fun entries(owner: UUID): List<HomeEntry> = api.entries(owner).map {
            HomeEntry(it.name, it.world, it.x, it.y, it.z)
        }
    }
}
