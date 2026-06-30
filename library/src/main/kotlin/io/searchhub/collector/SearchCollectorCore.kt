package io.searchhub.collector

import io.searchhub.collector.interfaces.*
import io.searchhub.collector.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SearchCollectorCore(
    private val transport: Transport,
    private val sessionStore: SessionStore,
    private val trailStore: TrailStore,
    private val eventQueue: EventQueue,
    private val contextProvider: ContextProvider,
    private val timestampProvider: TimestampProvider,
    private val channel: String,
    private val maxBatchSize: Int,
    private val batchIntervalMs: Long,
    logger: Logger,
    logLevel: LogLevel,
) {
    private val logger: Logger = createFilteredLogger(logger, logLevel)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    private var autoFlushJob: Job? = null
    private val debugCapable: DebugCapable? = transport as? DebugCapable

    @Volatile var debugSessionToken: String? = null

    init {
        startAutoFlush()
    }

    fun setContext(url: String, referrer: String) {
        contextProvider.setContext(url, referrer)
    }

    fun logReplayError(err: Throwable) {
        logger.error("Replay error — pre-configure event lost", err)
    }

    internal fun launch(block: suspend () -> Unit): Job = scope.launch {
        runCatching { block() }.onFailure { err ->
            this@SearchCollectorCore.logger.error("Tracking error", err)
        }
    }

    suspend fun trackBrowser(timestampMs: Long = timestampProvider.now()) {
        val agent = contextProvider.getUserAgent()
        val touch = contextProvider.isTouchDevice()
        val lang = contextProvider.getLanguage()
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.Browser(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                agent = agent,
                touch = touch,
                lang = lang,
            )
        )
    }

    suspend fun trackInstantSearch(keywords: String, timestampMs: Long = timestampProvider.now()) {
        val query = formatQuery(keywords)
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.InstantSearch(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                keywords = keywords,
                query = query,
            )
        )
    }

    suspend fun trackFiredSearch(keywords: String, timestampMs: Long = timestampProvider.now()) {
        val query = formatQuery(keywords)
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.FiredSearch(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                keywords = keywords,
                query = query,
            )
        )
    }

    suspend fun trackSuggestClick(keywords: String, prefix: String, position: Int, timestampMs: Long = timestampProvider.now()) {
        val query = formatQuery(keywords)
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.SuggestSearch(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                keywords = keywords,
                query = query,
                data = SuggestData(prefix = prefix, position = position),
            )
        )
    }

    suspend fun trackSuggestProductClick(keywords: String, prefix: String, position: Int, productId: String, timestampMs: Long = timestampProvider.now()) {
        val query = formatQuery(keywords)
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.SuggestProductClick(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                keywords = keywords,
                query = query,
                data = SuggestProductData(prefix = prefix, position = position, id = productId),
            )
        )
    }

    suspend fun trackSearch(keywords: String, count: Int, action: SearchAction, timestampMs: Long = timestampProvider.now()) {
        val query = formatQuery(keywords)
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.Search(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                keywords = keywords,
                query = query,
                count = count,
                action = action,
            )
        )
    }

    suspend fun trackRedirect(keywords: String, resultCount: Int, timestampMs: Long = timestampProvider.now()) {
        val query = formatQuery(keywords)
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.Redirect(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                keywords = keywords,
                query = query,
                resultCount = resultCount,
            )
        )
    }

    suspend fun trackImpression(keywords: String, products: List<ProductPosition>, timestampMs: Long = timestampProvider.now()) {
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.Impression(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                query = formatQuery(keywords),
                data = products,
            )
        )
    }

    suspend fun trackProductClick(productId: String, position: Int, keywords: String, timestampMs: Long = timestampProvider.now()) {
        val query = formatQuery(keywords)
        trailStore.register(productId, query, TrailType.MAIN)
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.Product(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                query = query,
                id = productId,
                position = position,
            )
        )
    }

    suspend fun trackAssociatedProductClick(productId: String, position: Int, keywords: String, timestampMs: Long = timestampProvider.now()) {
        val query = formatQuery(keywords)
        trailStore.register(productId, query, TrailType.ASSOCIATED)
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.AssociatedProduct(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                query = query,
                id = productId,
                position = position,
                trailType = TrailType.ASSOCIATED,
            )
        )
    }

    suspend fun trackBasket(productId: String, price: Double, timestampMs: Long = timestampProvider.now()) {
        val trail = trailStore.get(productId)
        val common = getCommonProperties(timestampMs)
        enqueue(
            SearchCollectorEvent.Basket(
                timestamp = common.timestamp,
                session = common.session,
                channel = common.channel,
                url = common.url,
                ref = common.ref,
                query = trail?.query ?: "",
                id = productId,
                price = price,
                trailType = trail?.type,
            )
        )
    }

    suspend fun trackCheckout(products: List<CheckoutProduct>, timestampMs: Long = timestampProvider.now()) {
        for (product in products) {
            val trail = trailStore.get(product.id)
            val common = getCommonProperties(timestampMs)
            enqueue(
                SearchCollectorEvent.Checkout(
                    timestamp = common.timestamp,
                    session = common.session,
                    channel = common.channel,
                    url = common.url,
                    ref = common.ref,
                    query = trail?.query ?: "",
                    id = product.id,
                    price = product.price,
                    quantity = product.quantity,
                    trailType = trail?.type,
                )
            )
        }
    }

    suspend fun registerTrail(key: String, query: String, trailType: TrailType) {
        trailStore.register(key, query, trailType)
    }

    suspend fun copyTrail(fromProductId: String, toProductId: String) {
        val trail = trailStore.get(fromProductId) ?: return
        trailStore.register(toProductId, trail.query, trail.type)
    }

    suspend fun flush() {
        if (!flushMutex.tryLock()) {
            // A flush is already in progress — wait for it
            flushMutex.withLock { }
            return
        }
        try {
            eventQueue.transactionalDrain { events ->
                if (events.isEmpty()) return@transactionalDrain
                val batches = createBatches(events)
                batches.map { batch ->
                    scope.async { transport.send(batch) }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Flush failed", e)
            throw e
        } finally {
            flushMutex.unlock()
        }
    }

    fun dispose() {
        autoFlushJob?.cancel()
        scope.cancel()
    }

    internal suspend fun activateDebugSession(token: String) {
        flushMutex.withLock {
            debugSessionToken = token
            if (debugCapable != null) {
                debugCapable.setDebugActive(true)
            } else {
                logger.warn("activateDebugSession: custom transport in use — debug endpoint routing is the caller's responsibility")
            }
        }
    }

    internal suspend fun deactivateDebugSession() {
        flushMutex.withLock {
            debugSessionToken = null
            debugCapable?.setDebugActive(false)
        }
    }

    private suspend fun getCommonProperties(timestampMs: Long = timestampProvider.now()): CommonProperties {
        sessionStore.touch()
        val session = debugSessionToken ?: sessionStore.getOrCreateSessionId()
        val url = contextProvider.getCurrentUrl()
        val ref = contextProvider.getReferrer()
        return CommonProperties(
            timestamp = timestampMs,
            session = session,
            channel = channel,
            url = url,
            ref = ref,
        )
    }

    private fun enqueue(event: SearchCollectorEvent) {
        val shouldFlush = eventQueue.push(event)
        if (shouldFlush) {
            scope.launch {
                runCatching { flush() }.onFailure { err ->
                    logger.error("Auto-flush on queue full failed", err)
                }
            }
        }
    }

    private fun startAutoFlush() {
        autoFlushJob = scope.launch {
            while (isActive) {
                delay(batchIntervalMs)
                runCatching { flush() }.onFailure { err ->
                    logger.error("Auto-flush failed", err)
                }
            }
        }
    }

    private fun formatQuery(keywords: String): String = "\$s=${android.net.Uri.encode(keywords)}/"

    private fun createBatches(events: List<SearchCollectorEvent>): List<List<SearchCollectorEvent>> {
        val maxSizeBytes = 10 * 1024
        val batches = mutableListOf<List<SearchCollectorEvent>>()
        var current = mutableListOf<SearchCollectorEvent>()

        for (event in events) {
            val test = current + event
            if (test.size > maxBatchSize || estimateBatchSize(test) > maxSizeBytes) {
                if (current.isNotEmpty()) batches.add(current)
                current = mutableListOf(event)
            } else {
                current.add(event)
            }
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    private fun estimateBatchSize(batch: List<SearchCollectorEvent>): Int {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val jsonStr = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SearchCollectorEvent.serializer()), batch)
        val utf8Bytes = jsonStr.toByteArray(Charsets.UTF_8).size
        return (utf8Bytes * 4 + 2) / 3  // base64 overhead ~33%
    }

    private data class CommonProperties(
        val timestamp: Long,
        val session: String,
        val channel: String,
        val url: String,
        val ref: String,
    )
}
