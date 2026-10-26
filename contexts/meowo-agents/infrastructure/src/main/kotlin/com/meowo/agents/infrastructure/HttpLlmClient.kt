package com.meowo.agents.infrastructure

import com.meowo.agents.domain.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * An OpenAI-compatible HTTP endpoint.
 *
 * The timeouts are short and non-negotiable. A player is waiting at a chat prompt, and a cat that
 * takes ten seconds to answer is worse than one that says nothing: the whole loop is bounded by
 * [timeout], and a request that hangs holds an agent thread for as long as the server allows.
 *
 * ## What a failure is
 *
 * A failed call is a **returned value**, never an exception: [Completion.Unusable] for something
 * that will not get better (a malformed URL, a rejected key, an answer that is not an answer), and
 * [Completion.Unavailable] for something that might (a timeout, a 429, a 5xx). The two are answered
 * differently — the second one is worth one more request — and the runtime is what decides to make
 * it, so the retry is charged to the caller's allowance rather than being a free extra call the
 * daily cap cannot see.
 *
 * ## Why the body is read with a limit
 *
 * `BodyHandlers.ofString()` reads the whole response into memory, and the endpoint is not ours: a
 * misconfigured or hostile one can answer with as many gigabytes as it likes, and the agent worker
 * that reads it dies with the heap. So the body is read through a cap, and a body that is cut off
 * simply fails to parse — which is the same failure as any other unusable answer.
 *
 * ## Why the failures are reported
 *
 * A provider that starts answering 401 leaves no trace at all if nothing is written down: the cats
 * stop answering, the players think the feature is broken, and the operator has no evidence. Every
 * failure therefore goes through [onError], with the endpoint and the status — never the key.
 */
class HttpLlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val timeout: Duration = Duration.ofSeconds(8),
    /**
     * The completion budget. Left at the codec default on purpose: it is measured, not guessed
     * (see [OpenAiCodec.DEFAULT_MAX_TOKENS]), and the same value has to be used everywhere or the
     * measurement stops meaning anything. Overridable only so a deployment can raise it for a
     * longer tool conversation.
     */
    private val maxTokens: Int = OpenAiCodec.DEFAULT_MAX_TOKENS,
    /**
     * Keeps a reasoning model's thinking out of `content`, where it would otherwise be sent to a
     * player verbatim. Off only for an endpoint that rejects the field.
     */
    private val reasoningSplit: Boolean = true,
    /**
     * The transport, injectable so a test can supply its own.
     *
     * Null means "make one", and a client that made its own closes it; one that was handed a
     * transport leaves it alone, because somebody else is holding it.
     */
    transport: HttpClient? = null,
    /**
     * Where a failure is reported.
     *
     * Injected so this class stays free of any logger, and so the caller decides what an
     * operator should see. The key is never part of the message.
     */
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) : LlmClient, AutoCloseable {

    private val http: HttpClient = transport ?: HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Only a transport this client made is this client's to close. */
    private val ownsHttp: Boolean = transport == null

    /**
     * A failed call is a returned value, not an exception.
     *
     * The agent thread is shared by every player talking to every cat; one provider hiccup must not
     * take it down or leave it blocked. The runtime turns an unusable answer into a short
     * in-character apology, which is a better outcome than a stack trace in the server log.
     */
    override fun complete(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): Completion {
        if (baseUrl.isBlank() || apiKey.isBlank()) return Completion.Unusable
        val endpoint = OpenAiCodec.endpoint(baseUrl)

        val request = try {
            val body = OpenAiCodec.encodeRequest(model, messages, tools, maxTokens = maxTokens, reasoningSplit = reasoningSplit)
            HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        } catch (e: IllegalArgumentException) {
            // A URL that will not parse is a configuration mistake, not a hiccup: asking again
            // changes nothing, so it must not become a retry.
            onError("llm endpoint url is not usable: $endpoint", e)
            return Completion.Unusable
        }

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val status = response.statusCode()
            val text = response.body().use { readBounded(it, MAX_BODY_BYTES) }
            if (status !in 200..299) {
                // The endpoint's own words are the only clue about what it objected to, so a
                // snippet is kept. The key is not in it, and the snippet is bounded.
                onError("llm endpoint $endpoint answered $status: ${text.take(400)}", null)
                if (status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR) {
                    Completion.Unavailable("http $status")
                } else {
                    Completion.Unusable
                }
            } else {
                OpenAiCodec.decodeResponse(text)
            }
        } catch (e: Exception) {
            // Timeouts, resets and refused connections all land here. They are worth one more
            // try, and the runtime charges for that try like any other request.
            onError("llm call to $endpoint failed", e)
            Completion.Unavailable(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    /**
     * Reads at most [limit] bytes and decodes them.
     *
     * The cut is silent on purpose: a body that is too large is a body that cannot be trusted, and
     * the parser's own "this is not an answer" verdict is the right one for it.
     */
    private fun readBounded(stream: InputStream, limit: Int): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = stream.read(chunk, 0, minOf(chunk.size, limit - total))
            if (read < 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toString(Charsets.UTF_8)
    }

    /**
     * Releases the transport, if this client made it.
     *
     * `HttpClient` holds a thread of its own from Java 21 on, and a context that is closed and
     * started again would otherwise leave one behind every time.
     */
    override fun close() {
        if (ownsHttp) http.close()
    }

    private companion object {
        /** A megabyte. A long conversation's answer is kilobytes; anything past this is a mistake. */
        const val MAX_BODY_BYTES = 1024 * 1024

        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
