package com.meowo.core.config

import com.meowo.economy.domain.model.EconomyPolicy
import com.meowo.economy.domain.model.Money
import com.meowo.platform.CurrencyFormat
import com.meowo.platform.LocaleResolver
import com.meowo.spawn.SpawnSettings
import com.meowo.mine.MineSettings
import com.meowo.nametag.NametagSettings
import com.meowo.sit.SitSettings
import com.meowo.hat.HatSettings
import com.meowo.chat.ChatSettings
import com.meowo.agents.AgentsSettings
import com.meowo.avatar.AvatarSettings
import com.meowo.avatar.domain.ScaleLimits
import com.meowo.shadowbox.ShadowBoxSettings
import com.meowo.shop.ShopSettings
import com.meowo.teleport.TeleportSettings
import com.meowo.home.HomeSettings
import com.meowo.invsee.InvseeSettings
import com.meowo.pomodoro.PomodoroSettings
import com.meowo.webapi.WebApiSettings
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.time.ZoneId

/**
 * The plugin's configuration, read from `plugins/Meowo/config.yml`.
 *
 * ## Where the defaults live
 *
 * **In the shipped `config.yml`, and nowhere else.**
 *
 * That sentence is the whole redesign. Every option used to have its default written twice — once
 * as a literal in [read] and once in the YAML that ships in the jar — and two copies of one
 * default are two facts that can disagree. They did:
 *
 * | option | in the file | in the code |
 * |---|---|---|
 * | `shadowbox.give-permission` | `true` | `false` |
 *
 * The file's own comment promised `true`. The code promised `false`. An operator who deleted the
 * line to "go back to the default" got the opposite of what the file had been telling them, and
 * the only symptom was that `/shadowbox give` stopped handing out boxes. Nobody notices a
 * default until it changes, which is exactly why a default that is written down twice is a bug
 * waiting for its day.
 *
 * So the code no longer carries defaults. It asks the shipped file for the value of a key by name,
 * and the shipped file is the only place that knows what an option is worth. When the operator's
 * file has the key, their value is used; when it does not, the shipped one is. There is no third
 * answer to fall back to and therefore no way to drift.
 *
 * ## What happens when the operator writes something impossible
 *
 * **It is reported and the plugin keeps running.**
 *
 * A value of the wrong shape falls back to the default and becomes a [ConfigIssue]. A value of
 * the right shape but out of range is caught by the option's own `require` — in the context that
 * owns the rule — and repaired the same way, one option at a time (see [ConfigResolver.guard]).
 *
 * The previous behaviour was to throw, and a throw during `onEnable` disables the plugin: one
 * wrong number in a 170-key file took down the economy, the menus and the twenty other features
 * with it, and the console showed a stack trace whose first line was about a YAML path rather
 * than about the number that was wrong.
 *
 * ## The context list
 *
 * The set of contexts is read from the shipped `contexts` block rather than written down here,
 * so adding a context means adding it in one place. It used to be in three: here, in the YAML, and
 * in `settings.gradle.kts`. The build gate checks that the YAML and the Gradle settings agree —
 * and that the runtime actually enables every context the YAML offers. See
 * `ConfigSchemaTest` in the `core` module's tests: the lists are derived from the tree, so a
 * context added later is checked without anyone editing the gate.
 */
data class GlobalConfig(
    val locale: String,
    val languageMode: String,
    val contexts: Set<String>,
    val renderLimit: Int,
    val currency: CurrencyFormat,
    val economy: EconomyPolicy,
    val teleport: TeleportSettings,
    val spawn: SpawnSettings,
    val sit: SitSettings,
    val hat: HatSettings,
    val shadowBox: ShadowBoxSettings,
    val nametag: NametagSettings,
    val home: HomeSettings,
    val mine: MineSettings,
    val chat: ChatSettings,
    val agents: AgentsSettings,
    val avatar: AvatarSettings,
    val invsee: InvseeSettings,
    val pomodoro: PomodoroSettings,
    val shop: ShopSettings,
    val webapi: WebApiSettings,
    /** Everything the operator should be told about their file. Never empty in practice. */
    val issues: List<ConfigIssue> = emptyList(),
    /** Whether to log the effective configuration and every issue at start-up. */
    val debug: Boolean = false,
    /**
     * Every option the plugin knows about, with the value it will actually use.
     *
     * The file shows what the operator *wrote*; this shows what the plugin *understood*. The two
     * are the same until they are not, and `/meowo config` exists to make that difference
     * visible.
     */
    val effective: Map<String, String> = emptyMap(),
) {
    companion object {

        /**
         * Reads the operator's file.
         *
         * The shipped file is read from the jar. [shipped] is a parameter only so a test can supply
         * a different one; production always uses the copy in the jar.
         */
        fun read(file: File, shipped: InputStream? = null): GlobalConfig {
            val defaults = YamlConfiguration.loadConfiguration(
                (shipped ?: GlobalConfig::class.java.getResourceAsStream("/config.yml")
                    ?: error("config.yml is missing from the jar"))
                    .reader(Charsets.UTF_8),
            )
            val operator = if (file.isFile) YamlConfiguration().apply { load(file) } else null
            val resolver = ConfigResolver(defaults, operator)

            // A section of the operator's file that the plugin does not know is worth saying out
            // loud before anything else: it is the most common reason a setting "does nothing".
            resolver.audit()

            fun text(key: String): String = resolver.text(key)
            fun bool(key: String): Boolean = resolver.bool(key)
            fun integer(key: String): Int = resolver.int(key)
            fun decimal(key: String): BigDecimal = resolver.decimal(key)

            val locale = resolver.guard("locale") {
                text("locale").also {
                    require(it in LocaleResolver.SUPPORTED) { "locale 必须是 ${LocaleResolver.SUPPORTED} 之一" }
                }
            }
            val languageMode = resolver.guard("language-mode") {
                text("language-mode").also {
                    require(it == "player" || it == "server") { "language-mode 只能是 player 或 server" }
                }
            }
            val debug = resolver.guard("debug") { bool("debug") }

            // The context list comes from the shipped file, so the YAML is the one place that says
            // which contexts exist. `agents` ships as `false` because it is the only context that
            // can spend money per message: a server has to ask for it rather than inherit it.
            val names = resolver.shippedKeys("contexts")
            val contexts = names.filter { bool("contexts.$it") }.toSet()

            val limit = resolver.guard("render.max-per-player") {
                integer("render.max-per-player").also { require(it in 1..1000) { "必须在 1..1000 之间" } }
            }
            val currency = resolver.guard("economy.currency") {
                CurrencyFormat(text("economy.currency.name"), text("economy.currency.symbol"))
            }
            val policy = resolver.guard("economy") {
                EconomyPolicy(
                    decimal("economy.teleport-tax.rate"),
                    Money.of(decimal("economy.teleport-tax.minimum")),
                    Money.of(decimal("economy.teleport-tax.maximum")),
                    bool("economy.daily-subsidy.enabled"),
                    Money.of(decimal("economy.daily-subsidy.amount")),
                    ZoneId.of(text("economy.daily-subsidy.timezone")),
                    decimal("economy.pay-tax.rate"),
                )
            }

            val teleport = resolver.guard("teleport") {
                TeleportSettings(
                    requestSeconds = integer("teleport.request-seconds"),
                    particles = bool("teleport.particles"),
                    countdownSeconds = integer("teleport.countdown-seconds"),
                    backRate = decimal("teleport-back.rate"),
                    backMinimum = decimal("teleport-back.minimum"),
                    backMaximum = decimal("teleport-back.maximum"),
                    backOnDeath = bool("teleport-back.on-death"),
                )
            }
            val spawn = resolver.guard("spawn") {
                SpawnSettings(
                    text("spawn.world"),
                    integer("spawn.warmup-seconds"),
                    integer("spawn.cooldown-seconds"),
                    integer("spawn.invulnerability-seconds"),
                    bool("spawn.center-align"),
                    bool("spawn.safety"),
                    integer("spawn.safe-search-range"),
                )
            }
            val sit = resolver.guard("sit") {
                SitSettings(
                    integer("sit.target-range"),
                    bool("sit.right-click-blocks"),
                    decimal("sit.max-distance").toDouble(),
                    resolver.offset("sit.stair-offset-y"),
                    resolver.offset("sit.slab-offset-y"),
                    resolver.offset("sit.carpet-offset-y"),
                    resolver.forward("sit.stair-forward"),
                    resolver.forward("sit.slab-forward"),
                    resolver.forward("sit.carpet-forward"),
                )
            }
            val hat = resolver.guard("hat") {
                HatSettings(
                    bool("hat.allow-tools"),
                    resolver.stringList("hat.deny").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet(),
                    bool("hat.inventory-slot"),
                )
            }
            val shadowBox = resolver.guard("shadowbox") {
                // A material that cannot hold an inventory is downgraded to the default rather than
                // rejected, so a typo never takes the whole /meowo reload down with it.
                ShadowBoxSettings(
                    ShadowBoxSettings.materialOf(text("shadowbox.material")),
                    text("shadowbox.material"),
                    bool("shadowbox.give-permission"),
                )
            }
            val nametag = resolver.guard("nametag") {
                NametagSettings(
                    bool("nametag.enabled"),
                    integer("nametag.afk-after-seconds"),
                    integer("nametag.check-interval-seconds"),
                )
            }
            val home = resolver.guard("home") {
                HomeSettings(
                    integer("home.limit"),
                    decimal("home.set-tax.rate"),
                    decimal("home.minimum"),
                    decimal("home.maximum"),
                    decimal("home.distance-divisor"),
                    decimal("home.cross-world-fee"),
                    bool("home.cross-world"),
                    integer("home.countdown-seconds"),
                    bool("home.particles"),
                    bool("home.locator"),
                    bool("home.bossbar"),
                    bool("home.force"),
                )
            }
            val mine = resolver.guard("mine") {
                MineSettings(
                    bool("mine.announce"),
                    bool("mine.sound"),
                    bool("mine.highlight-first"),
                    integer("mine.max-vein-size"),
                )
            }
            val chat = resolver.guard("chat") {
                ChatSettings(
                    maxLength = integer("chat.max-length"),
                    warnAt = integer("chat.warn-at"),
                    blockAt = integer("chat.block-at"),
                    failOpen = bool("chat.fail-open"),
                    auditCapacity = integer("chat.audit-capacity"),
                    literalFilters = resolver.stringList("chat.literal-filters").map { it.trim() }.filter { it.isNotEmpty() },
                    regexFilters = resolver.stringList("chat.regex-filters").map { it.trim() }.filter { it.isNotEmpty() },
                    playerFirst = bool("chat.player-first"),
                    helpdesk = bool("chat.helpdesk"),
                    mailCapacity = integer("chat.mail-capacity"),
                    logCapacity = integer("chat.log-capacity"),
                    logDays = integer("chat.log-days"),
                    extraEmoji = emojiTable(resolver),
                )
            }
            val agents = resolver.guard("agents") {
                AgentsSettings(
                    baseUrl = text("agents.base-url"),
                    apiKey = text("agents.api-key"),
                    model = text("agents.model"),
                    // 512 is the measured floor for a reasoning model to finish thinking and still
                    // emit its tool call; 80 truncated every response and looked like a model that
                    // would not call tools at all. 1024 leaves room for the *reply* as well —
                    // thinking and answer share one allowance, and the answer is now allowed to
                    // be 320 characters.
                    maxTokens = integer("agents.max-tokens"),
                    preflight = bool("agents.preflight"),
                    workers = integer("agents.workers"),
                    backlog = integer("agents.backlog"),
                    dailyCap = integer("agents.daily-cap"),
                    perPlayerCap = integer("agents.per-player-cap"),
                    cooldownSeconds = integer("agents.cooldown-seconds").toLong(),
                    proactive = bool("agents.proactive"),
                    disciplineIntervalSeconds = integer("agents.discipline-interval-seconds").toLong(),
                    storePath = text("agents.store"),
                    impressionDays = integer("agents.impression-days"),
                )
            }
            val avatar = resolver.guard("avatar") {
                AvatarSettings(
                    enabled = bool("avatar.enabled"),
                    // The size grid. The numbers are the settings; the rules about what a step is live
                    // in the domain, where they can be tested without a server.
                    scale = ScaleLimits(
                        min = decimal("avatar.scale.min").toDouble(),
                        max = decimal("avatar.scale.max").toDouble(),
                        step = decimal("avatar.scale.step").toDouble(),
                        default = decimal("avatar.scale.default").toDouble(),
                    ),
                )
            }
            val invsee = resolver.guard("invsee") {
                InvseeSettings(audit = bool("invsee.audit"))
            }
            val pomodoro = resolver.guard("pomodoro") {
                PomodoroSettings(
                    maxDistance = decimal("pomodoro.max-distance").toDouble(),
                    bossbarDefault = bool("pomodoro.bossbar-default"),
                    titleDefault = bool("pomodoro.title-default"),
                    textDisplayOffsetY = decimal("pomodoro.text-display.offset-y").toDouble(),
                    titleRefreshSeconds = integer("pomodoro.title-refresh-seconds"),
                    bossbarColors = PomodoroSettings.BossbarColors(
                        work = text("pomodoro.bossbar-colors.work"),
                        breakColor = text("pomodoro.bossbar-colors.break"),
                        longBreakColor = text("pomodoro.bossbar-colors.long-break"),
                        pausedColor = text("pomodoro.bossbar-colors.paused"),
                    ),
                    customPresetLimit = integer("pomodoro.custom-preset-limit"),
                    sounds = PomodoroSettings.PomodoroSounds(
                        uiClick = text("pomodoro.sounds.ui-click"),
                        uiSuccess = text("pomodoro.sounds.ui-success"),
                        uiFail = text("pomodoro.sounds.ui-fail"),
                        timerEndWarning = text("pomodoro.sounds.timer-end-warning"),
                        nextCycle = text("pomodoro.sounds.next-cycle"),
                        leaveWarning = text("pomodoro.sounds.leave-warning"),
                        completeAllRounds = text("pomodoro.sounds.complete-all-rounds"),
                        newRoundStart = text("pomodoro.sounds.new-round-start"),
                    ),
                    // The bundled defaults travel inside the pomodoro module's jar, not core's, so
                    // the lookup is by classpath root rather than by a data-folder file. A missing
                    // resource fails the reload loudly instead of silently shipping zero presets.
                    builtinPresets = PomodoroSettings.defaults(
                        requireNotNull(PomodoroSettings::class.java.classLoader.getResourceAsStream("pomodoro-default-presets.yml")) {
                            "pomodoro-default-presets.yml is missing from the classpath"
                        },
                    ),
                )
            }
            val webapi = resolver.guard("webapi") {
                // An empty secret is not repaired here on purpose: the context refuses to start
                // with it, and a port nobody can authenticate against is worse than no port.
                WebApiSettings(
                    port = integer("webapi.port").also { require(it in 0..65535) { "端口必须在 0..65535 之间" } },
                    bind = text("webapi.bind").also { require(it.isNotBlank()) { "监听地址不能为空" } },
                    secret = text("webapi.secret"),
                    snapshotIntervalSeconds = integer("webapi.snapshot-interval-seconds")
                        .also { require(it in 5..3600) { "快照间隔必须在 5..3600 秒之间" } },
                    statisticsMode = text("webapi.statistics-mode")
                        .also { require(it == "full" || it == "basic") { "统计明细只能是 full 或 basic" } },
                    maxPlayers = integer("webapi.max-players")
                        .also { require(it in 1..1000) { "单次快照人数必须在 1..1000 之间" } },
                    allowedIps = resolver.stringList("webapi.allowed-ips")
                        .map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                )
            }
            val shop = resolver.guard("shop") {
                // The operator's table overrides the shipped one entry by entry: a server that
                // writes only `COAL_ORE` changes coal and leaves every other ore alone.
                ShopSettings.withOverrides(orePrices(resolver))
            }

            return GlobalConfig(
                locale, languageMode, contexts, limit, currency, policy, teleport, spawn, sit, hat,
                shadowBox, nametag, home, mine, chat, agents, avatar, invsee, pomodoro, shop, webapi,
                issues = resolver.issues.toList(), debug = debug, effective = resolver.snapshot(),
            )
        }

        /**
         * `chat.emoji` — a flat `name: character` block the operator adds to.
         *
         * Raw YAML keys rather than a list of `name=char` strings, because a block reads like the
         * table it extends. Entries are **merged over** the bundled ones in `ChatService`, so a
         * server that defines one emoji keeps the other forty. An empty value is skipped: one
         * blank line should not take the server down over a typo.
         */
        private fun emojiTable(resolver: ConfigResolver): Map<String, String> {
            val section = resolver.section("chat.emoji") ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (key in section.getKeys(false)) {
                val value = section.getString(key)
                if (value.isNullOrBlank()) continue
                out[key.trim().lowercase()] = value.trim()
            }
            return out
        }

        /**
         * `shop.prices` — `MATERIAL: "3.00"` overrides for the shipped price table.
         *
         * An unknown material name or an unparseable amount is **dropped**, not rejected: one
         * wrong line in the file must not stop the shop from opening at all, and the key it
         * names simply is not an ore anybody can sell. `shop.prices` is a tuning file, not a
         * correctness gate.
         */
        private fun orePrices(resolver: ConfigResolver): Map<Material, BigDecimal> {
            val section = resolver.section("shop.prices") ?: return emptyMap()
            val out = LinkedHashMap<Material, BigDecimal>()
            for (key in section.getKeys(false)) {
                val material = Material.matchMaterial(key.trim()) ?: continue
                val value = runCatching { BigDecimal(section.get(key)?.toString()?.trim() ?: "") }.getOrNull() ?: continue
                if (value < BigDecimal.ZERO) continue
                out[material] = value
            }
            return out
        }
    }
}
