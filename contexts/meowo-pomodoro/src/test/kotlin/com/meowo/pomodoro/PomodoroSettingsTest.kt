package com.meowo.pomodoro

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PomodoroSettingsTest {

    private fun yaml(body: String) = body.trimIndent().byteInputStream()

    @Test fun `bundled defaults parse into the canonical two presets`() {
        val stream = requireNotNull(PomodoroSettings::class.java.classLoader.getResourceAsStream("pomodoro-default-presets.yml")) {
            "the module must ship pomodoro-default-presets.yml"
        }
        val presets = PomodoroSettings.defaults(stream)
        assertEquals(setOf("standard", "class"), presets.map { it.id }.toSet())

        val standard = presets.single { it.id == "standard" }
        assertEquals(25 * 60, standard.workSeconds)
        assertEquals(5 * 60, standard.breakSeconds)
        assertEquals(15 * 60, standard.longBreakSeconds)
        assertEquals(4, standard.sessions)
        assertEquals("CLOCK", standard.icon)

        val course = presets.single { it.id == "class" }
        assertEquals(40 * 60, course.workSeconds)
        assertEquals(2, course.sessions)
    }

    @Test fun `a fractional minute is rounded up to whole seconds`() {
        val presets = PomodoroSettings.defaults(
            yaml(
                """
                presets:
                  quick:
                    name: Quick
                    icon: CLOCK
                    work: 1.5
                    break: 1
                    long-break: 2
                    sessions: 1
                """,
            ),
        )
        // 1.5 minutes is 90 seconds. A fraction must never round *down* into a shorter round than the
        // operator asked for.
        assertEquals(90, presets.single().workSeconds)
        assertEquals(60, presets.single().breakSeconds)
        assertEquals(120, presets.single().longBreakSeconds)
    }

    @Test fun `a sub-minute duration is rejected instead of quietly becoming a shorter round`() {
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroSettings.defaults(
                yaml(
                    """
                    presets:
                      quick:
                        name: Quick
                        icon: CLOCK
                        work: 0.5
                        break: 1
                        long-break: 2
                        sessions: 1
                    """,
                ),
            )
        }
    }

    @Test fun `a missing or zero duration is rejected rather than defaulted`() {
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroSettings.defaults(
                yaml(
                    """
                    presets:
                      broken:
                        name: Broken
                        icon: CLOCK
                        work: 0
                        break: 5
                        long-break: 15
                        sessions: 4
                    """,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroSettings.defaults(
                yaml(
                    """
                    presets:
                      broken:
                        name: Broken
                        icon: CLOCK
                        break: 5
                        long-break: 15
                        sessions: 4
                    """,
                ),
            )
        }
    }

    @Test fun `a non-numeric duration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroSettings.defaults(
                yaml(
                    """
                    presets:
                      broken:
                        name: Broken
                        icon: CLOCK
                        work: soon
                        break: 5
                        long-break: 15
                        sessions: 4
                    """,
                ),
            )
        }
    }

    @Test fun `a file without a presets block is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroSettings.defaults(yaml("nothing: here"))
        }
    }

    @Test fun `bossbar colours accept the seven valid names and reject anything else`() {
        PomodoroSettings.BossbarColors()
        for (name in PomodoroSettings.BossbarColors.ALLOWED) {
            PomodoroSettings.BossbarColors(work = name)
        }
        assertThrows(IllegalArgumentException::class.java) { PomodoroSettings.BossbarColors(work = "CHARTREUSE") }
    }

    @Test fun `settings reject out-of-range tunables`() {
        assertThrows(IllegalArgumentException::class.java) { PomodoroSettings(maxDistance = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { PomodoroSettings(maxDistance = 65.0) }
        assertThrows(IllegalArgumentException::class.java) { PomodoroSettings(customPresetLimit = 0) }
        assertThrows(IllegalArgumentException::class.java) { PomodoroSettings(textDisplayOffsetY = 9.0) }
    }

    @Test fun `an unknown builtin id resolves to nothing rather than throwing`() {
        val settings = PomodoroSettings()
        assertNull(settings.builtin("standard"))
    }
}
