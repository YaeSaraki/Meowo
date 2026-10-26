package com.meowo.agents.application

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meowo.agents.domain.CatProfile
import com.meowo.agents.domain.CatTurn
import com.meowo.agents.domain.ToolAnswer
import com.meowo.agents.domain.ToolCall
import com.meowo.agents.domain.ToolContext
import com.meowo.agents.domain.ToolSpec

/**
 * One read-only lookup a cat may perform.
 *
 * ## Why there is no write mode
 *
 * Every mutating economy operation — `deposit`, `withdraw`, `setBalance`, `transfer`,
 * `claimDaily`, `reserveTax`, and the three teleport lifecycle calls — is a *write*. None of them
 * appears on this type, and there is no flag that could turn one on. That absence is the guarantee
 * the plan asks for, and it is a property of the shape rather than of anybody's discipline: the
 * ten write methods simply have nowhere to be registered.
 *
 * ## Data and refusal are different things
 *
 * A tool returns [ToolAnswer], not a bare string. Without that split a policy refusal came back
 * as an ordinary answer, and the runtime could no longer tell "there is no such data" from "I am
 * not allowed to tell you" — which is precisely the distinction the plan insists on. See
 * [com.meowo.agents.domain.Redaction] for why the check lives in the tool layer rather than being
 * trusted to the prompt.
 *
 * ## Why this lives in the application layer
 *
 * The arguments a model sends arrive as JSON, and reading them means parsing JSON, which means
 * naming a JSON library. The domain must not: it is the layer that can be read and tested with
 * nothing else in the room. So the *contract* — what a tool is, what it answers, what it may be
 * asked — stayed in the domain, and the *plumbing* came here, where a library is allowed.
 */
class ReadTool(
    val name: String,
    /** Read by the model, so it is part of the tool's behaviour and not documentation. */
    val description: String,
    /** The tool's only contract: the schema, as JSON text. */
    val parameters: String,
    private val body: (args: JsonObject, ctx: ToolContext) -> ToolAnswer,
) {
    fun call(args: JsonObject, ctx: ToolContext): ToolAnswer = body(args, ctx)

    fun spec(): ToolSpec = ToolSpec(name, description, parameters)
}

/** Terse constructor, so a tool file reads as behaviour rather than as boilerplate. */
fun readTool(
    name: String,
    description: String,
    parameters: String,
    body: (args: JsonObject, ctx: ToolContext) -> ToolAnswer,
) = ReadTool(name, description, parameters, body)

/**
 * The registry, and the only way a model reaches data.
 *
 * Two gates, both here:
 *
 * 1. A cat may only call tools in its own [CatProfile.tools]. Anything else is refused *before* the
 *    registry is consulted, so a hallucinated `economy.setBalance` is stopped on a name it can
 *    never own — and, just as importantly, never reaches a registry that might.
 * 2. An unparseable argument list is refused rather than passed on as an empty object, so a model
 *    that emits broken JSON gets a turn to recover instead of a tool reading `{}` as a request.
 */
class ToolRegistry(tools: List<ReadTool> = emptyList()) {

    private val byName: Map<String, ReadTool> = tools.associateBy { it.name }

    /** Names this registry can serve, for diagnostics and for catching a typo in a cat profile. */
    val names: Set<String> get() = byName.keys

    fun specsFor(cat: CatProfile): List<ToolSpec> =
        cat.tools.sorted().mapNotNull { byName[it]?.spec() }

    /**
     * Tools a cat's profile claims but this registry cannot serve.
     *
     * A non-empty result is always a wiring bug — a renamed tool, or a context that failed to
     * register its half of the registry — and it means the cat has been told it may use something it
     * cannot, which would otherwise show up as a confusing refusal at conversation time.
     */
    fun missingFor(cat: CatProfile): List<String> = cat.tools.filter { it !in byName }.sorted()

    /**
     * Runs one call the model asked for, as a [CatTurn] so an unusable, refused and genuine answer
     * stay distinguishable without the runtime re-deriving anything.
     */
    fun invoke(cat: CatProfile, call: ToolCall, ctx: ToolContext): CatTurn {
        if (call.name !in cat.tools) {
            return CatTurn.Refuse("${cat.displayName} 没有 ${call.name} 这个能力。")
        }
        val tool = byName[call.name]
            ?: return CatTurn.Refuse("${call.name} 现在不可用，回头再问一次吧。")
        val args = runCatching { JsonParser.parseString(call.arguments) }.getOrNull()
        if (args !is JsonObject) return CatTurn.Refuse("参数没看明白，换个说法？")
        // A tool that throws is a *failure*, not an answer. Returning it as a Reply would let the
        // runtime pass an internal error off to a player as though a cat had said it.
        return when (val answer = runCatching { tool.call(args, ctx) }
            .getOrElse { return CatTurn.Refuse("${call.name} 出了点问题。") }) {
            is ToolAnswer.Data -> CatTurn.Reply(answer.text)
            // The refusal keeps its own identity all the way out, so the runtime can prefer it over
            // a generic apology when the provider fails mid-conversation.
            is ToolAnswer.Refused -> CatTurn.Refuse(answer.message)
        }
    }

    /** Merges registries; used to assemble the per-context tool sets at startup. */
    operator fun plus(other: ToolRegistry) = ToolRegistry(byName.values + other.byName.values)
}
