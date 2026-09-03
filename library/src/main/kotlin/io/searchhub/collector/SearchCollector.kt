package io.searchhub.collector

import android.content.Context
import android.os.Build
import io.searchhub.collector.SearchCollector.DEBUG_TOKEN_PARAM
import io.searchhub.collector.SearchCollector.configure
import io.searchhub.collector.SearchCollector.disable
import io.searchhub.collector.SearchCollector.extractDebugToken
import io.searchhub.collector.SearchCollector.flush
import io.searchhub.collector.SearchCollector.flushAsync
import io.searchhub.collector.SearchCollector.setNavContext
import io.searchhub.collector.impl.context.AndroidBrowserInfoProvider
import io.searchhub.collector.impl.queue.BASE_PREFS_KEY
import io.searchhub.collector.impl.queue.InMemoryEventQueue
import io.searchhub.collector.impl.session.SharedPreferencesSessionStore
import io.searchhub.collector.impl.timestamp.SystemTimestampProvider
import io.searchhub.collector.impl.trail.SharedPreferencesTrailStore
import io.searchhub.collector.impl.transport.ShSqsTransport
import io.searchhub.collector.interfaces.DebugCapable
import io.searchhub.collector.interfaces.Logger
import io.searchhub.collector.interfaces.consoleLogger
import io.searchhub.collector.interfaces.createFilteredLogger
import io.searchhub.collector.model.BufferedEventsTimestamp
import io.searchhub.collector.model.CheckoutProduct
import io.searchhub.collector.model.DependencyOverrides
import io.searchhub.collector.model.LogLevel
import io.searchhub.collector.model.ProductPosition
import io.searchhub.collector.model.SearchAction
import io.searchhub.collector.model.SearchCollectorConfig
import io.searchhub.collector.model.TrailType
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import io.searchhub.collector.impl.session.PREFS_NAME as SESSION_PREFS_NAME
import io.searchhub.collector.impl.trail.PREFS_NAME as TRAIL_PREFS_NAME

/**
 * Singleton facade for the SearchHub Search Collector.
 *
 * Usage:
 * 1. Call [configure] once on app start (e.g. in Application.onCreate).
 * 2. Optionally call [setNavContext] whenever the user navigates to a new screen.
 * 3. Call tracking methods anywhere — they are fire-and-forget and safe to call before [configure].
 * 4. Optionally call [flush] or [flushAsync] to force-send all queued events.
 *
 * Java callers use @JvmStatic methods directly: SearchCollector.configure(...)
 */
object SearchCollector {

    /** Query parameter name for the debug session token in a deep-link Intent URI. */
    const val DEBUG_TOKEN_PARAM = "___scForceNewSession_"

    @Volatile
    private var core: SearchCollectorCore? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var isDisabled = false

    @Volatile
    private var maxPendingActions: Int = 250

    private val cachedContext = AtomicReference("" to "")

    @Volatile
    private var pendingCore: SearchCollectorCore? = null

    private val pendingActions = ConcurrentLinkedQueue<PendingAction>()

    // Matches SearchCollectorConfig's default logLevel (ERROR) so pre-configure() log calls
    // (e.g. buffering, disable(), setNavContext()) are silent by default, consistent with the
    // eventual configured behavior — not the unfiltered fallback used only to avoid a null logger.
    @Volatile
    private var logger: Logger = createFilteredLogger(consoleLogger, LogLevel.ERROR)

    /**
     * Configure and initialize the collector. Must be called before events can be sent.
     * Safe to call multiple times — disposes the previous instance first.
     * Re-enables the collector if [disable] was previously called.
     */
    @JvmStatic
    fun configure(config: SearchCollectorConfig) {
        val reconfiguring = core != null || pendingCore != null
        isDisabled = false
        maxPendingActions = config.queueSettings.maxPendingActions
        logger = createFilteredLogger(config.logger ?: consoleLogger, config.logLevel)
        logger.info(
            if (reconfiguring) "Reconfiguring SearchCollector — disposing previous instance" else "Configuring SearchCollector",
            "channel=${config.channel} logLevel=${config.logLevel}",
        )

        val appContext = config.context.applicationContext
        this.appContext = appContext

        // Cancel any in-flight replay, then gracefully flush the active core.
        // core is explicitly nulled so fireAndForget buffers during replay.
        pendingCore?.gracefulDispose()
        pendingCore = null
        core?.gracefulDispose()
        core = null

        val transport = config.overrides.transport
            ?: ShSqsTransport(
                queueUrl = config.endpoint,
                debugEnabled = config.debugRouting?.let {
                    it.enabled ?: (Build.VERSION.CODENAME != "REL")
                } ?: false,
                debugEndpoint = config.debugRouting?.debugEndpoint,
                logger = logger,
            )
        val sessionStore = config.overrides.sessionStore
            ?: SharedPreferencesSessionStore(appContext, config.sessionLifetimeMs)
        val trailStore = config.overrides.trailStore
            ?: SharedPreferencesTrailStore(appContext)
        val eventQueue = config.overrides.eventQueue
            ?: InMemoryEventQueue(config.queueSettings.maxBatchSize)
        val browserInfoProvider = config.overrides.browserInfoProvider
            ?: AndroidBrowserInfoProvider()
        val timestampProvider = config.overrides.timestampProvider
            ?: SystemTimestampProvider()

        val newCore = SearchCollectorCore(
            transport = transport,
            sessionStore = sessionStore,
            trailStore = trailStore,
            eventQueue = eventQueue,
            browserInfoProvider = browserInfoProvider,
            timestampProvider = timestampProvider,
            channel = config.channel,
            maxBatchSize = config.queueSettings.maxBatchSize,
            batchIntervalMs = config.queueSettings.batchIntervalMs,
            logger = config.logger ?: consoleLogger,
            logLevel = config.logLevel,
        )

        val debugToken = config.debugRouting?.debugToken
        if (debugToken != null) {
            newCore.debugSessionToken = debugToken
            // enabled=false means "force production" — honour it even when a token is present
            if (config.debugRouting?.enabled != false) {
                (transport as? DebugCapable)?.setDebugActive(true)
            }
        }

        // Drain pending actions while core==null. core is assigned at the END of replay
        // so that live events arriving during replay buffer into pendingActions and carry
        // their own context snapshot — they are not affected by per-action context mutations.
        val pending = drainPendingActions()
        if (pending.isNotEmpty()) {
            val useOriginal = config.bufferedEventsTimestamp == BufferedEventsTimestamp.ORIGINAL
            pendingCore = newCore
            newCore.launch {
                replay(pending, useOriginal, newCore)

                // Activate only if reset()/configure() hasn't displaced this replay
                if (pendingCore === newCore) {
                    core = newCore
                    pendingCore = null

                    // Drain events that buffered in pendingActions while replay was running
                    val lateArrivals = drainPendingActions()
                    replay(lateArrivals, useOriginal, newCore)
                }
            }
        } else {
            core = newCore
        }
    }

    private suspend fun replay(
        actions: List<PendingAction>,
        useOriginal: Boolean,
        newCore: SearchCollectorCore
    ) {
        if (actions.isNotEmpty()) {
            logger.debug("Replaying ${actions.size} buffered pre-configure event(s)", "useOriginalTimestamp=$useOriginal")
        }
        for (action in actions) {
            val ts = if (useOriginal) action.timestamp else System.currentTimeMillis()
            runCatching { action.block(newCore, ts, action.url, action.referrer) }
                .onFailure { err -> logger.error("Replay error — pre-configure event lost", err) }
        }
    }

    /**
     * Update the current screen navigation context. Call whenever the user navigates to a new
     * screen. [url] is the current screen identifier (e.g. a deep-link path or screen name) and
     * [referrer] is the previous screen — not HTTP URLs. Safe to call before or after [configure].
     * Each tracking call snapshots the current context atomically, so per-event attribution is
     * correct even when navigation happens between calls.
     */
    @JvmStatic
    @JvmOverloads
    fun setNavContext(url: String, referrer: String = "") {
        logger.debug("Nav context updated", "url=$url referrer=$referrer")
        cachedContext.set(url to referrer)
    }

    /**
     * Disable tracking. Clears any buffered pre-configure events and discards all subsequent
     * tracking calls until [configure] is called. Events already in the queue before [disable]
     * was called continue to flush normally.
     */
    @JvmStatic
    fun disable() {
        isDisabled = true
        pendingActions.clear()
        logger.info("SearchCollector disabled — pending events cleared, further calls ignored until configure()")
    }

    /**
     * Discard all buffered pre-configure events without affecting the enabled/disabled state.
     * Safe to call before [configure], while disabled, or while active.
     */
    @JvmStatic
    fun clearPendingActions() {
        pendingActions.clear()
    }

    /** Send a browser event with device info. Call once on app/screen start. */
    @JvmStatic
    fun initialize() = fireAndForget { core, ts, url, ref -> core.trackBrowser(ts, url, ref) }

    /**
     * Track the query as the user types. Debounce recommended.
     * @param keywords Current search input value
     */
    @JvmStatic
    fun trackInstantSearch(keywords: String) =
        fireAndForget { core, ts, url, ref -> core.trackInstantSearch(keywords, ts, url, ref) }

    /**
     * Track a search query that was explicitly submitted.
     * @param keywords The submitted search query
     */
    @JvmStatic
    fun trackFiredSearch(keywords: String) =
        fireAndForget { core, ts, url, ref -> core.trackFiredSearch(keywords, ts, url, ref) }

    /**
     * Track a click on a suggest/autocomplete search term.
     * @param keywords The selected suggestion
     * @param prefix The typed prefix that triggered the suggestion
     * @param position 0-based position in the suggestion list
     */
    @JvmStatic
    fun trackSuggestClick(keywords: String, prefix: String, position: Int) =
        fireAndForget { core, ts, url, ref ->
            core.trackSuggestClick(keywords, prefix, position, ts, url, ref)
        }

    /**
     * Track a click on a product shown in autocomplete/suggest results.
     * @param keywords The suggestion term
     * @param prefix The typed prefix
     * @param position 0-based position in the suggest list
     * @param productId ID of the clicked product
     */
    @JvmStatic
    fun trackSuggestProductClick(
        keywords: String,
        prefix: String,
        position: Int,
        productId: String
    ) =
        fireAndForget { core, ts, url, ref ->
            core.trackSuggestProductClick(keywords, prefix, position, productId, ts, url, ref)
        }

    /**
     * Track a completed search with its result count.
     * @param keywords The search query
     * @param count Number of results returned
     * @param action Search action type (default: SEARCH)
     */
    @JvmStatic
    @JvmOverloads
    fun trackSearch(keywords: String, count: Int, action: SearchAction = SearchAction.SEARCH) =
        fireAndForget { core, ts, url, ref ->
            core.trackSearch(
                keywords,
                count,
                action,
                ts,
                url,
                ref
            )
        }

    /**
     * Track a search that resulted in a redirect.
     * @param keywords The search query that triggered the redirect
     * @param resultCount Number of results that would have been shown
     */
    @JvmStatic
    fun trackRedirect(keywords: String, resultCount: Int) =
        fireAndForget { core, ts, url, ref ->
            core.trackRedirect(
                keywords,
                resultCount,
                ts,
                url,
                ref
            )
        }

    /**
     * Track which products were visible on a results page.
     * @param keywords The search query that produced these results
     * @param products List of displayed products with id and 0-based position
     */
    @JvmStatic
    fun trackImpression(keywords: String, products: List<ProductPosition>) =
        fireAndForget { core, ts, url, ref ->
            core.trackImpression(
                keywords,
                products,
                ts,
                url,
                ref
            )
        }

    /**
     * Track a click on a product in search results. Automatically registers a search trail.
     * @param productId ID of the clicked product
     * @param position 0-based position in the results list
     * @param keywords The search query that led to this result
     */
    @JvmStatic
    fun trackProductClick(productId: String, position: Int, keywords: String) =
        fireAndForget { core, ts, url, ref ->
            core.trackProductClick(productId, position, keywords, ts, url, ref)
        }

    /**
     * Track a click on an associated/related product (e.g. on a PDP). Automatically registers an associated trail.
     * @param productId ID of the clicked product
     * @param position 0-based position in the associated products list
     * @param keywords The search query that led to the original product
     */
    @JvmStatic
    fun trackAssociatedProductClick(productId: String, position: Int, keywords: String) =
        fireAndForget { core, ts, url, ref ->
            core.trackAssociatedProductClick(productId, position, keywords, ts, url, ref)
        }

    /**
     * Track a product being added to the basket. Resolves the search query from the trail.
     * @param productId ID of the added product
     * @param price Price of the product at time of adding
     */
    @JvmStatic
    fun trackBasket(productId: String, price: Double) =
        fireAndForget { core, ts, url, ref -> core.trackBasket(productId, price, ts, url, ref) }

    /**
     * Track a completed checkout. One event is sent per product.
     * @param products List of purchased products with id, price and quantity
     */
    @JvmStatic
    fun trackCheckout(products: List<CheckoutProduct>) =
        fireAndForget { core, ts, url, ref -> core.trackCheckout(products, ts, url, ref) }

    /**
     * Manually register a search trail for a product.
     * @param key Product ID
     * @param query The search query string (e.g. "\$s=jeans/")
     * @param trailType Trail type (default: MAIN)
     */
    @JvmStatic
    @JvmOverloads
    fun registerTrail(key: String, query: String, trailType: TrailType = TrailType.MAIN) =
        fireAndForget { core, _, _, _ -> core.registerTrail(key, query, trailType) }

    /**
     * Copy a search trail from one product to another (e.g. when a variant is selected on PDP).
     * @param fromProductId Source product ID
     * @param toProductId Target product ID
     */
    @JvmStatic
    fun copyTrail(fromProductId: String, toProductId: String) =
        fireAndForget { core, _, _, _ -> core.copyTrail(fromProductId, toProductId) }

    /**
     * Activate a debug session. Sets [token] as the session ID for all subsequent events and
     * routes them to the /debug endpoint. Must be called after [configure].
     *
     * Typical usage: extract the token from a deep-link Intent with [extractDebugToken], then
     * pass it here. Token expires server-side after 12 hours of inactivity.
     *
     * When a custom [DependencyOverrides.transport] is in use, session override still applies
     * but debug endpoint routing is the custom transport's responsibility.
     *
     * @throws IllegalStateException if called before [configure].
     */
    @JvmStatic
    fun activateDebugSession(token: String) {
        val c = core ?: throw IllegalStateException(
            "SearchCollector.activateDebugSession() called before configure(). Call configure() first."
        )
        c.launch { c.activateDebugSession(token) }
    }

    /**
     * Deactivate the current debug session. Restores the normal session ID and production
     * endpoint routing. Must be called after [configure].
     *
     * @throws IllegalStateException if called before [configure].
     */
    @JvmStatic
    fun deactivateDebugSession() {
        val c = core ?: throw IllegalStateException(
            "SearchCollector.deactivateDebugSession() called before configure(). Call configure() first."
        )
        c.launch { c.deactivateDebugSession() }
    }

    /**
     * Extract a debug session token from a deep-link [intent].
     * Returns the value of the [DEBUG_TOKEN_PARAM] query parameter, or null if absent.
     * Pure function — no side effects, no state changes.
     */
    @JvmStatic
    fun extractDebugToken(intent: android.content.Intent): String? =
        intent.data?.getQueryParameter(DEBUG_TOKEN_PARAM)

    /** Force-send all queued events immediately. Suspends until the flush is complete. */
    @JvmStatic
    suspend fun flush() {
        val c = core
        if (c == null) {
            logger.debug("flush() called before configure() — no-op")
            return
        }
        c.flush()
    }

    /**
     * Fire-and-forget flush. Enqueues a flush on the core's internal scope and returns
     * immediately. No-op if [configure] has not been called.
     */
    @JvmStatic
    fun flushAsync() {
        val c = core
        if (c == null) {
            logger.debug("flushAsync() called before configure() — no-op")
            return
        }
        c.launch { c.flush() }
    }

    /**
     * Dispose the current instance and clear all pending state.
     * @param clearStorage If true, also clears all SharedPreferences written by the library
     * (session ID, trails, persisted event queue). Use on logout or for GDPR erasure.
     */
    @JvmStatic
    @JvmOverloads
    fun reset(clearStorage: Boolean = false) {
        logger.info("Resetting SearchCollector", "clearStorage=$clearStorage")
        isDisabled = false
        cachedContext.set("" to "")
        pendingCore?.dispose()
        pendingCore = null
        core?.dispose()
        core = null
        pendingActions.clear()
        val ctx = appContext
        appContext = null
        if (clearStorage) {
            ctx?.let { c ->
                c.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE).edit().clear()
                    .commit()
                c.getSharedPreferences(TRAIL_PREFS_NAME, Context.MODE_PRIVATE).edit().clear()
                    .commit()
                c.getSharedPreferences(BASE_PREFS_KEY, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    private fun drainPendingActions(): List<PendingAction> {
        val drained = mutableListOf<PendingAction>()
        while (true) drained.add(pendingActions.poll() ?: break)
        return drained
    }

    private fun fireAndForget(block: suspend (SearchCollectorCore, Long, String, String) -> Unit) {
        if (isDisabled) {
            logger.debug("Call ignored — SearchCollector.disable() is active")
            return
        }
        val ts = System.currentTimeMillis()
        val (url, ref) = cachedContext.get()
        val currentCore = core
        if (currentCore != null) {
            currentCore.launch { block(currentCore, ts, url, ref) }
        } else {
            pendingActions.add(PendingAction(ts, url, ref, block))
            logger.debug("Buffering pre-configure event", "pending=${pendingActions.size}/$maxPendingActions")
            if (pendingActions.size > maxPendingActions) {
                pendingActions.poll()
                logger.warn("Pre-configure buffer full (max=$maxPendingActions) — oldest buffered event dropped")
            }
        }
    }
}

internal data class PendingAction(
    val timestamp: Long,
    val url: String,
    val referrer: String,
    val block: suspend (SearchCollectorCore, Long, String, String) -> Unit,
)
