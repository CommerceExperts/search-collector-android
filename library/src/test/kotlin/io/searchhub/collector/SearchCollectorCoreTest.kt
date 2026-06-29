package io.searchhub.collector

import io.searchhub.collector.impl.queue.InMemoryEventQueue
import io.searchhub.collector.impl.session.InMemorySessionStore
import io.searchhub.collector.impl.timestamp.SystemTimestampProvider
import io.searchhub.collector.impl.trail.InMemoryTrailStore
import io.searchhub.collector.impl.transport.ShSqsTransport
import io.searchhub.collector.interfaces.ContextProvider
import io.searchhub.collector.interfaces.SessionStore
import io.searchhub.collector.interfaces.TrailStore
import io.searchhub.collector.interfaces.Transport
import io.searchhub.collector.interfaces.silentLogger
import io.searchhub.collector.model.CheckoutProduct
import io.searchhub.collector.model.LogLevel
import io.searchhub.collector.model.ProductPosition
import io.searchhub.collector.model.SearchAction
import io.searchhub.collector.model.SearchCollectorEvent
import io.searchhub.collector.model.TrailType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchCollectorCoreTest {

    private lateinit var sentBatches: MutableList<List<SearchCollectorEvent>>
    private lateinit var queue: InMemoryEventQueue
    private lateinit var core: SearchCollectorCore

    private val fakeTransport = object : Transport {
        override suspend fun send(events: List<SearchCollectorEvent>) {
            sentBatches.add(events)
        }
    }

    private val fakeContext = object : ContextProvider {
        override suspend fun getCurrentUrl() = "https://example.com/search"
        override suspend fun getReferrer() = ""
        override suspend fun getUserAgent() = "TestAgent/1.0"
        override suspend fun isTouchDevice() = true
        override suspend fun getLanguage() = "de-DE"
    }

    private fun makeCore(
        trailStore: TrailStore = InMemoryTrailStore(),
        eventQueue: InMemoryEventQueue = queue,
        transport: Transport = fakeTransport,
        sessionStore: SessionStore = InMemorySessionStore(),
        contextProvider: ContextProvider = fakeContext,
    ) = SearchCollectorCore(
        transport = transport,
        sessionStore = sessionStore,
        trailStore = trailStore,
        eventQueue = eventQueue,
        contextProvider = contextProvider,
        timestampProvider = SystemTimestampProvider(),
        channel = "de",
        maxBatchSize = 10,
        batchIntervalMs = 60_000L,
        logger = silentLogger,
        logLevel = LogLevel.SILENT,
    )

    @Before
    fun setup() {
        sentBatches = mutableListOf()
        queue = InMemoryEventQueue(maxBatchSize = 10)
        core = makeCore()
    }

    @Test
    fun `trackSearch enqueues a search event`() = runTest {
        core.trackSearch("jeans", 42, SearchAction.SEARCH)
        val events = queue.drain()
        assertEquals(1, events.size)
        val event = events[0] as SearchCollectorEvent.Search
        assertEquals("\$s=jeans/", event.query)
        assertEquals(42, event.count)
        assertEquals(SearchAction.SEARCH, event.action)
        assertEquals("de", event.channel)
    }

    @Test
    fun `trackProductClick registers trail and enqueues product event`() = runTest {
        val trailStore = InMemoryTrailStore()
        val coreWithTrail = makeCore(trailStore = trailStore)
        coreWithTrail.trackProductClick("prod-123", 0, "blue jeans")

        val trail = trailStore.get("prod-123")
        assertNotNull(trail)
        assertEquals("\$s=blue%20jeans/", trail!!.query)
        assertEquals(TrailType.MAIN, trail.type)

        val event = queue.drain()[0] as SearchCollectorEvent.Product
        assertEquals("prod-123", event.id)
        coreWithTrail.dispose()
    }

    @Test
    fun `trackBasket resolves query from trail`() = runTest {
        val trailStore = InMemoryTrailStore()
        trailStore.register("prod-456", "\$s=sneaker/", TrailType.MAIN)
        val coreWithTrail = makeCore(trailStore = trailStore)

        coreWithTrail.trackBasket("prod-456", 99.99)
        val event = queue.drain()[0] as SearchCollectorEvent.Basket
        assertEquals("\$s=sneaker/", event.query)
        assertEquals(99.99, event.price, 0.001)
        coreWithTrail.dispose()
    }

    @Test
    fun `flush sends all queued events and clears queue`() = runTest {
        core.trackFiredSearch("test")
        core.trackSearch("test", 10, SearchAction.SEARCH)
        core.flush()
        assertEquals(1, sentBatches.size)
        assertEquals(2, sentBatches[0].size)
        assertEquals(0, queue.drain().size)
    }

    @Test
    fun `flush batches by maxBatchSize`() = runTest {
        val smallQueue =
            InMemoryEventQueue(maxBatchSize = 100) // large enough to never trigger auto-flush
        val smallCore = SearchCollectorCore(
            transport = fakeTransport,
            sessionStore = InMemorySessionStore(),
            trailStore = InMemoryTrailStore(),
            eventQueue = smallQueue,
            contextProvider = fakeContext,
            timestampProvider = SystemTimestampProvider(),
            channel = "de",
            maxBatchSize = 2,
            batchIntervalMs = 60_000L,
            logger = silentLogger,
            logLevel = LogLevel.SILENT,
        )  // explicit maxBatchSize = 2, cannot use makeCore()
        repeat(5) { smallCore.trackInstantSearch("query$it") }
        smallCore.flush()
        // 5 events, max batch size 2 → 3 batches
        assertEquals(3, sentBatches.size)
        smallCore.dispose()
    }

    @Test
    fun `copyTrail copies query to new product`() = runTest {
        val trailStore = InMemoryTrailStore()
        trailStore.register("prod-A", "\$s=original/", TrailType.MAIN)
        val coreWithTrail = makeCore(trailStore = trailStore)
        coreWithTrail.copyTrail("prod-A", "prod-B")
        val trail = trailStore.get("prod-B")
        assertNotNull(trail)
        assertEquals("\$s=original/", trail!!.query)
        assertEquals(TrailType.MAIN, trail.type)
        coreWithTrail.dispose()
    }

    @Test
    fun `explicit timestampMs is used when provided`() = runTest {
        core.trackFiredSearch("hello", timestampMs = 123456789L)
        val event = queue.drain()[0]
        assertEquals(123456789L, event.timestamp)
    }

    @Test
    fun `trackInstantSearch enqueues an InstantSearch event`() = runTest {
        core.trackInstantSearch("jea")
        val event = queue.drain()[0] as SearchCollectorEvent.InstantSearch
        assertEquals("\$s=jea/", event.query)
        assertEquals("de", event.channel)
    }

    @Test
    fun `trackFiredSearch enqueues a FiredSearch event`() = runTest {
        core.trackFiredSearch("jeans")
        val event = queue.drain()[0] as SearchCollectorEvent.FiredSearch
        assertEquals("\$s=jeans/", event.query)
        assertEquals("de", event.channel)
    }

    @Test
    fun `trackSuggestClick enqueues a SuggestSearch event`() = runTest {
        core.trackSuggestClick("jeans", "jea", 2)
        val event = queue.drain()[0] as SearchCollectorEvent.SuggestSearch
        assertEquals("\$s=jeans/", event.query)
        assertEquals("jea", event.data.prefix)
        assertEquals(2, event.data.position)
    }

    @Test
    fun `trackSuggestProductClick enqueues a SuggestProductClick event`() = runTest {
        core.trackSuggestProductClick("jeans", "jea", 1, "prod-99")
        val event = queue.drain()[0] as SearchCollectorEvent.SuggestProductClick
        assertEquals("\$s=jeans/", event.query)
        assertEquals("jea", event.data.prefix)
        assertEquals(1, event.data.position)
        assertEquals("prod-99", event.data.id)
    }

    @Test
    fun `trackRedirect enqueues a Redirect event`() = runTest {
        core.trackRedirect("sale", 0)
        val event = queue.drain()[0] as SearchCollectorEvent.Redirect
        assertEquals("\$s=sale/", event.query)
        assertEquals(0, event.resultCount)
        assertEquals("de", event.channel)
    }

    @Test
    fun `trackImpression enqueues an Impression event with correct product list`() = runTest {
        val products = listOf(ProductPosition("p1", 0), ProductPosition("p2", 1))
        core.trackImpression("jeans", products)
        val event = queue.drain()[0] as SearchCollectorEvent.Impression
        assertEquals("\$s=jeans/", event.query)
        assertEquals(2, event.data.size)
        assertEquals("p1", event.data[0].id)
        assertEquals(1, event.data[1].position)
    }

    @Test
    fun `trackAssociatedProductClick registers ASSOCIATED trail and enqueues event`() = runTest {
        val trailStore = InMemoryTrailStore()
        val coreWithTrail = makeCore(trailStore = trailStore)
        coreWithTrail.trackAssociatedProductClick("prod-77", 3, "sneakers")
        val trail = trailStore.get("prod-77")
        assertNotNull(trail)
        assertEquals(TrailType.ASSOCIATED, trail!!.type)
        val event = queue.drain()[0] as SearchCollectorEvent.AssociatedProduct
        assertEquals("prod-77", event.id)
        assertEquals(3, event.position)
        coreWithTrail.dispose()
    }

    @Test
    fun `trackCheckout emits one event per product and resolves trails`() = runTest {
        val trailStore = InMemoryTrailStore()
        trailStore.register("p1", "\$s=jeans/", TrailType.MAIN)
        val coreWithTrail = makeCore(trailStore = trailStore)
        coreWithTrail.trackCheckout(
            listOf(
                CheckoutProduct("p1", 49.99, 2),
                CheckoutProduct("p2", 9.99, 1),
            )
        )
        val events = queue.drain()
        assertEquals(2, events.size)
        val e1 = events[0] as SearchCollectorEvent.Checkout
        assertEquals("p1", e1.id)
        assertEquals("\$s=jeans/", e1.query)
        assertEquals(49.99, e1.price, 0.001)
        assertEquals(2, e1.quantity)
        val e2 = events[1] as SearchCollectorEvent.Checkout
        assertEquals("p2", e2.id)
        assertEquals("", e2.query)
        coreWithTrail.dispose()
    }

    @Test
    fun `trackBrowser enqueues a Browser event with non-empty agent and lang`() = runTest {
        core.trackBrowser()
        val event = queue.drain()[0] as SearchCollectorEvent.Browser
        assertTrue(event.agent.isNotEmpty())
        assertTrue(event.lang.isNotEmpty())
        assertEquals("de", event.channel)
        assertTrue(event.touch)
    }

    @Test
    fun `keywords with spaces are encoded as %20 in query trail`() = runTest {
        core.trackFiredSearch("blue jeans")
        val event = queue.drain()[0] as SearchCollectorEvent.FiredSearch
        assertEquals("blue jeans", event.keywords)
        assertEquals("\$s=blue%20jeans/", event.query)
    }

    // --- debug session ---

    @Test
    fun `debugSessionToken overrides session in emitted events`() = runTest {
        core.debugSessionToken = "abc123"
        core.trackFiredSearch("shoes")
        val event = queue.drain()[0]
        assertEquals("abc123", event.session)
    }

    @Test
    fun `without debugSessionToken session comes from sessionStore`() = runTest {
        val sessionStore = InMemorySessionStore()
        val c = makeCore(sessionStore = sessionStore)
        c.trackFiredSearch("shoes")
        val event = queue.drain()[0]
        assertEquals(sessionStore.getOrCreateSessionId(), event.session)
        c.dispose()
    }

    @Test
    fun `activateDebugSession sets token and enables debug routing on ShSqsTransport`() = runTest {
        val sqsTransport = ShSqsTransport(
            queueUrl = "https://sqs.example.com/123/queue",
            debugEnabled = false,
        )
        val c = makeCore(transport = sqsTransport)
        c.activateDebugSession("tok123")
        assertEquals("tok123", c.debugSessionToken)
        assertTrue(sqsTransport.activeEndpointUrl.contains("/debug/"))
        c.dispose()
    }

    @Test
    fun `deactivateDebugSession clears token and disables debug routing on ShSqsTransport`() =
        runTest {
            val sqsTransport = ShSqsTransport(
                queueUrl = "https://sqs.example.com/123/queue",
                debugEnabled = false,
            )
            val c = makeCore(transport = sqsTransport)
            c.activateDebugSession("tok123")
            c.deactivateDebugSession()
            assertNull(c.debugSessionToken)
            assertFalse(sqsTransport.activeEndpointUrl.contains("/debug/"))
            c.dispose()
        }

    @Test
    fun `activateDebugSession with custom transport sets token without crashing`() = runTest {
        // fakeTransport is not ShSqsTransport — routing is caller's responsibility
        val c = makeCore(transport = fakeTransport)
        c.activateDebugSession("tok-custom")
        assertEquals("tok-custom", c.debugSessionToken)
        c.dispose()
    }

    @Test
    fun `events after activateDebugSession carry the debug token as session`() = runTest {
        val sqsTransport = ShSqsTransport(
            queueUrl = "https://sqs.example.com/123/queue",
            debugEnabled = false,
        )
        val c = makeCore(transport = sqsTransport)
        c.activateDebugSession("debug-token")
        c.trackSearch("shoes", 5, SearchAction.SEARCH)
        val event = queue.drain()[0]
        assertEquals("debug-token", event.session)
        c.dispose()
    }

    // --- setContext ---

    @Test
    fun `setContext updates url and ref on subsequent events`() = runTest {
        val mutableContext = object : ContextProvider {
            private var url = ""
            private var ref = ""
            override suspend fun getCurrentUrl() = url
            override suspend fun getReferrer() = ref
            override suspend fun getUserAgent() = "TestAgent"
            override suspend fun isTouchDevice() = true
            override suspend fun getLanguage() = "de-DE"
            override fun setContext(url: String, referrer: String) { this.url = url; this.ref = referrer }
        }
        val c = makeCore(contextProvider = mutableContext)
        c.setContext("pdp/123", "search/results")
        c.trackFiredSearch("jeans")
        val event = queue.drain()[0] as SearchCollectorEvent.FiredSearch
        assertEquals("pdp/123", event.url)
        assertEquals("search/results", event.ref)
        c.dispose()
    }

    @Test
    fun `events after deactivateDebugSession carry normal session id`() = runTest {
        val sessionStore = InMemorySessionStore()
        val sqsTransport = ShSqsTransport(
            queueUrl = "https://sqs.example.com/123/queue",
            debugEnabled = false,
        )
        val c = makeCore(transport = sqsTransport, sessionStore = sessionStore)
        c.activateDebugSession("debug-token")
        c.deactivateDebugSession()
        c.trackSearch("shoes", 5, SearchAction.SEARCH)
        val event = queue.drain()[0]
        assertEquals(sessionStore.getOrCreateSessionId(), event.session)
        c.dispose()
    }
}
