package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A refusal is a **reason**, and this is what keeps it that way.
 *
 * ## The shape of the failure this prevents
 *
 * A refusal used to be a Chinese sentence in the middle of the pure layer, and the sentence went
 * straight to a player: the prompt tells the model to repeat a refusal as it is, so it could not
 * be translated on the way out, and an English player read Chinese. Nothing failed — the build was
 * green and the plugin rendered. It is the kind of bug that only shows up on a server nobody
 * testing it speaks the language of.
 *
 * The fix is not a review checklist; it is the **shape** of the types. A refusal holds a reason and
 * a list of arguments, so there is no field a sentence could go into. This test pins that shape,
 * because a type that grows a `text` field again would let the whole thing back in.
 */
class RefusalShapeTest {

    /**
     * The reasons are a closed set.
     *
     * Not one type but five — the data, the tool, the call, the cat, the provider — because they
     * are different axes, and a caller that had to ask which family a reason belonged to would be
     * worse off than one that did not.
     */
    @Test fun `the reasons are five separate closed sets`() {
        val families = listOf(
            RedactionRefusal::class.java,
            ToolRefusal::class.java,
            CallRefusal::class.java,
            TurnedAway::class.java,
            Apology::class.java,
        )
        assertEquals(5, families.distinct().size, "the five families must be five types")
        for (family in families) {
            assertTrue(
                Refusal::class.java.isAssignableFrom(family),
                "${family.simpleName} is not a Refusal",
            )
        }
        assertEquals(18, allReasons().size, "the reason inventory changed; the renderer and the pack must follow")
        assertEquals(
            allReasons().size,
            allReasons().distinct().size,
            "two reasons are the same value",
        )
    }

    /**
     * No refusal holds a sentence.
     *
     * The fields are asserted by name because that is the point: a field called `text` or `message`
     * on a refusal is a hole the old Chinese sentence came back through.
     */
    @Test fun `no refusal holds a sentence`() {
        assertEquals(
            listOf("reason", "args"),
            CatTurn.Refuse::class.java.declaredFields.map { it.name },
            "a refusal carries a reason and its arguments — nothing a sentence could go into",
        )
        assertEquals(
            listOf("reason", "args"),
            ToolAnswer.Refused::class.java.declaredFields.map { it.name },
            "a tool's refusal carries a reason and its arguments",
        )
        assertEquals(
            listOf("reason"),
            Redaction.Verdict.Refused::class.java.declaredFields.map { it.name }.sorted(),
            "a disclosure verdict carries a reason",
        )
    }

    /**
     * No refusal is built from a string.
     *
     * The scan is precise rather than heuristic: it looks for a refusal **constructed with a
     * literal**, which is the only way a sentence could get back in. It needs no list of files to
     * exempt, because the prompt's own Chinese is not a refusal.
     */
    @Test fun `no refusal is built from a hard-coded sentence`() {
        val literal = Regex("\\bRefus(?:e|ed)\\s*\\(\\s*\"")
        val offenders = mutableListOf<String>()

        for (root in listOf("domain/src/main/kotlin", "application/src/main/kotlin")) {
            val dir = File(root)
            assertTrue(dir.isDirectory, "source root does not exist: ${dir.absolutePath}")
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        if (literal.containsMatchIn(line)) {
                            offenders += "${file.path}:${index + 1}  ${line.trim()}"
                        }
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "a refusal built from a literal sentence:\n" + offenders.joinToString("\n"),
        )
    }

    /**
     * The old refusal constants are gone.
     *
     * They were the sentences themselves, and they were the reason the wording could not be
     * changed by an operator or held in a language pack.
     */
    @Test fun `the refusal sentences are no longer constants in the code`() {
        val gone = Regex("""\b(PRIVATE_REFUSAL|OPTED_OUT_REFUSAL|NOT_ALLOWED_REFUSAL|APOLOGISE)\b""")
        val offenders = mutableListOf<String>()

        for (root in listOf("domain/src/main/kotlin", "application/src/main/kotlin")) {
            File(root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        if (gone.containsMatchIn(line)) {
                            offenders += "${file.path}:${index + 1}  ${line.trim()}"
                        }
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "a refusal sentence is still a constant in the code:\n" + offenders.joinToString("\n"),
        )
    }

    private fun allReasons(): List<Refusal> = listOf(
        RedactionRefusal.PRIVATE,
        RedactionRefusal.OPTED_OUT,
        RedactionRefusal.NOT_ALLOWED,
        ToolRefusal.PENDING,
        ToolRefusal.FAILED,
        ToolRefusal.BAD_TICKET,
        ToolRefusal.DAILY_IS_YOURS,
        ToolRefusal.UNKNOWN_PLAYER,
        CallRefusal.NO_CAPABILITY,
        CallRefusal.NOT_WIRED,
        CallRefusal.BAD_ARGUMENTS,
        CallRefusal.THREW,
        TurnedAway.NOT_A_CAT,
        TurnedAway.COOLDOWN,
        TurnedAway.QUOTA,
        TurnedAway.NOT_ALLOWED,
        TurnedAway.BUSY,
        Apology.PROVIDER_FAILED,
    )
}
