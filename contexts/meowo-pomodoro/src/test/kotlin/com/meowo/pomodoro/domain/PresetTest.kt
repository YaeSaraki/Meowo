package com.meowo.pomodoro.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PresetTest {
    private fun preset(
        id: String = "standard",
        name: String = "Standard",
        work: Int = 25 * 60,
        brk: Int = 5 * 60,
        longBrk: Int = 15 * 60,
        sessions: Int = 4,
        builtin: Boolean = true,
    ) = Preset(id, name, "CLOCK", false, work, brk, longBrk, sessions, builtin)

    @Test fun `accepts builtin slug id and uuid id`() {
        assertNotNull(preset(id = "standard"))
        assertNotNull(preset(id = UUID.randomUUID().toString(), builtin = false))
    }

    @Test fun `rejects blank name`() {
        assertThrows(IllegalArgumentException::class.java) { preset(name = "") }
        assertThrows(IllegalArgumentException::class.java) { preset(name = " ") }
    }

    @Test fun `rejects name longer than 32 chars`() {
        assertThrows(IllegalArgumentException::class.java) { preset(name = "x".repeat(33)) }
    }

    @Test fun `rejects duration below floor`() {
        assertThrows(IllegalArgumentException::class.java) { preset(work = 30) }
        assertThrows(IllegalArgumentException::class.java) { preset(brk = 0) }
        assertThrows(IllegalArgumentException::class.java) { preset(longBrk = -1) }
    }

    @Test fun `rejects duration above ceiling`() {
        val ceiling = 3 * 60 * 60
        assertThrows(IllegalArgumentException::class.java) { preset(work = ceiling + 1) }
        assertThrows(IllegalArgumentException::class.java) { preset(brk = ceiling + 1) }
        assertThrows(IllegalArgumentException::class.java) { preset(longBrk = ceiling + 1) }
    }

    @Test fun `rejects zero or excessive sessions`() {
        assertThrows(IllegalArgumentException::class.java) { preset(sessions = 0) }
        assertThrows(IllegalArgumentException::class.java) { preset(sessions = 13) }
    }

    @Test fun `rejects blank icon`() {
        assertThrows(IllegalArgumentException::class.java) { Preset("standard", "Standard", "", false, 1500, 300, 900, 4, true) }
    }

    @Test fun `rejects unrecognised id format`() {
        assertThrows(IllegalArgumentException::class.java) { preset(id = "PRESET 1") }
    }

    @Test fun `asCustom flips builtin flag`() {
        val p = preset(builtin = true).asCustom()
        assertFalse(p.builtin)
    }

    @Test fun `validName accepts ordinary names and rejects what would break rendering`() {
        assertTrue(Preset.validName("Standard"))
        assertTrue(Preset.validName("深度工作"))
        assertTrue(Preset.validName("x".repeat(Preset.NAME_MAX)))
        assertFalse(Preset.validName(""))
        assertFalse(Preset.validName("   "))
        assertFalse(Preset.validName("x".repeat(Preset.NAME_MAX + 1)))
        // A section sign would turn into a colour escape in the GUI title and the BossBar label.
        assertFalse(Preset.validName("evil§cname"))
        assertFalse(Preset.validName("line\nbreak"))
    }

    @Test fun `validName agrees with the constructor's own limit`() {
        val atLimit = "y".repeat(Preset.NAME_MAX)
        assertTrue(Preset.validName(atLimit))
        assertNotNull(preset(name = atLimit))
        assertFalse(Preset.validName(atLimit + "y"))
        assertThrows(IllegalArgumentException::class.java) { preset(name = atLimit + "y") }
    }
}
