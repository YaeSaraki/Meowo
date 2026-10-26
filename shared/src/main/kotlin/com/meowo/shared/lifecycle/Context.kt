package com.meowo.shared.lifecycle

/** A context owns all its subscriptions, services and resources. close must be idempotent. */
interface Context : AutoCloseable {
    val id: String
    fun start()
}

/**
 * Explicit ordering avoids annotation scanning and hidden coupling. Start failures roll back the
 * failing context.
 *
 * ## A failure in one context must not take the server with it
 *
 * The manager catches the failure, rolls the context back and reports it; the server comes up
 * **without** that feature and with a log line saying so. That is the right shape for a plugin
 * whose features are independent: one broken context should cost one feature, not the whole server.
 *
 * But it only holds if the thing being caught is an **exception**. An `Error` — and the one that
 * matters here is `StackOverflowError`, which is what a catastrophically backtracking regular
 * expression throws — is not an `Exception`, so it used to escape `enable()` entirely and unwind
 * the whole plugin's start-up. One bad pattern in `config.yml` took down every context, not just
 * the chat one. So both here and in the cleanup path below, the caught type is `Throwable`.
 */
class ContextManager(private val onFailure: (String, Throwable) -> Unit) : AutoCloseable {
    private val active = linkedMapOf<String, Context>()
    fun enable(context: Context): Boolean {
        check(context.id !in active) { "Duplicate context: ${context.id}" }
        return try {
            context.start()
            active[context.id] = context
            true
        } catch (error: Throwable) {
            // The rollback must not itself throw: a context whose `close()` fails would otherwise
            // replace the real error — the one that says what went wrong — with a cleanup error.
            try { context.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            onFailure(context.id, error)
            false
        }
    }
    fun disable(id: String) { active.remove(id)?.let { try { it.close() } catch (e: Throwable) { onFailure(id, e) } } }
    fun isEnabled(id: String) = id in active
    fun ids(): Set<String> = active.keys.toSet()
    override fun close() { active.keys.toList().asReversed().forEach(::disable) }
}
