package com.meowo.agents.infrastructure

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.meowo.agents.domain.ChatMessage
import com.meowo.agents.domain.Completion
import com.meowo.agents.domain.ToolCall
import com.meowo.agents.domain.ToolSpec

/**
 * Wire format for an OpenAI-compatible `/chat/completions` endpoint.
 *
 * ## Total, not partial
 *
 * Every failure mode is a returned [Completion.Unusable] — never an exception, never a
 * half-decoded object. The agent thread is shared by every player talking to every cat, and one
 * malformed response from a provider must not take it down or leave it blocked.
 */
object OpenAiCodec {

    /**
     * Encodes a chat request body.
     *
     * The URL is deliberately not part of this: [endpoint] is where a base URL becomes an address,
     * and keeping the two apart is what lets the codec be tested with no network at all.
     */
    fun encodeRequest(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        temperature: Double = 0.7,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        reasoningSplit: Boolean = true,
    ): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("temperature", temperature)
        root.addProperty("max_tokens", maxTokens)
        root.add("stream", JsonPrimitive(false))
        if (reasoningSplit) root.addProperty("reasoning_split", true)
        root.add("messages", JsonArray().apply { messages.forEach { add(encodeMessage(it)) } })
        if (tools.isNotEmpty()) {
            root.add("tools", JsonArray().apply { tools.forEach { add(encodeTool(it)) } })
            root.addProperty("tool_choice", "auto")
        }
        return root.toString()
    }

    /**
     * The output budget a request gets.
     *
     * Reasoning tokens are drawn from the same allowance as the answer. Measured on MiniMax-M3 with
     * 15 identical requests each: at 80 the tool call arrived 10/15 and **every** failure came back
     * with `finish_reason: "length"` — the model was cut off mid-thought. At 300 and above it was
     * 15/15 with no truncation, using 71–91 tokens. 80 looked like a model that would not call tools;
     * it was a budget that stopped it before it could.
     *
     * **1024, because the answer got longer.** Those 71–91 tokens were the *thinking*; the reply
     * shares the same allowance, and a reply of [PromptBuilder.MAX_REPLY_CHARS] characters is
     * several hundred tokens on its own. At 512 a chatty cat could be cut off mid-sentence — and a
     * cut-off reply is the one failure a player reads as "the bot is broken" rather than "the bot
     * is terse".
     */
    const val DEFAULT_MAX_TOKENS = 1024

    /** Where a request goes. Tolerant of a base URL that already ends in a slash. */
    fun endpoint(baseUrl: String): String = "${baseUrl.trimEnd('/')}/chat/completions"

    private fun encodeMessage(m: ChatMessage): JsonObject = JsonObject().apply {
        addProperty("role", m.role)
        when (m) {
            is ChatMessage.System -> addProperty("content", m.content)
            is ChatMessage.User -> addProperty("content", m.content)
            is ChatMessage.ToolResult -> {
                addProperty("tool_call_id", m.toolCallId)
                addProperty("content", m.content)
            }
            is ChatMessage.Assistant -> {
                // A pure tool call has no content at all; some endpoints reject an explicit null.
                if (m.content != null) addProperty("content", m.content)
                if (m.toolCalls.isNotEmpty()) {
                    add("tool_calls", JsonArray().apply {
                        m.toolCalls.forEach { call ->
                            add(JsonObject().apply {
                                addProperty("id", call.id)
                                addProperty("type", "function")
                                add("function", JsonObject().apply {
                                    addProperty("name", call.name)
                                    addProperty("arguments", call.arguments)
                                })
                            })
                        }
                    })
                }
            }
        }
    }

    private fun encodeTool(spec: ToolSpec): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", spec.name)
            addProperty("description", spec.description)
            // The schema arrives as **text**, because the domain's tool contract must not know what
            // a JSON library is. Parsing it here — once, on the way out — is the one place that
            // knowledge is allowed to exist. A schema that will not parse is a bug in the tool
            // definition, so it fails loudly rather than being sent as a string where the endpoint
            // expects an object.
            add("parameters", JsonParser.parseString(spec.parameters))
        })
    }

    /**
     * Decodes a response body.
     *
     * A tool call whose `arguments` are not valid JSON is still returned, raw: the tool layer is
     * what validates arguments, and a codec that dropped the call would turn a model typo into a
     * confidently wrong answer instead of a recoverable error.
     */
    fun decodeResponse(body: String?): Completion {
        val root = runCatching { JsonParser.parseString(body.orEmpty()) }.getOrNull() as? JsonObject
            ?: return Completion.Unusable
        // A truncated answer is not a short answer. Whatever came back was cut off mid-thought, and
        // sending it would put a half-finished thought in front of a player.
        val finish = root.getAsJsonArray("choices")
            ?.takeIf { it.size() > 0 }?.get(0)?.asJsonObject?.get("finish_reason")?.asString
        if (finish == "length") return Completion.Unusable
        val message = root.getAsJsonArray("choices")
            ?.takeIf { it.size() > 0 }?.get(0)
            ?.asJsonObject?.getAsJsonObject("message")
            ?: return Completion.Unusable

        val calls = message.getAsJsonArray("tool_calls")?.mapNotNull { element ->
            val call = element.asJsonObject
            val fn = call?.getAsJsonObject("function")
            val name = fn?.get("name")?.asString ?: return@mapNotNull null
            ToolCall(
                id = call.get("id")?.asString ?: name,
                name = name,
                arguments = fn.get("arguments")?.asString ?: "{}",
            )
        }.orEmpty()

        if (calls.isNotEmpty()) return Completion.ToolUse(calls)
        val text = runCatching { message.get("content")?.asString }.getOrNull()
        return if (text.isNullOrBlank()) Completion.Unusable else Completion.Text(text)
    }
}
