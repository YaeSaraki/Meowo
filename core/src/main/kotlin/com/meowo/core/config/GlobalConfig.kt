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
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.math.BigDecimal
import java.time.ZoneId

data class GlobalConfig(val locale: String, val languageMode: String, val contexts: Set<String>, val renderLimit: Int,
    val currency: CurrencyFormat, val economy: EconomyPolicy, val teleport: TeleportSettings, val spawn: SpawnSettings,
    val sit: SitSettings, val hat: HatSettings, val shadowBox: ShadowBoxSettings,
    val nametag: NametagSettings, val home: HomeSettings, val mine: MineSettings,
    val chat: ChatSettings, val agents: AgentsSettings, val avatar: AvatarSettings,
    val invsee: InvseeSettings, val pomodoro: PomodoroSettings, val shop: ShopSettings) {
    companion object {
        fun read(file: File): GlobalConfig {
            val yaml = YamlConfiguration().apply { load(file) }
            fun text(key: String, default: String): String {
                require(!yaml.contains(key) || yaml.isString(key)) { "$key must be text" }
                return yaml.getString(key, default)!!
            }
            fun bool(key: String, default: Boolean): Boolean {
                require(!yaml.contains(key) || yaml.isBoolean(key)) { "$key must be boolean" }
                return yaml.getBoolean(key, default)
            }
            fun integer(key: String, default: Int): Int {
                require(!yaml.contains(key) || yaml.isInt(key)) { "$key must be an integer" }
                return yaml.getInt(key, default)
            }
            /**
             * `chat.emoji` — a flat `name: character` block the operator adds to.
             *
             * Raw YAML keys rather than a list of `name=char` strings, because a block reads like the
             * table it extends. Entries are **merged over** the bundled ones in `ChatService`, so a
             * server that defines one emoji keeps the other forty. An empty value is skipped: one
             * blank line should not take the server down over a typo.
             */
            fun emojiTable(): Map<String, String> {
                val section = yaml.getConfigurationSection("chat.emoji") ?: return emptyMap()
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
            fun orePrices(): Map<Material, BigDecimal> {
                val section = yaml.getConfigurationSection("shop.prices") ?: return emptyMap()
                val out = LinkedHashMap<Material, BigDecimal>()
                for (key in section.getKeys(false)) {
                    val material = Material.matchMaterial(key.trim()) ?: continue
                    val value = runCatching { BigDecimal(section.get(key)?.toString()?.trim() ?: "") }.getOrNull() ?: continue
                    if (value < BigDecimal.ZERO) continue
                    out[material] = value
                }
                return out
            }
            fun decimal(key: String, default: String): BigDecimal {
                val value = yaml.get(key)?.toString() ?: default
                require(Regex("[0-9]{1,12}([.][0-9]{1,8})?").matches(value)) { "$key must be a non-negative decimal" }
                return BigDecimal(value)
            }
            /** Signed decimal for offsets, which are negative by default. `decimal` would reject them. */
            fun offset(key: String, default: String): Double {
                val value = yaml.get(key)?.toString() ?: default
                require(Regex("-?[0-9]{1,3}([.][0-9]{1,4})?").matches(value)) { "$key must be a decimal" }
                val parsed = value.toDouble()
                require(parsed in -2.0..2.0) { "$key must be between -2 and 2" }
                return parsed
            }
            /** Non-negative decimal for forward offsets, which must never be negative. */
            fun forward(key: String, default: String): Double {
                val value = yaml.get(key)?.toString() ?: default
                require(Regex("[0-9]{1,3}([.][0-9]{1,4})?").matches(value)) { "$key must be a positive decimal" }
                val parsed = value.toDouble()
                require(parsed in 0.0..2.0) { "$key must be between 0 and 2" }
                return parsed
            }
            val locale = text("locale", "zh_CN")
            require(locale in LocaleResolver.SUPPORTED) { "locale must be one of ${LocaleResolver.SUPPORTED}" }
            val languageMode = text("language-mode", "player")
            require(languageMode in setOf("player", "server")) { "language-mode must be player or server" }
            val names = setOf("economy", "menu", "render", "teleport", "spawn", "sit", "hat", "shadowbox", "nametag", "home", "mine", "chat", "agents", "avatar", "invsee", "pomodoro", "shop")
            // `agents` defaults **false** unlike everything else: it is the only context that can
            // spend money per message, so a server has to ask for it rather than inherit it.
            val contexts = names.filter { bool("contexts.$it", it != "agents") }.toSet()
            val limit = integer("render.max-per-player", 200)
            require(limit in 1..1000) { "render.max-per-player must be 1..1000" }
            val currency = CurrencyFormat(text("economy.currency.name", "Meo"), text("economy.currency.symbol", "ฅ"))
            val policy = EconomyPolicy(
                decimal("economy.teleport-tax.rate", "0.001"),
                Money.of(decimal("economy.teleport-tax.minimum", "1")),
                Money.of(decimal("economy.teleport-tax.maximum", "233")),
                bool("economy.daily-subsidy.enabled", true),
                Money.of(decimal("economy.daily-subsidy.amount", "1")),
                ZoneId.of(text("economy.daily-subsidy.timezone", "Asia/Shanghai")),
                decimal("economy.pay-tax.rate", "0.01"),
            )
            return GlobalConfig(locale, languageMode, contexts, limit, currency, policy,
                TeleportSettings(
                    requestSeconds = integer("teleport.request-seconds", 60),
                    particles = bool("teleport.particles", true),
                    countdownSeconds = integer("teleport.countdown-seconds", 3),
                    backRate = java.math.BigDecimal(text("teleport-back.rate", "0.001")),
                    backMinimum = java.math.BigDecimal(text("teleport-back.minimum", "10")),
                    backMaximum = java.math.BigDecimal(text("teleport-back.maximum", "233")),
                    backOnDeath = bool("teleport-back.on-death", true),
                ),
                SpawnSettings(
                    text("spawn.world", ""),
                    integer("spawn.warmup-seconds", 3),
                    integer("spawn.cooldown-seconds", 5),
                    integer("spawn.invulnerability-seconds", 4),
                    bool("spawn.center-align", true),
                    bool("spawn.safety", true),
                    integer("spawn.safe-search-range", 16),
                ),
                SitSettings(
                    integer("sit.target-range", 3),
                    bool("sit.right-click-blocks", true),
                    decimal("sit.max-distance", "4").toDouble(),
                    offset("sit.stair-offset-y", "0.1"),
                    offset("sit.slab-offset-y", "-0.15"),
                    offset("sit.carpet-offset-y", "-0.5"),
                    forward("sit.stair-forward", "0.35"),
                    forward("sit.slab-forward", "0.2"),
                    forward("sit.carpet-forward", "0"),
                ),
                HatSettings(
                    bool("hat.allow-tools", false),
                    yaml.getStringList("hat.deny").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet(),
                    bool("hat.inventory-slot", true),
                ),
                // A material that cannot hold an inventory is downgraded to the default rather than
                // rejected, so a typo never takes the whole /meowo reload down with it.
                ShadowBoxSettings(
                    ShadowBoxSettings.materialOf(text("shadowbox.material", "SHULKER_BOX")),
                    text("shadowbox.material", "SHULKER_BOX"),
                    bool("shadowbox.give-permission", false),
                ),
                NametagSettings(
                    bool("nametag.enabled", true),
                    integer("nametag.afk-after-seconds", 180),
                    integer("nametag.check-interval-seconds", 1),
                ),
                HomeSettings(
                    integer("home.limit",10),
                    decimal("home.set-tax.rate","0.05"),
                    decimal("home.minimum","100"), decimal("home.maximum","2333"),
                    decimal("home.distance-divisor","10"),decimal("home.cross-world-fee","2333"),
                    bool("home.cross-world",true),integer("home.countdown-seconds",3),bool("home.particles",true),
                    bool("home.locator",true),bool("home.bossbar",bool("home.actionbar",true)),
                    bool("home.force",true),
                ),
                MineSettings(
                    bool("mine.announce", true),
                    bool("mine.sound", true),
                    bool("mine.highlight-first", true),
                    integer("mine.max-vein-size", 32),
                ),
                ChatSettings(
                    maxLength = integer("chat.max-length", 256),
                    warnAt = integer("chat.warn-at", 40),
                    blockAt = integer("chat.block-at", 80),
                    failOpen = bool("chat.fail-open", false),
                    auditCapacity = integer("chat.audit-capacity", 512),
                    literalFilters = yaml.getStringList("chat.literal-filters").map { it.trim() }.filter { it.isNotEmpty() },
                    regexFilters = yaml.getStringList("chat.regex-filters").map { it.trim() }.filter { it.isNotEmpty() },
                    playerFirst = bool("chat.player-first", false),
                    helpdesk = bool("chat.helpdesk", true),
                    mailCapacity = integer("chat.mail-capacity", 32),
                    logCapacity = integer("chat.log-capacity", 4096),
                    logDays = integer("chat.log-days", 15),
                    extraEmoji = emojiTable(),
                ),
                AgentsSettings(
                    baseUrl = text("agents.base-url", ""),
                    apiKey = text("agents.api-key", ""),
                    model = text("agents.model", "MiniMax-M3"),
                    // 512 is the measured floor for a reasoning model to finish thinking and still
                    // emit its tool call; 80 truncated every response and looked like a model that
                    // would not call tools at all. 1024 leaves room for the *reply* as well —
                    // thinking and answer share one allowance, and the answer is now allowed to
                    // be 320 characters.
                    maxTokens = integer("agents.max-tokens", 1024),
                    preflight = bool("agents.preflight", false),
                    workers = integer("agents.workers", 2),
                    backlog = integer("agents.backlog", 32),
                    dailyCap = integer("agents.daily-cap", 500),
                    perPlayerCap = integer("agents.per-player-cap", 20),
                    cooldownSeconds = integer("agents.cooldown-seconds", 10).toLong(),
                    proactive = bool("agents.proactive", false),
                    disciplineIntervalSeconds = integer("agents.discipline-interval-seconds", 300).toLong(),
                    storePath = text("agents.store", "agents/profile.db"),
                    impressionDays = integer("agents.impression-days", 15),
                ),
                AvatarSettings(
                    enabled = bool("avatar.enabled", true),
                    // The size grid. The numbers are the settings; the rules about what a step is live
                    // in the domain, where they can be tested without a server.
                    scale = ScaleLimits(
                        min = decimal("avatar.scale.min", "0.5").toDouble(),
                        max = decimal("avatar.scale.max", "3.0").toDouble(),
                        step = decimal("avatar.scale.step", "0.25").toDouble(),
                        default = decimal("avatar.scale.default", "1.0").toDouble(),
                    ),
                ),
                InvseeSettings(
                    audit = bool("invsee.audit", true),
                ),
                PomodoroSettings(
                    maxDistance = decimal("pomodoro.max-distance", "16").toDouble(),
                    bossbarDefault = bool("pomodoro.bossbar-default", true),
                    titleDefault = bool("pomodoro.title-default", true),
                    textDisplayOffsetY = decimal("pomodoro.text-display.offset-y", "2.4").toDouble(),
                    titleRefreshSeconds = integer("pomodoro.title-refresh-seconds", 5),
                    bossbarColors = PomodoroSettings.BossbarColors(
                        work = text("pomodoro.bossbar-colors.work", "GREEN"),
                        breakColor = text("pomodoro.bossbar-colors.break", "BLUE"),
                        longBreakColor = text("pomodoro.bossbar-colors.long-break", "YELLOW"),
                        pausedColor = text("pomodoro.bossbar-colors.paused", "RED"),
                    ),
                    customPresetLimit = integer("pomodoro.custom-preset-limit", 16),
                    sounds = PomodoroSettings.PomodoroSounds(
                        uiClick = text("pomodoro.sounds.ui-click", "ui.button.click"),
                        uiSuccess = text("pomodoro.sounds.ui-success", "entity.experience_orb.pickup"),
                        uiFail = text("pomodoro.sounds.ui-fail", "entity.villager.no"),
                        timerEndWarning = text("pomodoro.sounds.timer-end-warning", "block.note_block.hat"),
                        nextCycle = text("pomodoro.sounds.next-cycle", "entity.player.levelup"),
                        leaveWarning = text("pomodoro.sounds.leave-warning", "entity.villager.no"),
                        completeAllRounds = text("pomodoro.sounds.complete-all-rounds", "entity.firework_rocket.launch"),
                        newRoundStart = text("pomodoro.sounds.new-round-start", "block.bell.use"),
                    ),
                    // The bundled defaults travel inside the pomodoro module's jar, not core's, so
                    // the lookup is by classpath root rather than by a data-folder file. A missing
                    // resource fails the reload loudly instead of silently shipping zero presets.
                    builtinPresets = PomodoroSettings.defaults(
                        requireNotNull(PomodoroSettings::class.java.classLoader.getResourceAsStream("pomodoro-default-presets.yml")) {
                            "pomodoro-default-presets.yml is missing from the classpath"
                        },
                    ),
                ),
                ShopSettings.withOverrides(orePrices()),
            )
        }
    }
}
