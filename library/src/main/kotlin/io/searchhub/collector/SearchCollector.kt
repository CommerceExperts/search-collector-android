package io.searchhub.collector

import android.content.Context
import android.os.Build
import io.searchhub.collector.SearchCollector.DEBUG_TOKEN_PARAM
import io.searchhub.collector.SearchCollector.configure
import io.searchhub.collector.SearchCollector.extractDebugToken
import io.searchhub.collector.SearchCollector.flush
import io.searchhub.collector.impl.context.AndroidContextProvider
import io.searchhub.collector.impl.queue.BASE_PREFS_KEY
import io.searchhub.collector.impl.queue.InMemoryEventQueue
import io.searchhub.collector.impl.session.SharedPreferencesSessionStore
import io.searchhub.collector.impl.timestamp.SystemTimestampProvider
import io.searchhub.collector.impl.trail.SharedPreferencesTrailStore
import io.searchhub.collector.impl.transport.ShSqsTransport
import io.searchhub.collector.interfaces.DebugCapable
import io.searchhub.collector.interfaces.consoleLogger
import io.searchhub.collector.model.BufferedEventsTimestamp
import io.searchhub.collector.model.CheckoutProduct
import io.searchhub.collector.model.DependencyOverrides
import io.searchhub.collector.model.ProductPosition
import io.searchhub.collector.model.SearchAction
import io.searchhub.collector.model.SearchCollectorConfig
import io.searchhub.collector.model.TrailType
import java.util.concurrent.ConcurrentLinkedQueue
import io.searchhub.collector.impl.session.PREFS_NAME as SESSION_PREFS_NAME
import io.searchhub.collector.impl.trail.PREFS_NAME as TRAIL_PREFS_NAME

/**
 * Singleton facade for the SearchHub Search Collector.
 *
 * Usage:
 * 1. Call [configure] once on app start (e.g. in Application.onCreate).
 * 2. Optionally call [setContext] whenever the user navigates to a new screen.
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

    @Volatile
    private var cachedUrl: String = ""

    @Volatile
    private var cachedReferrer: String = ""

    private val pendingActions = ConcurrentLinkedQueue<PendingAction>()

    /**
     * Configure and initialize the collector. Must be called before events can be sent.
     * Safe to call multiple times — disposes the previous instance first.
     * Re-enables the collector if [disable] was previously called.
     */
    @JvmStatic
    fun configure(config: SearchCollectorConfig) {
        isDisabled = false
        maxPendingActions = config.queueSettings.maxPendingActions

        val appContext = config.context.applicationContext
        this.appContext = appContext

        core?.dispose()

        val transport = config.overrides.transport
            ?: ShSqsTransport(
                queueUrl = config.endpoint,
                debugEnabled = config.debugRouting?.let { it.enabled ?: (Build.VERSION.CODENAME != "REL") } ?: false,
                debugEndpoint = config.debugRouting?.debugEndpoint,
            )
        val sessionStore = config.overrides.sessionStore
            ?: SharedPreferencesSessionStore(appContext, config.sessionLifetimeMs)
        val trailStore = config.overrides.trailStore
            ?: SharedPreferencesTrailStore(appContext)
        val eventQueue = config.overrides.eventQueue
            ?: InMemoryEventQueue(config.queueSettings.maxBatchSize)
        val contextProvider = config.overrides.contextProvider
            ?: AndroidContextProvider(appContext)
        val timestampProvider = config.overrides.timestampProvider
            ?: SystemTimestampProvider()

        contextProvider.setContext(cachedUrl, cachedReferrer)

        val newCore = SearchCollectorCore(
            transport = transport,
            sessionStore = sessionStore,
            trailStore = trailStore,
            eventQueue = eventQueue,
            contextProvider = contextProvider,
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

        core = newCore
        newCore.setContext(cachedUrl, cachedReferrer)

        // Replay buffered calls made before configure()
        val pending = mutableListOf<PendingAction>()
        while (true) {
            pending.add(pendingActions.poll() ?: break)
        }
        if (pending.isNotEmpty()) {
            val useOriginal = config.bufferedEventsTimestamp == BufferedEventsTimestamp.ORIGINAL
            newCore.launch {
                for (action in pending) {
                    newCore.setContext(action.url, action.referrer)
                    val ts = if (useOriginal) action.timestamp else System.currentTimeMillis()
                    runCatching { action.block(newCore, ts) }
                        .onFailure { err -> newCore.logReplayError(err) }
                }
                newCore.setContext(cachedUrl, cachedReferrer)
            }
        }
    }

    /**
     * Update the current screen context. In native Android apps, [url] is the current screen
     * identifier (e.g. a deep-link path or screen name) and [referrer] is the previous screen —
     * not HTTP URLs. Always caches the values regardless of collector state; also delegates to
     * the active core when [configure] has been called.
     */
    @JvmStatic
    @JvmOverloads
    fun setContext(url: String, referrer: String = "") {
        cachedUrl = url
        cachedReferrer = referrer
        core?.setContext(url, referrer)
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
    fun initialize() = fireAndForget { core, ts -> core.trackBrowser(ts) }

    /**
     * Track the query as the user types. Debounce recommended.
     * @param keywords Current search input value
     */
    @JvmStatic
    fun trackInstantSearch(keywords: String) =
        fireAndForget { core, ts -> core.trackInstantSearch(keywords, ts) }

    /**
     * Track a search query that was explicitly submitted.
     * @param keywords The submitted search query
     */
    @JvmStatic
    fun trackFiredSearch(keywords: String) =
        fireAndForget { core, ts -> core.trackFiredSearch(keywords, ts) }

    /**
     * Track a click on a suggest/autocomplete search term.
     * @param keywords The selected suggestion
     * @param prefix The typed prefix that triggered the suggestion
     * @param position 0-based position in the suggestion list
     */
    @JvmStatic
    fun trackSuggestClick(keywords: String, prefix: String, position: Int) =
        fireAndForget { core, ts -> core.trackSuggestClick(keywords, prefix, position, ts) }

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
        fireAndForget { core, ts ->
            core.trackSuggestProductClick(
                keywords,
                prefix,
                position,
                productId,
                ts
            )
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
        fireAndForget { core, ts -> core.trackSearch(keywords, count, action, ts) }

    /**
     * Track a search that resulted in a redirect.
     * @param keywords The search query that triggered the redirect
     * @param resultCount Number of results that would have been shown
     */
    @JvmStatic
    fun trackRedirect(keywords: String, resultCount: Int) =
        fireAndForget { core, ts -> core.trackRedirect(keywords, resultCount, ts) }

    /**
     * Track which products were visible on a results page.
     * @param keywords The search query that produced these results
     * @param products List of displayed products with id and 0-based position
     */
    @JvmStatic
    fun trackImpression(keywords: String, products: List<ProductPosition>) =
        fireAndForget { core, ts -> core.trackImpression(keywords, products, ts) }

    /**
     * Track a click on a product in search results. Automatically registers a search trail.
     * @param productId ID of the clicked product
     * @param position 0-based position in the results list
     * @param keywords The search query that led to this result
     */
    @JvmStatic
    fun trackProductClick(productId: String, position: Int, keywords: String) =
        fireAndForget { core, ts -> core.trackProductClick(productId, position, keywords, ts) }

    /**
     * Track a click on an associated/related product (e.g. on a PDP). Automatically registers an associated trail.
     * @param productId ID of the clicked product
     * @param position 0-based position in the associated products list
     * @param keywords The search query that led to the original product
     */
    @JvmStatic
    fun trackAssociatedProductClick(productId: String, position: Int, keywords: String) =
        fireAndForget { core, ts ->
            core.trackAssociatedProductClick(
                productId,
                position,
                keywords,
                ts
            )
        }

    /**
     * Track a product being added to the basket. Resolves the search query from the trail.
     * @param productId ID of the added product
     * @param price Price of the product at time of adding
     */
    @JvmStatic
    fun trackBasket(productId: String, price: Double) =
        fireAndForget { core, ts -> core.trackBasket(productId, price, ts) }

    /**
     * Track a completed checkout. One event is sent per product.
     * @param products List of purchased products with id, price and quantity
     */
    @JvmStatic
    fun trackCheckout(products: List<CheckoutProduct>) =
        fireAndForget { core, ts -> core.trackCheckout(products, ts) }

    /**
     * Manually register a search trail for a product.
     * @param key Product ID
     * @param query The search query string (e.g. "\$s=jeans/")
     * @param trailType Trail type (default: MAIN)
     */
    @JvmStatic
    @JvmOverloads
    fun registerTrail(key: String, query: String, trailType: TrailType = TrailType.MAIN) =
        fireAndForget { core, _ -> core.registerTrail(key, query, trailType) }

    /**
     * Copy a search trail from one product to another (e.g. when a variant is selected on PDP).
     * @param fromProductId Source product ID
     * @param toProductId Target product ID
     */
    @JvmStatic
    fun copyTrail(fromProductId: String, toProductId: String) =
        fireAndForget { core, _ -> core.copyTrail(fromProductId, toProductId) }

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
        core?.flush()
    }

    /**
     * Fire-and-forget flush. Enqueues a flush on the core's internal scope and returns
     * immediately. No-op if [configure] has not been called.
     */
    @JvmStatic
    fun flushAsync() {
        val c = core ?: return
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
        isDisabled = false
        cachedUrl = ""
        cachedReferrer = ""
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

    private fun fireAndForget(block: suspend (SearchCollectorCore, Long) -> Unit) {
        if (isDisabled) return
        val ts = System.currentTimeMillis()
        val currentCore = core
        if (currentCore != null) {
            currentCore.launch { block(currentCore, ts) }
        } else {
            pendingActions.add(PendingAction(ts, cachedUrl, cachedReferrer, block))
            if (pendingActions.size > maxPendingActions) pendingActions.poll()
        }
    }
}

internal data class PendingAction(
    val timestamp: Long,
    val url: String,
    val referrer: String,
    val block: suspend (SearchCollectorCore, Long) -> Unit,
)
