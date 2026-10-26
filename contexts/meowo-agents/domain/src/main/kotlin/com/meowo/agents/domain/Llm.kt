package com.meowo.agents.domain

/**
 * One message in an OpenAI-compatible chat request.
 *
 * A closed sum of shapes rather than a bag of nullable fields: a tool result has no `content`, and a
 * tool call has no content at all. Modelling that as a sealed interface means the codec cannot
 * accidentally emit `content: null` where the endpoint expects `tool_call_id`.
 */
sealed interface ChatMessage {
    val role: String

    data class System(override val role: String = "system", val content: String) : ChatMessage

    data class User(override val role: String = "user", val content: String) : ChatMessage

    data class Assistant(
        override val role: String = "assistant",
        val content: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
    ) : ChatMessage

    data class ToolResult(
        override val role: String = "tool",
        val toolCallId: String,
        val content: String,
    ) : ChatMessage
}

data class ToolCall(val id: String, val name: String, val arguments: String = "{}")

/**
 * A tool as the model is told about it.
 *
 * [parameters] is the schema as **JSON text**, not as a JSON object.
 *
 * The schema is the wire format, and the wire format belongs to the codec: the domain saying "a
 * tool wants these arguments" does not need to know what a JSON library is. It used to hold a
 * `JsonObject`, which meant this domain imported a JSON library — and the whole point of the pure
 * domain is that it can be read, reasoned about and tested without any of the machinery around it.
 *
 * The text is parsed once, by the codec, on the way out.
 */
data class ToolSpec(val name: String, val description: String, val parameters: String)

/** One round-trip's worth of answer. */
sealed interface Completion {
    /** Text with no tool call. */
    data class Text(val content: String) : Completion

    /** The model wants to call tools; [calls] is never empty. */
    data class ToolUse(val calls: List<ToolCall>) : Completion

    /** The endpoint answered, but not with anything usable. */
    data object Unusable : Completion

    /**
     * The endpoint failed in a way that may pass: a timeout, a rate limit, a 5xx.
     *
     * Kept apart from [Unusable] because the two are answered differently. An unusable answer ends
     * the conversation — a rejected key or a malformed request will not fix itself. An unavailable
     * one is worth one more request.
     *
     * That request is **not free**: the runtime makes it as an ordinary round, so the caller's
     * allowance pays for it exactly as it pays for the first attempt. A retry hidden inside the
     * client would be a real provider call the daily cap could not see, and a cap that
     * under-counts is worse than no cap.
     */
    data class Unavailable(val reason: String) : Completion
}

/**
 * The port the runtime talks to a model through.
 *
 * An interface with one method, so the whole agent loop is testable against a scripted fake with no
 * network and no sleeping (08 §19). Everything transport-shaped lives in the infrastructure layer.
 */
fun interface LlmClient {
    /**
     * One round trip.
     *
     * Implementations must not block a tick thread; the runtime calls this from its own pool.
     */
    fun complete(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): Completion
}
