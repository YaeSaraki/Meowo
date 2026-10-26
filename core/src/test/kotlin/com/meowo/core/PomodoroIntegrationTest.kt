package com.meowo.core

import com.meowo.platform.CommandRouter
import com.meowo.platform.MenuChrome
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.pomodoro.PomodoroContext
import com.meowo.pomodoro.PomodoroSettings
import com.meowo.pomodoro.domain.SessionState
import com.meowo.shared.event.EventBus
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.Locale

/**
 * End-to-end wiring for the pomodoro context under a mocked server: the context boots against a real
 * SQLite file, registers both command routes, ticks, and drives a session through its whole cycle.
 *
 * This is the layer that catches composition mistakes a unit test cannot see — a wrong argument order
 * in a settings constructor, a route registered under a name `plugin.yml` does not declare, a listener
 * that throws on the first tick. Each of those compiles, and each of those is a dead feature in
 * production.
 */
class PomodoroIntegrationTest {

    /**
     * Slot constants, named so a failure says *which function* moved rather than which integer did.
     * They mirror the colonnade: seven heads on the first content row, extras on the lower rail.
     */
    private companion object {
        const val STATUS_CORE = 4
        const val START_HEAD = 10
        const val PAUSE_HEAD = 11
        const val NEXT_HEAD = 12
        const val STOP_HEAD = 13
        const val GALLERY_HEAD = 14
        const val CREATE_HEAD = 15
        const val STATS_HEAD = 16

        /** The editor's field pillars, in the order they appear. */
        const val NAME_HEAD = 10
        const val WORK_HEAD = 11
        const val BREAK_HEAD = 12
        const val LONG_BREAK_HEAD = 13
        const val SESSIONS_HEAD = 14
        const val ICON_HEAD = 15
        const val RESET_HEAD = 16

        /** The editor's action crystals. */
        const val SAVE_CRYSTAL = 37
        const val DUPLICATE_CRYSTAL = 39
        const val DELETE_CRYSTAL = 41
        const val BACK_CRYSTAL = 50

        /** The gallery's own controls. */
        const val GALLERY_CREATE = 49
        const val GALLERY_BACK = 50
        const val GALLERY_NEXT = 52

        /** Every page carries a close on the rail; pages with a parent also carry a back. */
        const val CLOSE_CRYSTAL = 52

        /** The confirmation page's two pillars. */
        const val CONFIRM_NO = 11
        const val CONFIRM_YES = 15

        /** First two cells of the content grid: the built-in preset, then the first custom one. */
        const val GALLERY_FIRST = 10
        const val GALLERY_SECOND = 11
    }

    open class TestPlugin : org.bukkit.plugin.java.JavaPlugin()


    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var host: PlatformHost
    private lateinit var context: PomodoroContext
    private lateinit var screens: FakeUI
    private lateinit var player: PlayerMock

    /** Everything the server has said to the player. `nextMessage()` drains, so it is buffered. */
    private val said = mutableListOf<String>()

    /**
     * One-minute rounds, so a whole cycle can be driven by ticking the scheduler. Sixty seconds is the
     * floor the domain enforces, which makes this the shortest legal preset rather than a magic number.
     */
    private val settings = PomodoroSettings(
        maxDistance = 8.0,
        builtinPresets = listOf(
            PomodoroSettings.BuiltinPreset("standard", "Standard", "CLOCK", false, 60, 60, 120, 2),
        ),
    )

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.loadWith(TestPlugin::class.java, java.io.File("build/resources/main/plugin.yml"))
        plugin.description.permissions.forEach { server.pluginManager.addPermission(it) }
        val messages = Messages(plugin).apply { load("zh_CN") }
        host = PlatformHost(plugin, EventBus { _, error -> throw error }, CommandRouter(messages), messages)
        player = server.addPlayer("Timer").apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        player.addAttachment(plugin, "meowo.pomodoro", true)
        screens = FakeUI(messages)
        // A cell names its command the way a player would type it (`pomodoro presets`); the router path is
        // what the test harness already uses.
        screens.dispatch = { _, line -> command(*line.removePrefix("pomodoro ").split(" ").filter { it.isNotEmpty() }.toTypedArray()) }
        context = PomodoroContext(host, settings, ui = { screens })
        context.start()
    }

    @AfterEach
    fun close() {
        try {
            if (::context.isInitialized) context.close()
        } finally {
            MockBukkit.unmock()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private fun command(vararg args: String) {
        host.commands.onCommand(player, plugin.getCommand("meowo")!!, "meowo", arrayOf("pomodoro", *args))
    }

    /** One second of the context's ticker (which runs every 20 server ticks). */
    private fun turn() {
        server.scheduler.performTicks(20)
        drainMessages()
    }

    private fun drainMessages() {
        while (true) {
            val line = player.nextMessage() ?: break
            said += line
        }
    }

    private fun pump(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue(
                System.nanoTime() < deadline,
                "pomodoro operation timed out; page=${screens.page?.title}; said=$said",
            )
            server.scheduler.performTicks(1)
            drainMessages()
            Thread.sleep(2)
        }
    }

    private fun mainMenu() {
        screens.page = null
        command("gui")
        pump { screens.page != null }
    }

    private fun click(slot: Int, right: Boolean = false, shift: Boolean = false) {
        // The double dispatches the click the way the renderer does, so this exercises the same path a
        // player's click takes: a gesture picks the action, and the action is a command or a built-in.
        screens.click(slot, right, shift)
        server.scheduler.performTicks(1)
        drainMessages()
    }

    /** Opens the colonnade and walks into the gallery, which is where presets are chosen. */
    private fun openGallery() {
        mainMenu()
        click(GALLERY_HEAD)
        pump { screens.page?.title?.startsWith("我的预设") == true }
    }

    /**
     * Whether a gallery cell holds a preset rather than an empty socket.
     *
     * Every cell of the content grid is filled once the page is drawn — empty ones with a grey socket —
     * so `buttons.containsKey(slot)` says nothing about whether a preset is there.
     */
    private fun hasPresetAt(slot: Int): Boolean =
        screens.page!!.buttons[slot]?.item?.type?.let { it != Material.GRAY_STAINED_GLASS_PANE } ?: false

    private fun session() = context.sessionOf(player.uniqueId)

    private fun state() = session()?.state

    private fun sawText(fragment: String) = said.any { it.contains(fragment) }

    // ── routing and assembly ─────────────────────────────────────────────────────────────────

    @Test fun `the context registers both routes under the declared permission`() {
        assertTrue(host.commands.hasRoute("pomodoro"), "the /pomodoro route must exist")
        assertTrue(host.commands.hasRoute("pomo"), "the /pomo route must exist")
        assertEquals("meowo.pomodoro", host.commands.permissionOf("pomodoro"))
        assertEquals("pomodoro", host.commands.ownerOf("pomodoro"))
    }

    @Test fun `the colonnade opens with seven function heads and a status core`() {
        mainMenu()
        val page = screens.page!!
        assertEquals("番茄钟", page.title)
        assertEquals(6, page.rows)
        // Seven pillars, one function each, on the first content row.
        (10..16).forEach { assertTrue(page.buttons.containsKey(it), "pillar head at $it must exist") }
        assertTrue(page.buttons.containsKey(4), "the top-rail core carries the session readout")
        assertTrue(page.buttons.containsKey(53), "the close crystal belongs on the bottom rail")
    }

    @Test fun `every function pillar stands on a shaft and a foot`() {
        mainMenu()
        val page = screens.page!!
        // The head is the button; the two cells beneath it are the pillar it stands on, so the shape
        // reads as a colonnade rather than a loose row of icons.
        for (head in 10..16) {
            assertTrue(page.buttons.containsKey(head + 9), "shaft under the head at $head")
            assertTrue(page.buttons.containsKey(head + 18), "foot under the head at $head")
        }
    }

    @Test fun `the colonnade needs no database read to appear`() {
        // A pillar screen that waited for SQLite would flash or look broken; this one must be able to
        // open with the page already complete on the first call.
        screens.page = null
        command("gui")
        assertNotNull(screens.page, "the colonnade must open synchronously")
    }

    /**
     * Every page must address only slots its own inventory has.
     *
     * A slot past the last row is not a cosmetic slip: it is handed to the menu adapter as a position
     * that does not exist. The three-row confirmation was doing exactly that — its pillars were drawn
     * at a fixed height, so a "foot" landed on slot 29 of a 27-slot chest.
     */
    @Test fun `every page stays inside its own inventory`() {
        val pages = mutableListOf<FakeUI.Page>()

        mainMenu()
        pages += screens.page!!

        command("presets")
        pump { screens.page?.title?.startsWith("我的预设") == true }
        pages += screens.page!!

        openCreator()
        pages += screens.page!!

        // The three-row confirmation, reached the way a player reaches it.
        createPreset()
        click(GALLERY_SECOND, shift = true)
        pump { screens.page?.title == "确认删除" }
        pages += screens.page!!

        pages.forEach { page ->
            val size = page.rows * 9
            val outside = page.buttons.keys.filter { it !in 0 until size }
            assertTrue(outside.isEmpty(), "\"${page.title}\" addresses slots outside its $size slots: $outside")
        }
        assertTrue(pages.size >= 4, "the dump must cover every page shape")
    }

    // ── starting ─────────────────────────────────────────────────────────────────────────────

    @Test fun `the gallery lists the built-in preset and clicking it starts a session`() {
        openGallery()
        click(10)
        pump { state() == SessionState.WORK }
        assertEquals("standard", session()!!.preset.id)
        assertEquals(1, session()!!.workSession)
        assertEquals(60, session()!!.secondsLeft)
        assertTrue(sawText("Standard"), "the start line should name the preset; said=$said")
    }

    @Test fun `the start pillar opens the gallery when nothing is running`() {
        mainMenu()
        click(START_HEAD)
        pump { screens.page?.title?.startsWith("我的预设") == true }
        assertNull(session())
    }

    @Test fun `the start pillar resumes a paused session instead of opening the gallery`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        command("pause")
        assertEquals(SessionState.PAUSED, state())
        mainMenu()
        click(START_HEAD)
        server.scheduler.performTicks(1)
        drainMessages()
        assertEquals(SessionState.WORK, state(), "a paused timer resumes from the colonnade")
    }

    @Test fun `start by name resolves a built-in preset`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        assertEquals("Standard", session()!!.preset.name)
    }

    @Test fun `start by an unknown name is refused and creates nothing`() {
        command("start", "does-not-exist")
        pump { said.isNotEmpty() }
        assertNull(session())
        assertTrue(sawText("does-not-exist"), "the refusal should name what was asked for")
    }

    @Test fun `starting with no preset name opens the colonnade instead`() {
        command("start")
        pump { screens.page != null }
        assertNull(session())
        assertEquals("番茄钟", screens.page!!.title)
    }

    @Test fun `the presets verb goes straight to the gallery`() {
        command("presets")
        pump { screens.page?.title?.startsWith("我的预设") == true }
    }

    // ── control ──────────────────────────────────────────────────────────────────────────────

    @Test fun `pause then resume keeps the remaining seconds`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        turn()
        turn()
        command("pause")
        assertEquals(SessionState.PAUSED, state())
        val frozen = session()!!.secondsLeft
        turn()
        assertEquals(frozen, session()!!.secondsLeft, "a paused clock must not advance")
        command("resume")
        assertEquals(SessionState.WORK, state())
        assertEquals(frozen, session()!!.secondsLeft)
    }

    @Test fun `pause is refused during the waiting phase`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        repeat(60) { turn() }
        assertEquals(SessionState.WORK_COMPLETED, state())
        command("pause")
        pump { sawText("不能暂停") }
        assertEquals(SessionState.WORK_COMPLETED, state())
    }

    @Test fun `next is refused outside the waiting phase`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        command("next")
        pump { sawText("工作完成") }
        assertEquals(SessionState.WORK, state())
    }

    @Test fun `stop ends the session and a second stop reports nothing to stop`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        command("stop")
        assertEquals(SessionState.STOPPED, state())
        command("stop")
        pump { sawText("没有进行中的番茄钟") }
    }

    @Test fun `the full cycle runs work to break to long break and back to work`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }

        repeat(60) { turn() }                    // round 1 work ends
        assertEquals(SessionState.WORK_COMPLETED, state())
        command("next")
        assertEquals(SessionState.BREAK, state())
        assertEquals(2, session()!!.workSession)

        repeat(60) { turn() }                    // break ends, round 2 begins
        assertEquals(SessionState.WORK, state())
        assertEquals(2, session()!!.workSession)

        repeat(60) { turn() }                    // round 2 work ends
        assertEquals(SessionState.WORK_COMPLETED, state())
        command("next")
        assertEquals(SessionState.LONG_BREAK, state())
        assertEquals(1, session()!!.workSession, "a long break resets the round counter")

        repeat(120) { turn() }                   // long break ends, a new cycle begins
        assertEquals(SessionState.WORK, state())
    }

    // ── the drift guard ──────────────────────────────────────────────────────────────────────

    @Test fun `drifting past max-distance auto-pauses and never drags the player back`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        player.teleport(Location(player.world, 400.0, 64.0, 400.0))
        // The per-second guard is the safety net; it must catch a player whose position changed
        // without a move event ever reaching the handler.
        turn()
        assertEquals(SessionState.PAUSED, state(), "straying must pause the timer")
        assertTrue(sawText("离开"), "and say why; said=$said")
        assertTrue(player.location.x > 300.0, "the plugin must not move the player")
    }

    @Test fun `a plugin teleport out of range pauses before the player has even moved`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        val from = player.location
        // Fired while the player is still standing inside the radius, carrying a destination far
        // outside it. Evaluating `event.to` is what makes this pause; reading `player.location` would
        // see the origin and let the teleport through unnoticed.
        server.pluginManager.callEvent(
            org.bukkit.event.player.PlayerTeleportEvent(
                player, from, Location(player.world, 500.0, 64.0, 500.0),
            ),
        )
        drainMessages()
        assertEquals(SessionState.PAUSED, state())
        assertTrue(sawText("离开"), "said=$said")
    }

    @Test fun `staying inside max-distance keeps the timer running`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        // Offset from the *actual* start point rather than from an assumed origin: the radius is a
        // 3D sphere (as upstream measures it), so teleporting to an absolute coordinate would also be
        // testing whatever vertical distance the mock's default spawn happens to add.
        val origin = player.location.clone()
        player.teleport(origin.clone().add(1.0, 0.0, 1.0))
        turn()
        assertEquals(SessionState.WORK, state())
    }

    // ── readouts ─────────────────────────────────────────────────────────────────────────────

    @Test fun `visuals toggles a readout, and an unknown knob prints the usage line`() {
        command("visuals", "bossbar", "off")
        pump { sawText("关闭进度条") }
        command("visuals", "bossbar", "on")
        pump { sawText("开启进度条") }
        command("visuals", "hat", "on")
        pump { sawText("/pomodoro visuals") }
    }

    @Test fun `visuals without a state is a usage error rather than a silent disable`() {
        command("visuals", "bossbar")
        pump { sawText("/pomodoro visuals") }
    }

    // ── the readout toggles must not go stale ────────────────────────────────────────────────

    /**
     * The bug this pins: the crystal said "progress bar: on" after being clicked off, because the
     * preference changed and the menu did not. A toggle that only takes effect on reopen looks broken.
     */
    @Test fun `toggling a readout repaints the open menu instead of reopening it`() {
        mainMenu()
        val before = screens.opens
        val refreshesBefore = screens.refreshes
        click(37)   // the progress-bar crystal on the colonnade
        pump { sawText("关闭进度条") }

        assertEquals(refreshesBefore + 1, screens.refreshes, "the menu must be repainted in place")
        assertEquals(before, screens.opens, "and must not be closed and reopened to do it")
        // The repainted page is the colonnade, and it now shows the other state.
        assertEquals("番茄钟", screens.page!!.title)
        assertTrue(
            textOf(37).contains("进度条：关"),
            "the crystal must show the state it switched to, was: ${textOf(37)}",
        )
    }

    @Test fun `toggling the title crystal repaints it too`() {
        mainMenu()
        click(38)   // the title-readout crystal
        pump { sawText("关闭屏幕标题") }
        assertTrue(textOf(38).contains("屏幕标题：关"), "was: ${textOf(38)}")
    }

    @Test fun `a phase change repaints the colonnade that is showing it`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        mainMenu()
        val refreshesBefore = screens.refreshes
        // Drain the work phase; the transition has to reach the page the player is looking at.
        repeat(60) { turn() }
        assertTrue(screens.refreshes > refreshesBefore, "a boundary crossing must repaint the menu")
    }

    // The title cadence and its fade times are pinned in the pomodoro module instead:
    // `TitleThrottleTest` for "when is it re-sent" and `PomodoroVisualsTest` for "with what stay time".
    // A mocked player records only the title text, so a cadence assertion here would be measuring the
    // mock's bookkeeping rather than the readout a client receives.

    // ── navigation must be findable, and never the scenery ───────────────────────────────────

    /**
     * The bottom row is the original glass rail, and **every cell on it leads back**.
     *
     * This replaced a row of separate door/barrier ornaments. A player looking for the way out saw a row
     * of glass panes with no tooltips and could not tell which were clickable — so the row is now one
     * wide affordance, and it says so on every cell.
     */
    @Test fun `every page's bottom row is a clickable glass rail that leads back`() {
        val pages = mutableListOf<FakeUI.Page>()
        mainMenu(); pages += screens.page!!
        command("presets"); pump { screens.page?.title?.startsWith("我的预设") == true }; pages += screens.page!!
        openCreator(); pages += screens.page!!
        createPreset(); click(GALLERY_SECOND, shift = true); pump { screens.page?.title == "确认删除" }
        pages += screens.page!!

        pages.forEach { page ->
            (45..53).forEach { slot ->
                val cell = page.buttons[slot]
                assertNotNull(cell, "\"${page.title}\" leaves slot $slot on the rail empty")
                assertNotNull(cell!!.item.itemMeta?.displayName(), "rail cell $slot has no name")
                assertTrue(loreOf(cell.item).isNotEmpty(), "rail cell $slot has no lore explaining the click")
            }
        }

        // The row has two parts and they lead to two different places: the corners are the bedrock floor and
        // go to the main menu, the glass between them is this screen's own way back. Both are named.
        mainMenu()
        com.meowo.platform.MenuChrome.PLINTH_SLOTS.forEach { slot ->
            assertEquals(
                Material.BEDROCK, screens.page!!.buttons.getValue(slot).item.type,
                "the bottom corners are the bedrock floor, slot $slot is not",
            )
        }
        com.meowo.platform.MenuChrome.RAIL_GLASS_SLOTS.forEach { slot ->
            assertEquals(
                Material.PURPLE_STAINED_GLASS_PANE,
                screens.page!!.buttons.getValue(slot).item.type,
                "the rail between the corners is the chrome glass, slot $slot differs",
            )
        }
    }

    @Test fun `the bedrock floor leads to the main menu on every screen`() {
        val pages = mutableListOf<FakeUI.Page>()
        mainMenu(); pages += screens.page!!
        command("presets"); pump { screens.page?.title?.startsWith("我的预设") == true }; pages += screens.page!!
        openCreator(); pages += screens.page!!

        pages.forEach { page ->
            com.meowo.platform.MenuChrome.PLINTH_SLOTS.forEach { slot ->
                val cell = page.buttons.getValue(slot)
                assertEquals(Material.BEDROCK, cell.item.type, "\"${page.title}\" slot $slot is not the floor")
                assertNotNull(cell.item.itemMeta?.displayName(), "the floor must say where it goes")
                assertTrue(loreOf(cell.item).isNotEmpty())
            }
        }
    }

    @Test fun `clicking the rail glass navigates back`() {
        // Gallery -> colonnade, by clicking the glass rather than aiming at one ornament.
        command("presets")
        pump { screens.page?.title?.startsWith("我的预设") == true }
        val before = screens.page!!.title
        click(GALLERY_BACK)
        pump { screens.page?.title == "番茄钟" }
        assertNotEquals(before, screens.page!!.title)

        // Editor -> gallery, the same way.
        openCreator()
        click(48)
        pump { screens.page?.title?.startsWith("我的预设") == true }
    }

    @Test fun `the root screen's glass goes back to the main menu`() {
        mainMenu()
        // Glass means "back" on every screen; on the root the way back is the plugin's main menu. It used to
        // close the window, which made the same material mean two different things depending on the page.
        assertEquals(Material.PURPLE_STAINED_GLASS_PANE, screens.page!!.buttons.getValue(46)!!.item.type)
        assertTrue(textOf(46).contains("返回"), "expected the way back, was: ${textOf(46)}")
    }

    @Test fun `the gallery shows the page position at the bottom right`() {
        command("presets")
        pump { screens.page?.title?.startsWith("我的预设") == true }
        // With a single page there is nothing to turn to, so the rightmost glass cell carries the page
        // count: the paging spot is never empty and never unexplained.
        assertTrue(textOf(GALLERY_NEXT).contains("1"), "expected the page number, was: ${textOf(GALLERY_NEXT)}")
        assertTrue(loreOf(screens.page!!.buttons.getValue(GALLERY_NEXT)!!.item).isNotEmpty())
    }

    @Test fun `tooltips do not leak raw colour codes`() {
        mainMenu()
        // The bundle writes these lines with `§` codes. Building an item name with `Component.text`
        // printed them literally, so a lore line read "§7回到上一层" on screen.
        val lore = loreOf(screens.page!!.buttons.getValue(45)!!.item)
        assertTrue(lore.isNotEmpty())
        lore.forEach { line ->
            assertFalse(line.contains('§'), "a raw colour code reached the tooltip: $line")
        }
        (10..16).forEach { slot ->
            loreOf(screens.page!!.buttons.getValue(slot).item).forEach { line ->
                assertFalse(line.contains('§'), "a raw colour code reached the tooltip: $line")
            }
        }
    }

    @Test fun `no page paints scenery over a function`() {
        val scenery = setOf(Material.GRAY_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE, Material.AMETHYST_SHARD)

        mainMenu()
        // The colonnade's seven functions live on the first content row.
        (10..16).forEach { slot ->
            assertFalse(
                screens.page!!.buttons.getValue(slot).item.type in scenery,
                "the function pillar at $slot was painted over",
            )
        }

        command("presets")
        pump { screens.page?.title?.startsWith("我的预设") == true }
        assertFalse(
            screens.page!!.buttons.getValue(GALLERY_FIRST).item.type in scenery,
            "the first preset was painted over",
        )

        openCreator()
        (10..16).forEach { slot ->
            assertFalse(
                screens.page!!.buttons.getValue(slot).item.type in scenery,
                "the editor field pillar at $slot was painted over",
            )
        }

        createPreset()
        click(GALLERY_SECOND, shift = true)
        pump { screens.page?.title == "确认删除" }
        listOf(CONFIRM_NO, CONFIRM_YES).forEach { slot ->
            assertFalse(
                screens.page!!.buttons.getValue(slot).item.type in scenery,
                "the confirmation's pillar at $slot was painted over, leaving the question unanswerable",
            )
        }
    }

    @Test fun `both rails are the same glass`() {
        mainMenu()
        // Reverted from a stone plinth on request: the glass rail reads better, and it does not need to
        // look inert because the page dresses it as the way back.
        assertEquals(Material.PURPLE_STAINED_GLASS_PANE, MenuChrome.frame(6)[46]!!.type)
        assertEquals(MenuChrome.frame(6)[1]!!.type, MenuChrome.frame(6)[46]!!.type)
    }

    @Test fun `an unused page slot is a silent socket rather than a broken button`() {
        command("presets")
        pump { screens.page?.title?.startsWith("我的预设") == true }
        // Slot 12 is inside the grid with only two presets listed; it renders as a socket.
        val socket = screens.page!!.buttons[GALLERY_FIRST + 2]
        assertNotNull(socket, "the grid must be dressed, not holed")
        assertTrue(loreOf(socket!!.item).isEmpty(), "a socket must not pretend to be a button")
    }

    private fun textOf(slot: Int): String =
        plain(screens.page!!.buttons.getValue(slot).item.itemMeta?.displayName())

    private fun loreOf(item: org.bukkit.inventory.ItemStack): List<String> =
        item.itemMeta?.lore()?.map { plain(it) }.orEmpty()

    private fun plain(component: net.kyori.adventure.text.Component?): String =
        component?.let {
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it)
        }.orEmpty()

    @Test fun `stats reports the accumulated focus time`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        command("stats")
        // Waiting on the reply itself, not on "some message arrived": the start line is already in
        // the buffer, and asserting off it would pass before the query ever came back.
        pump { sawText("专注") }
    }

    @Test fun `an unknown verb prints the usage line`() {
        command("wibble")
        pump { sawText("/pomodoro") }
    }

    // ── the preset editor ────────────────────────────────────────────────────────────────────

    /** Opens the creator from the colonnade and waits for the editor page. */
    private fun openCreator() {
        mainMenu()
        click(CREATE_HEAD)
        pump { screens.page?.title == "新建预设" }
    }

    /** Creates a custom preset through the editor and returns to the gallery. */
    private fun createPreset(workMinutes: Int = 1) {
        openCreator()
        repeat(workMinutes) { click(WORK_HEAD) }
        click(SAVE_CRYSTAL)
        pump { screens.page?.title?.startsWith("我的预设") == true }
    }

    @Test fun `a custom preset is created in the editor and can then be started`() {
        openCreator()
        click(WORK_HEAD)       // work duration +1 minute (the seed is 60 seconds)
        click(SESSIONS_HEAD)   // sessions +1 (the seed is 2)
        click(SAVE_CRYSTAL)

        pump { screens.page?.title?.startsWith("我的预设") == true }
        // Built-in first, then the custom one the editor just wrote.
        click(GALLERY_SECOND)
        pump { state() == SessionState.WORK }
        assertEquals(120, session()!!.preset.workSeconds, "the edited work duration must be used")
        assertEquals(3, session()!!.preset.sessions, "and the edited round count")
        assertFalse(session()!!.preset.builtin, "a custom preset must not claim to be built-in")
    }

    @Test fun `an edited duration is clamped at the one-minute floor`() {
        openCreator()
        repeat(5) { click(WORK_HEAD, right = true) }   // already at 60s, the domain floor
        click(SAVE_CRYSTAL)
        pump { screens.page?.title?.startsWith("我的预设") == true }
        click(GALLERY_SECOND)
        pump { state() == SessionState.WORK }
        assertEquals(60, session()!!.preset.workSeconds, "the domain floor must hold")
    }

    @Test fun `discarding a new preset leaves nothing stored`() {
        openCreator()
        click(BACK_CRYSTAL)
        pump { screens.page?.title?.startsWith("我的预设") == true }
        // Only the built-in remains in the gallery.
        assertFalse(hasPresetAt(GALLERY_SECOND), "a discarded draft must not be stored")
    }

    @Test fun `editing a stored preset changes the stored row rather than adding one`() {
        createPreset(workMinutes = 1)                    // 60s + 1 minute = 120s
        click(GALLERY_SECOND, right = true)              // right-click opens the editor
        pump { screens.page?.title == "编辑预设" }
        click(WORK_HEAD, right = true)                   // 120s back to 60s
        click(SAVE_CRYSTAL)
        pump { screens.page?.title?.startsWith("我的预设") == true }
        assertFalse(hasPresetAt(GALLERY_SECOND + 1), "editing must not add a preset")

        click(GALLERY_SECOND)
        pump { state() == SessionState.WORK }
        assertEquals(60, session()!!.preset.workSeconds, "the stored preset must have been updated")
    }

    @Test fun `the editor resets the numbers without touching the name`() {
        createPreset(workMinutes = 6)                    // 60s + 6 minutes = 420s
        click(GALLERY_SECOND, right = true)
        pump { screens.page?.title == "编辑预设" }
        click(RESET_HEAD)
        pump { sawText("恢复") }
        click(SAVE_CRYSTAL)
        pump { screens.page?.title?.startsWith("我的预设") == true }
        click(GALLERY_SECOND)
        pump { state() == SessionState.WORK }
        assertEquals(60, session()!!.preset.workSeconds, "reset returns to the built-in default")
    }

    // ── copying and deleting ─────────────────────────────────────────────────────────────────

    @Test fun `right-clicking a built-in preset copies it instead of editing it`() {
        openGallery()
        click(GALLERY_FIRST, right = true)   // built-ins cannot be edited, so this copies
        pump { screens.page?.title == "新建预设" }
        // The copy is a draft until saved, and it is a custom preset, not the built-in.
        click(SAVE_CRYSTAL)
        pump { screens.page?.title?.startsWith("我的预设") == true }
        click(GALLERY_SECOND)
        pump { state() == SessionState.WORK }
        assertFalse(session()!!.preset.builtin, "a copy of a built-in must be an editable custom preset")
        assertEquals(60, session()!!.preset.workSeconds)
    }

    @Test fun `the editor can save a duplicate that does not collide with the original name`() {
        createPreset()
        val original = screens.page!!.buttons.keys.first { it == GALLERY_SECOND }
        assertNotNull(original)
        click(GALLERY_SECOND, right = true)
        pump { screens.page?.title == "编辑预设" }
        click(DUPLICATE_CRYSTAL)
        pump { sawText("已存为") }
        // Two custom presets now, under different labels.
        pump { screens.page?.title == "编辑预设" }
        click(BACK_CRYSTAL)
        pump { screens.page?.title?.startsWith("我的预设") == true }
        assertTrue(hasPresetAt(GALLERY_SECOND), "the original stays")
        assertTrue(hasPresetAt(GALLERY_SECOND + 1), "and the copy is added")
    }

    @Test fun `deleting a custom preset asks first and then removes it`() {
        createPreset()
        click(GALLERY_SECOND, shift = true)
        pump { screens.page?.title == "确认删除" }
        // Backing out leaves the preset alone.
        click(CONFIRM_NO)
        pump { screens.page?.title?.startsWith("我的预设") == true }
        assertTrue(hasPresetAt(GALLERY_SECOND), "cancelling must not delete")

        click(GALLERY_SECOND, shift = true)
        pump { screens.page?.title == "确认删除" }
        click(CONFIRM_YES)
        pump { sawText("已删除") }
        pump { screens.page?.title?.startsWith("我的预设") == true }
        assertFalse(hasPresetAt(GALLERY_SECOND), "confirming must delete it")
    }

    @Test fun `a built-in preset cannot be deleted even by shift-clicking it`() {
        openGallery()
        click(GALLERY_FIRST, shift = true)
        // No confirmation page, no deletion: the built-in is still there.
        server.scheduler.performTicks(1)
        drainMessages()
        assertTrue(
            hasPresetAt(GALLERY_FIRST),
            "a built-in must survive a delete gesture; said=$said",
        )
        assertTrue(said.isEmpty() || !sawText("已删除"), "and must not claim to have deleted it")
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────

    @Test fun `re-enabling the context starts from a clean slate`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        context.close()
        context = PomodoroContext(host, settings, ui = { screens })
        context.start()
        assertNull(session(), "a stale timer must not resume under a new context")
        // And the routes are back, so the feature works again rather than half-dying on reload.
        assertTrue(host.commands.hasRoute("pomodoro"))
    }

    @Test fun `a player who quits is forgotten`() {
        command("start", "standard")
        pump { state() == SessionState.WORK }
        player.disconnect()
        // The quit handler stops the session so a returning player starts fresh.
        assertTrue(session() == null || session()!!.state == SessionState.STOPPED)
    }
}
