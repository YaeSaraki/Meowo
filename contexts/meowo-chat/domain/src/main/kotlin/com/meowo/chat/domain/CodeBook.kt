package com.meowo.chat.domain

import java.util.BitSet
import kotlin.random.Random

/**
 * Which `#k3f9` codes have been used.
 *
 * ## Why this is a bitmap and not a set of strings
 *
 * A code is four base-36 characters, so there are exactly 1 679 616 of them. That is small enough to
 * hold in a **bitmap**: one bit per code, 210 kB for the whole space, whether the server has said
 * ten thousand things or ten million.
 *
 * The alternative — a `Set<String>` of every code ever minted — grows with the chat log and costs
 * far more per entry than the one bit it needs. The bitmap is exact in both directions: it is as
 * small as the problem allows and it never lies about what is taken.
 *
 * ## Why the whole space has to be known
 *
 * The codes used to be checked against the **in-memory** log only — the last few thousand lines.
 * The chat log itself keeps weeks of history, so a fresh code could collide with a row that had
 * already been written, and a colliding code is not a cosmetic problem: the old row is either
 * overwritten or shadowed, and a reader following `#k3f9` lands on somebody else's message.
 *
 * So the book is loaded with the codes already in the log when the log opens, and every code minted
 * afterwards is reserved here. Codes are never released: a code that has been used stays used, so a
 * reader who bookmarked it never finds it pointing at a different conversation later.
 */
class CodeBook {

    private val used = BitSet(MessageCode.SPACE)

    /**
     * Reserves [code].
     *
     * @return false when the code was already taken, or is not a code at all.
     */
    @Synchronized
    fun reserve(code: String): Boolean {
        val index = MessageCode.indexOf(code)
        if (index < 0) return false
        if (used.get(index)) return false
        used.set(index)
        return true
    }

    /** A code that has not been used, and is now used. */
    @Synchronized
    fun mint(random: Random = Random.Default, attempts: Int = 64): String {
        repeat(attempts) {
            val index = random.nextInt(MessageCode.SPACE)
            if (!used.get(index)) {
                used.set(index)
                return MessageCode.codeOf(index)
            }
        }
        // The space is (almost) exhausted: 64 collisions in a row means a million codes are gone.
        // Handing back a duplicate beats throwing on a chat line, and the code that comes back is
        // still marked as used.
        val index = random.nextInt(MessageCode.SPACE)
        used.set(index)
        return MessageCode.codeOf(index)
    }

    /** Whether [code] has been used. */
    @Synchronized
    fun isUsed(code: String): Boolean {
        val index = MessageCode.indexOf(code)
        return index >= 0 && used.get(index)
    }

    /** How many codes have been used. */
    @Synchronized
    fun size(): Int = used.cardinality()
}
