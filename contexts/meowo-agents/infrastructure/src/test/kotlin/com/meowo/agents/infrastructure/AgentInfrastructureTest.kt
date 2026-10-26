package com.meowo.agents.infrastructure

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meowo.agents.domain.CatProfile
import com.meowo.agents.domain.CatRegistry
import com.meowo.agents.domain.ChatMessage
import com.meowo.agents.domain.Completion
import com.meowo.agents.domain.LlmClient
import com.meowo.agents.domain.ToolCall
import com.meowo.agents.domain.ToolContext
import com.meowo.agents.domain.ToolAnswer
import com.meowo.agents.domain.ToolSpec
import com.meowo.agents.application.ToolRegistry
import com.meowo.agents.application.readTool
import com.meowo.agents.domain.plain
import com.meowo.agents.domain.CatTurn
import com.meowo.agents.domain.PromptBuilder
import com.meowo.agents.domain.Redaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class OpenAiCodecTest {

    private fun body(json: String?) = OpenAiCodec.decodeResponse(json)

    private fun textResponse(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$content"}}]}"""

    /**
     * Builds a realistic `tool_calls` payload.
     *
     * [args] is written as a JSON *string value*, so its own quotes have to be escaped — an earlier
     * version inlined them raw and produced a body that was not JSON at all, which the codec
     * correctly reported as unusable and the test then misread as a codec bug.
     */
    private fun toolResponse(vararg calls: Triple<String, String, String>) =
        """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
        ${calls.joinToString(",") { (id, name, args) ->
            """{"id":"$id","type":"function","function":{"name":"$name","arguments":"${args.replace("\"", "\\\"")}"}}"""
        }}]}}]}
        """.trimIndent()

    // ── decoding ─────────────────────────────────────────────────────────────────────────────

    @Test fun `a plain answer decodes`() {
        val result = body(textResponse("你有 100 块。"))
        assertTrue(result is Completion.Text)
        assertEquals("你有 100 块。", (result as Completion.Text).content)
    }

    @Test fun `tool calls decode with their arguments intact`() {
        val result = body(toolResponse(Triple("c1", "economy.balance", """{"player":"Alice"}""")))
        assertTrue(result is Completion.ToolUse)
        val call = (result as Completion.ToolUse).calls.single()
        assertEquals("c1", call.id)
        assertEquals("economy.balance", call.name)
        assertEquals("""{"player":"Alice"}""", call.arguments)
    }

    @Test fun `several tool calls in one message all survive`() {
        val result = body(
            toolResponse(
                Triple("c1", "economy.balance", "{}"),
                Triple("c2", "teleport.estimatedFee", """{"target":"spawn"}"""),
            ),
        ) as Completion.ToolUse
        assertEquals(listOf("c1", "c2"), result.calls.map { it.id })
    }

    /**
     * A model that emits broken JSON should cost a turn, not silently become a wrong answer. The
     * codec hands the raw text on and lets the tool layer complain, which is recoverable.
     */
    @Test fun `unparseable arguments are passed through for the tool layer to reject`() {
        val result = body(
            """{"choices":[{"message":{"tool_calls":[
               {"id":"c1","function":{"name":"economy.balance","arguments":"{oops"}}]}}]}""",
        ) as Completion.ToolUse
        assertEquals("{oops", result.calls.single().arguments)
    }

    /**
     * The one that cost a whole round of wrong conclusions.
     *
     * Reasoning tokens come out of the same allowance as the answer, so a model told to think in a
     * small budget is cut off mid-thought and emits no tool call at all. At 80 tokens on MiniMax-M3
     * every single failure came back with `finish_reason: "length"`. A truncated response must never
     * become a player's half-finished thought.
     */
    @Test fun `a truncated response is unusable rather than a short answer`() {
        val truncated = """
            {"choices":[{"finish_reason":"length","message":{"role":"assistant",
             "content":"<think>The user is asking where their"}}]}
        """.trimIndent()
        assertTrue(body(truncated) is Completion.Unusable, "a cut-off thought is not a reply")
    }

    @Test fun `a complete response is still accepted`() {
        val complete = """{"choices":[{"finish_reason":"stop","message":{"content":"你有 3 个家。"}}]}"""
        assertEquals(Completion.Text("你有 3 个家。"), body(complete))
    }

    @Test fun `reasoning is split out so it cannot reach a player`() {
        val split = """{"choices":[{"finish_reason":"tool_calls","message":{
            "content":"","reasoning_details":[{"type":"reasoning.text","text":"<think>我该先调工具</think>"}]}}]}"""
        // An empty content is unusable, which is the right outcome: there is nothing to say.
        assertTrue(body(split) is Completion.Unusable)
    }

    @Test fun `the default budget leaves room for reasoning to finish`() {
        assertTrue(OpenAiCodec.DEFAULT_MAX_TOKENS >= 300,
            "measured: 80 truncated every reasoning response, 300 did not")
        val json = JsonParser.parseString(
            OpenAiCodec.encodeRequest("m", listOf(ChatMessage.User(content = "hi")))
        ).asJsonObject
        assertEquals(OpenAiCodec.DEFAULT_MAX_TOKENS, json.get("max_tokens").asInt)
        assertTrue(json.get("reasoning_split").asBoolean, "on by default so thinking stays out of content")
    }

    /**
     * The budget has to cover the **answer**, not only the thinking.
     *
     * Reasoning tokens and the reply are drawn from one allowance, so a bound on the reply that
     * the budget cannot pay for is a promise the model cannot keep: it gets cut off mid-sentence,
     * and a cut-off reply reads as a broken bot rather than as a terse one.
     *
     * Two tokens per character is a deliberately pessimistic reading of Chinese text — a reply of
     * the full allowed length should still fit under the budget with room left for reasoning.
     */
    @Test fun `the budget covers the reply the prompt allows`() {
        val needed = PromptBuilder.MAX_REPLY_CHARS * 2
        assertTrue(OpenAiCodec.DEFAULT_MAX_TOKENS >= needed,
            "a reply of ${PromptBuilder.MAX_REPLY_CHARS} characters needs about $needed tokens, " +
                "and the budget is ${OpenAiCodec.DEFAULT_MAX_TOKENS}")
    }

    @Test fun `reasoning_split can be turned off for an endpoint that does not know it`() {
        val json = JsonParser.parseString(
            OpenAiCodec.encodeRequest("m", listOf(ChatMessage.User(content = "hi")), reasoningSplit = false)
        ).asJsonObject
        assertFalse(json.has("reasoning_split"))
    }

    @Test fun `every malformed response is unusable rather than an exception`() {
        val cases: List<String?> = listOf(
            null, "", "not json", "[]", "{}", """{"choices":[]}""",
            """{"choices":[{"message":{}}]}""",
            """{"choices":[{"message":{"content":"   "}}]}""",
        )
        for (c in cases) {
            val decoded = body(c)
            assertTrue(decoded is Completion.Unusable, "should have been unusable: $c -> $decoded")
        }
    }

    @Test fun `a tool call without a name is dropped instead of crashing`() {
        val result = body(
            """{"choices":[{"message":{"tool_calls":[{"id":"c1","function":{}}]}}]}""",
        )
        assertEquals(Completion.Unusable, result, "a nameless call cannot be routed anywhere")
    }

    // ── encoding ─────────────────────────────────────────────────────────────────────────────

    private fun encode(messages: List<ChatMessage>, tools: List<ToolSpec> = emptyList()): JsonObject =
        JsonParser.parseString(OpenAiCodec.encodeRequest("gpt-x", messages, tools)).asJsonObject

    @Test fun `a built prompt becomes a system and a user message`() {
        val prompt = PromptBuilder.Prompt("你是一只猫。", "[question]\n我有多少钱？")
        val messages = PromptBuilder.opening(prompt)
        assertEquals(listOf("system", "user"), messages.map { it.role })
        val root = encode(messages)
        val array = root.getAsJsonArray("messages")
        assertEquals("你是一只猫。", array[0].asJsonObject.get("content").asString)
    }

    @Test fun `a pure tool call is sent with a null content, not an empty string`() {
        val root = encode(
            listOf(
                ChatMessage.Assistant(content = null, toolCalls = listOf(ToolCall("c1", "economy.balance", "{}"))),
            ),
        )
        val message = root.getAsJsonArray("messages")[0].asJsonObject
        assertFalse(message.has("content"), "an absent key and a null one are not the same to the endpoint")
        assertEquals("c1", message.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString)
    }

    @Test fun `a tool result carries the id the model used`() {
        val root = encode(listOf(ChatMessage.ToolResult(toolCallId = "c1", content = "100 块")))
        val message = root.getAsJsonArray("messages")[0].asJsonObject
        assertEquals("tool", message.get("role").asString)
        assertEquals("c1", message.get("tool_call_id").asString)
        assertEquals("100 块", message.get("content").asString)
    }

    @Test fun `tools are omitted entirely when there are none`() {
        val root = encode(listOf(ChatMessage.User(content = "hi")))
        assertFalse(root.has("tools"))
        assertFalse(root.has("tool_choice"))
    }

    @Test fun `tools are advertised when present`() {
        val spec = ToolSpec("economy.balance", "查余额", "{}")
        val root = encode(listOf(ChatMessage.User(content = "hi")), listOf(spec))
        val fn = root.getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function")
        assertEquals("economy.balance", fn.get("name").asString)
        assertEquals("auto", root.get("tool_choice").asString)
    }

    @Test fun `the endpoint tolerates a trailing slash`() {
        assertEquals("https://x.test/chat/completions", OpenAiCodec.endpoint("https://x.test"))
        assertEquals("https://x.test/chat/completions", OpenAiCodec.endpoint("https://x.test/"))
    }

    @Test fun `a round trip survives a request and its answer`() {
        val sent = encode(listOf(ChatMessage.User(content = "我有多少钱")))
        assertEquals("gpt-x", sent.get("model").asString)
        assertFalse(sent.get("stream").asBoolean, "streaming is not implemented and must not be requested")
        assertNotNull(body(textResponse("100 块")) as Completion.Text)
    }
}

class ToolRegistryTest {

    private val alice = UUID.randomUUID()
    private val econ = CatRegistry.byId("economy")!!
    private val sit = CatRegistry.byId("sit")!!

    private fun params(vararg required: String): String =
        """{"type":"object","properties":{},"required":[${required.joinToString(",") { "\"$it\"" }}]}"""

    private fun registry() = ToolRegistry(
        listOf(
            readTool("economy.balance", "查余额", params()) { _, _ -> plain("100 块") },
            readTool("economy.treasury", "查国库", params()) { _, ctx -> treasury(ctx) },
        ),
    )

    private fun treasury(ctx: ToolContext) = when (val v = ctx.disclose(alice)) {
        is Redaction.Verdict.Refused -> ToolAnswer.Refused(v.message)
        is Redaction.Verdict.Allowed -> plain("国库 5000 块（范围 ${v.scope}）")
    }

    private fun ctx(
        cat: CatProfile = econ,
        admin: Boolean = false,
        allowed: Boolean = false,
    ) = ToolContext(
        asker = alice, cat = cat, askerIsAdmin = admin,
        disclose = { if (allowed) Redaction.Verdict.Allowed(Redaction.Scope.SUMMARY) else Redaction.Verdict.Refused(Redaction.PRIVATE_REFUSAL) },
    )

    private fun call(name: String, args: String = "{}") = ToolCall("c1", name, args)

    @Test fun `a cat may call its own tool`() {
        val turn = registry().invoke(econ, call("economy.balance"), ctx())
        assertTrue(turn is CatTurn.Reply)
        assertEquals("100 块", (turn as CatTurn.Reply).text)
    }

    /**
     * The gate that matters. A model can emit any name it likes; only a name the cat owns gets as
     * far as being looked up.
     */
    @Test fun `a tool outside the cat's whitelist never reaches a registry`() {
        val spy = ToolRegistry(listOf(readTool("economy.setBalance", "改余额", params()) { _, _ -> plain("改了") }))
        val turn = spy.invoke(econ, call("economy.setBalance"), ctx())
        assertTrue(turn is CatTurn.Refuse)
        assertFalse((turn as CatTurn.Refuse).text.contains("改了"), "the tool body must not have run")
    }

    @Test fun `a tool the cat owns but the registry lacks says so instead of throwing`() {
        val empty = ToolRegistry()
        val turn = empty.invoke(econ, call("economy.balance"), ctx())
        assertTrue(turn is CatTurn.Refuse)
        assertTrue((turn as CatTurn.Refuse).text.contains("不可用"))
    }

    @Test fun `broken arguments are refused rather than read as an empty request`() {
        val turn = registry().invoke(econ, call("economy.balance", "{not json"), ctx())
        assertTrue(turn is CatTurn.Refuse, "got $turn")
    }

    @Test fun `a tool that throws becomes a refusal, not a dead agent thread`() {
        val boom = ToolRegistry(listOf(readTool("economy.balance", "查余额", params()) { _, _ -> error("kaboom") }))
        val turn = boom.invoke(econ, call("economy.balance"), ctx())
        assertTrue(turn is CatTurn.Refuse)
        assertFalse((turn as CatTurn.Refuse).text.contains("kaboom"), "a stack trace is not a chat message")
    }

    @Test fun `a refused disclosure returns the refusal text, never the data`() {
        val reg = registry()
        val refused = reg.invoke(econ, call("economy.treasury"), ctx())
        assertTrue(refused is CatTurn.Refuse, "a policy refusal must stay a refusal, not become an answer: $refused")
        assertEquals(Redaction.PRIVATE_REFUSAL, (refused as CatTurn.Refuse).text)
        val allowed = reg.invoke(econ, call("economy.treasury"), ctx(allowed = true)) as CatTurn.Reply
        assertTrue(allowed.text.contains("国库"), allowed.text)
    }

    @Test fun `only the requested tools are advertised to the model`() {
        val specs = registry().specsFor(econ)
        assertEquals(listOf("economy.balance", "economy.treasury"), specs.map { it.name })
        assertTrue(registry().specsFor(sit).isEmpty(), "a toolless cat is offered nothing")
    }

    /**
     * A cat profile naming a tool no registry serves is a wiring bug, and it is reported rather
     * than discovered later as a confusing refusal in conversation.
     */
    @Test fun `a profile whose tools are missing is reported at startup`() {
        val reg = registry()
        // This stub serves two of EconCat's five; the other three are exactly what must be reported.
        assertEquals(
            listOf("economy.chargeStatus", "economy.dailyStatus", "economy.teleportFee"),
            reg.missingFor(econ),
        )
        assertEquals(
            listOf("economy.chargeStatus", "economy.dailyStatus", "economy.teleportFee", "home.list"),
            reg.missingFor(econ.copy(tools = econ.tools + setOf("home.list"))),
        )
    }

    @Test fun `registries merge without losing a tool`() {
        val merged = ToolRegistry(listOf(readTool("a.one", "d", params()) { _, _ -> plain("1") })) +
            ToolRegistry(listOf(readTool("b.two", "d", params()) { _, _ -> plain("2") }))
        assertEquals(setOf("a.one", "b.two"), merged.names)
    }

    /** The registry has no write-shaped surface at all; this is the check that stays true. */
    @Test fun `no write-shaped tool can be expressed`() {
        val forbidden = listOf("deposit", "withdraw", "setBalance", "transfer", "claimDaily", "reserveTax")
        val names = registry().names.toList() + ToolRegistry().names
        for (n in forbidden) {
            assertTrue(names.none { it.contains(n, ignoreCase = true) }, "$n is a write and must not be exposed")
        }
    }
}

class LlmClientTest {

    /** A scripted client, so the whole loop is testable with no network and no sleeping. */
    private class Scripted(private val script: List<Completion>) : LlmClient {
        val seen = mutableListOf<List<ChatMessage>>()
        override fun complete(model: String, messages: List<ChatMessage>, tools: List<ToolSpec>): Completion {
            seen += messages
            return script.getOrElse(seen.size - 1) { Completion.Unusable }
        }
    }

    @Test fun `an unconfigured endpoint is unusable rather than an exception`() {
        val client = HttpLlmClient(baseUrl = "", apiKey = "k")
        assertEquals(Completion.Unusable, client.complete("m", listOf(ChatMessage.User(content = "hi")), emptyList()))
    }

    /**
     * A refused connection is worth one more try, so it is **unavailable** rather than unusable.
     *
     * It used to be unusable, which meant the runtime gave up on the first hiccup — and the
     * difference between the two is the difference between a cat that says "喵……刚才走神了" once
     * and one that says it every time the provider is briefly busy.
     */
    @Test fun `an unreachable endpoint is unavailable rather than an exception`() {
        val client = HttpLlmClient(
            baseUrl = "http://127.0.0.1:1",
            apiKey = "k",
            timeout = java.time.Duration.ofMillis(500),
        )
        val result = client.complete("m", listOf(ChatMessage.User(content = "hi")), emptyList())
        assertTrue(result is Completion.Unavailable, "got $result")
    }

    /**
     * A URL that will not parse is a configuration mistake, so it must not be retried.
     */
    @Test fun `a malformed url is unusable rather than unavailable`() {
        val client = HttpLlmClient(baseUrl = "http://exa mple.com", apiKey = "k")
        val result = client.complete("m", listOf(ChatMessage.User(content = "hi")), emptyList())
        assertEquals(Completion.Unusable, result)
    }

    /**
     * A local endpoint that answers whatever the test tells it to.
     *
     * A real socket rather than a mocked transport, because the things worth testing here are
     * exactly the things a mock would hide: the status code, the body and its size.
     */
    private class Endpoint(
        private val status: Int,
        private val body: ByteArray,
    ) : AutoCloseable {
        private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/chat/completions") { exchange ->
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }
    }

    @Test fun `a server error is unavailable and is reported`() {
        Endpoint(500, "boom".toByteArray()).use { endpoint ->
            val reported = mutableListOf<String>()
            val client = HttpLlmClient(
                baseUrl = endpoint.url,
                apiKey = "k",
                onError = { message, _ -> reported += message },
            )
            val result = client.complete("m", listOf(ChatMessage.User(content = "hi")), emptyList())
            assertTrue(result is Completion.Unavailable, "a 5xx may pass: $result")
            assertTrue(reported.isNotEmpty(), "a silent failure is the failure nobody can fix")
            assertTrue(reported.any { it.contains("500") }, "the status must be in the report: $reported")
        }
    }

    @Test fun `a rejected key is unusable and is reported`() {
        Endpoint(401, """{"error":"bad key"}""".toByteArray()).use { endpoint ->
            val reported = mutableListOf<String>()
            val client = HttpLlmClient(
                baseUrl = endpoint.url,
                apiKey = "k",
                onError = { message, _ -> reported += message },
            )
            val result = client.complete("m", listOf(ChatMessage.User(content = "hi")), emptyList())
            assertEquals(Completion.Unusable, result, "a rejected key will not fix itself")
            assertTrue(reported.any { it.contains("401") }, "the status must be in the report: $reported")
        }
    }

    /**
     * The response body is read through a cap.
     *
     * A megabyte is far more than an answer needs, so a body that is larger than that is not an
     * answer: the read stops at the cap and the truncated text fails to parse, which is the
     * honest verdict. Without the cap the same body would be read whole into the heap.
     */
    @Test fun `an oversized body is cut off instead of being read whole`() {
        val padding = "x".repeat(2 * 1024 * 1024)
        val body = """{"choices":[{"message":{"role":"assistant","content":"喵"}}],"pad":"$padding"}"""
        Endpoint(200, body.toByteArray()).use { endpoint ->
            val client = HttpLlmClient(baseUrl = endpoint.url, apiKey = "k")
            val result = client.complete("m", listOf(ChatMessage.User(content = "hi")), emptyList())
            assertEquals(Completion.Unusable, result, "a cut-off answer is not a short answer")
        }
    }

    /** A body inside the cap is still a good answer. */
    @Test fun `an ordinary answer is still decoded`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"喵"}}]}"""
        Endpoint(200, body.toByteArray()).use { endpoint ->
            val client = HttpLlmClient(baseUrl = endpoint.url, apiKey = "k")
            val result = client.complete("m", listOf(ChatMessage.User(content = "hi")), emptyList())
            assertEquals(Completion.Text("喵"), result)
        }
    }

    @Test fun `the scripted client hands back the next scripted turn`() {
        val client = Scripted(listOf(Completion.Text("喵"), Completion.Text("喵喵")))
        assertEquals(Completion.Text("喵"), client.complete("m", emptyList(), emptyList()))
        assertEquals(Completion.Text("喵喵"), client.complete("m", emptyList(), emptyList()))
    }
}
