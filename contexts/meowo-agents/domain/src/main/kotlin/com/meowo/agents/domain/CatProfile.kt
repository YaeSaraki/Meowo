package com.meowo.agents.domain

/**
 * One cat's fixed identity: who it is, how it speaks, what it is allowed to look up, and where it
 * hands off when it cannot answer.
 *
 * Immutable on purpose. A cat's personality is configuration, not runtime state — if it could be
 * mutated by a tool call, a prompt injection could rewrite the rules it is supposed to follow.
 *
 * @param tools names of the read-only tools this cat may call. **An empty set means the cat knows
 *   nothing and must delegate** rather than improvise.
 * @param delegatesTo cats this one may hand off to, in preference order.
 * @param mbti a four-letter personality code, used as a **behavioural shorthand** rather than as a
 *   claim about anybody.
 *
 *   The pop-psych label is not the point; the *sharpness* is. A paragraph of adjectives gave the
 *   model twelve interchangeable cats, and "你是严谨的" is not something a model can act on.
 *   A code is: it names a direction in one token, and it can be checked — sixteen codes, twelve
 *   cats, so **every cat gets a different one**, and a test can prove it.
 * @param personality what [mbti] means **for this cat**, in behaviour rather than in theory.
 *
 *   The code alone would be a stereotype handed to a model; the gloss is what turns it into a
 *   character. "ESTP" means nothing on its own; "催人、盯当下、嘴上不饶人，但真到要扛事的时候
 *   他第一个上" is a cat you can hear.
 * @param scope what this cat is on shift for: the topics it owns, and — in the same breath — the
 *   topics it hands to somebody else.
 *
 *   A boundary phrased as an omission gets read as "not forbidden". "余额的事归别人" therefore
 *   has to be said, and said as a positive claim about what *is* this cat's business, or the model
 *   will happily widen the lane by itself.
 *
 *   The scope is **conversation** scope, not tool scope. A cat with no tools still owns a topic it
 *   talks about; [tools] is what decides whether it can put data behind it. The prompt says so
 *   explicitly, because "这是你的地盘" next to "你没有任何工具" reads as a contradiction otherwise.
 *
 *   It stays inside the server and the Minecraft world. The world's edge is drawn **once**, for
 *   every cat, in [PromptBuilder.WORLD_BOUNDARY] — a per-cat scope that reached past it would
 *   make that edge negotiable, one cat at a time.
 * @param sample a worked exchange, `玩家：…` then the cat's answer. Zero-shot character work is
 *   unreliable, and one exchange does more for a voice than another paragraph of adjectives — but it
 *   is an *example*, so it must never read as fact: it carries no number, no name and no place.
 * @param quirk one concrete habit, not an adjective. "严谨" changes nothing; "回答里总会引用对方
 *   上次说过的话" is something a model can actually perform.
 * @param deflect what this cat says when it cannot answer. Without it, a cat that does not know
 *   something reaches for the policy voice — and that is exactly what makes an NPC sound dead.
 */
data class CatProfile(
    val id: String,
    val speakerId: String,
    val displayName: String,
    val persona: String,
    val tone: String,
    /** The behavioural code. Validated against [MBTI_CODES] — a typo here is a silent personality swap. */
    val mbti: String,
    /** What the code means for this cat, in behaviour rather than in theory. */
    val personality: String,
    /**
     * Required on purpose: a cat with no chat range has no lane, and a default would let one ship
     * with an empty `## 你管什么` section — a heading with nothing under it, which is the failure
     * mode [PromptBuilder] already has a test for.
     */
    val scope: String,
    val tools: Set<String> = emptySet(),
    val delegatesTo: Set<String> = emptySet(),
    val sample: String = "",
    val quirk: String = "",
    val deflect: String = "",
) {
    init {
        require(scope.isNotBlank()) { "a cat without a scope has no chat range: $id" }
        require(mbti in MBTI_CODES) { "$id has an unknown personality code: $mbti" }
        require(personality.isNotBlank()) { "a code with no behaviour behind it is not a personality: $id" }
    }

    /** A cat with no tools must never answer from memory; it can only say "ask that one". */
    val answersFromData: Boolean get() = tools.isNotEmpty()

    companion object {
        /**
         * The sixteen codes, used here as a compact vocabulary of behaviour.
         *
         * This is a writing device, not a personality test — nothing in this codebase measures
         * anybody by it. It is here because a model can act on "ESTP" and cannot act on
         * "有主见、说到做到".
         */
        val MBTI_CODES: Set<String> = setOf(
            "ISTJ", "ISFJ", "INFJ", "INTJ",
            "ISTP", "ISFP", "INFP", "INTP",
            "ESTP", "ESFP", "ENFP", "ENTP",
            "ESTJ", "ESFJ", "ENFJ", "ENTJ",
        )
    }
}