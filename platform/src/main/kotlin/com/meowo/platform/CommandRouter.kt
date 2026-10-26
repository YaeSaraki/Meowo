package com.meowo.platform

import org.bukkit.command.*
import java.util.Locale

/**
 * Notified when a context registers one of its routes.
 *
 * This exists because route names and context ids routinely differ — `eco` belongs to `economy`,
 * `tpa` to `teleport` — so a context that wants to know "which commands are mine" cannot work it
 * out from the name. Observing registration keeps the answer in step with the code by
 * construction: there is no second table to forget to update.
 */
fun interface RouteObserver {
    fun onRoute(contextId: String, name: String, route: Route)
}

/** Notified after a route has actually run, so a listener knows both *who* and *which*. */
fun interface RouteRunner {
    fun onRun(sender: CommandSender, route: String)
}

interface Route {
    val permission: String
    fun execute(sender: CommandSender, args: List<String>)
    fun complete(sender: CommandSender, args: List<String>): List<String> = emptyList()
}
/** Routes are explicitly owned by contexts, so disabling one removes both execution and completion. */
class CommandRouter(private val messages: Messages) : CommandExecutor, TabCompleter {
    private val routes = linkedMapOf<String, Route>()

    /**
     * Which context registered a route, and only for the routes that declared one.
     *
     * A name absent from this map was registered through the two-argument overload and is
     * deliberately unowned rather than guessed at: route names and context ids routinely differ
     * (`eco` is not `economy`, `tpa` is not `teleport`), so inferring one from the other would
     * produce confidently wrong answers at exactly the wrong moment.
     */
    private val owners = hashMapOf<String, String>()
    private val observers = java.util.concurrent.CopyOnWriteArrayList<RouteObserver>()
    private val runners = java.util.concurrent.CopyOnWriteArrayList<RouteRunner>()

    fun register(name: String, route: Route): AutoCloseable = add(name, route, owner = null)

    /**
     * Registers a route and declares who owns it.
     *
     * [contextId] is explicit rather than derived for the reason above, and it is what lets a
     * context learn which commands belong to it without the mapping drifting out of date: the
     * declaration sits on the same line as the registration.
     */
    fun register(name: String, contextId: String, route: Route): AutoCloseable = add(name, route, owner = contextId)

    private fun add(name: String, route: Route, owner: String?): AutoCloseable {
        check(name !in routes)
        routes[name] = route
        if (owner != null) owners[name] = owner
        if (owner != null) observers.forEach { notifySafely(it, owner, name, route) }
        return AutoCloseable {
            routes.remove(name, route)
            owners.remove(name)
        }
    }

    /**
     * Watches route registrations.
     *
     * Already-registered routes are **replayed** to a new observer, so a context that loads after
     * the others still ends up with the complete picture. Without that, load order would silently
     * decide how much a cat knows, which is the kind of bug that only shows up in production.
     *
     * An observer that throws is isolated: it cannot stop the route from being registered, and it
     * cannot stop the other observers from being told.
     */
    fun observe(observer: RouteObserver): AutoCloseable {
        observers.add(observer)
        for ((name, route) in routes) {
            owners[name]?.let { notifySafely(observer, it, name, route) }
        }
        return AutoCloseable { observers.remove(observer) }
    }

    /** The context that registered [name], or null when nobody declared one. */
    fun ownerOf(name: String): String? = owners[name]

    /**
     * Watches routes **as they run**.
     *
     * Separate from [observe] because the two questions are different: [observe] answers "which
     * routes exist", this answers "somebody just used one". A listener that wants the second has no
     * other way to get it — parsing `PlayerCommandPreprocessEvent` would mean re-deriving the route
     * name and its owner from the command string, which is the second source of truth this router
     * was built to avoid.
     *
     * Fires **after** the route has executed and **only when it ran**: an unknown command, a route
     * the sender may not use, and a console sender are all facts a listener would otherwise have to
     * re-check, and re-checking is how the answers drift apart.
     */
    fun onRun(runner: RouteRunner): AutoCloseable {
        runners.add(runner)
        return AutoCloseable { runners.remove(runner) }
    }

    /** Every route a context owns, in registration order. */
    fun routesOf(contextId: String): List<String> =
        owners.entries.filter { it.value == contextId }.map { it.key }

    private fun notifySafely(observer: RouteObserver, contextId: String, name: String, route: Route) {
        try {
            observer.onRoute(contextId, name, route)
        } catch (error: Exception) {
            java.util.logging.Logger.getLogger(CommandRouter::class.java.name)
                .warning("route observer failed for '$name': ${error.message}")
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val name = (args.firstOrNull() ?: "help").lowercase(Locale.ROOT)
        val route = routes[name]
        when {
            route == null -> messages.send(sender, "unknown-command")
            !sender.hasPermission(route.permission) -> messages.send(sender, "no-permission")
            else -> {
                route.execute(sender, args.drop(1))
                // After the fact, and only on the path that actually ran: a listener that has to
                // re-derive whether the command succeeded is a listener that will eventually
                // disagree with the router about it.
                runners.forEach { notifyRunSafely(it, sender, name) }
            }
        }
        return true
    }

    private fun notifyRunSafely(runner: RouteRunner, sender: CommandSender, name: String) {
        try {
            runner.onRun(sender, name)
        } catch (error: Exception) {
            java.util.logging.Logger.getLogger(CommandRouter::class.java.name)
                .warning("route runner failed for '$name': ${error.message}")
        }
    }
    /** Whether the sender is allowed to run [name]. Used by the help page to hide what it cannot run. */
    fun isVisible(sender: CommandSender, name: String): Boolean =
        routes[name]?.let { sender.hasPermission(it.permission) } ?: false

    /** True when [name] is a registered route at all, so a typo in the help text is not hidden. */
    fun hasRoute(name: String): Boolean = name in routes

    /**
     * The permission a route checks, or null when nothing is registered under [name].
     *
     * Exists so the "every route permission is declared in plugin.yml" check can run against the
     * live router. An undeclared node resolves against the default group — which is what let
     * `meowo.teleport.back` behave as OP-only for as long as it was missing, invisibly, because every
     * filter in the product read the same wrong answer and agreed with each other.
     */
    fun permissionOf(name: String): String? = routes[name]?.permission

    /**
     * Both branches filter by the prefix the player has typed.
     *
     * `Command#tabComplete` does **not** do it for us — Bukkit hands the completer's list straight
     * back — so a route that forgot would answer `/shadowbox gi<TAB>` with `open`. Doing it here
     * means every route gets it for free and none can forget.
     */
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size <= 1) return routes.filter { (name, route) -> sender.hasPermission(route.permission) && name.startsWith(args.firstOrNull().orEmpty(), true) }.keys.toList()
        val route = routes[args[0].lowercase(Locale.ROOT)] ?: return emptyList()
        if (!sender.hasPermission(route.permission)) return emptyList()
        return route.complete(sender, args.drop(1)).filter { it.startsWith(args.last(), true) }
    }
}
