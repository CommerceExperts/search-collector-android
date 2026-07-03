package io.searchhub.collector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.searchhub.collector.impl.queue.InMemoryEventQueue
import io.searchhub.collector.impl.session.InMemorySessionStore
import io.searchhub.collector.impl.timestamp.SystemTimestampProvider
import io.searchhub.collector.impl.trail.InMemoryTrailStore
import io.searchhub.collector.impl.transport.ShSqsTransport
import io.searchhub.collector.interfaces.BrowserInfoProvider
import io.searchhub.collector.interfaces.Transport
import io.searchhub.collector.interfaces.silentLogger
import io.searchhub.collector.model.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class SearchCollectorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var sentBatches: MutableList<List<SearchCollectorEvent>>
    private lateinit var queue: InMemoryEventQueue

    private val fakeTransport get() = object : Transport {
        override suspend fun send(events: List<SearchCollectorEvent>) {
            sentBatches.add(events)
        }
    }

    private fun makeConfig(
        transport: Transport = fakeTransport,
        eventQueue: InMemoryEventQueue = queue,
        timestamp: BufferedEventsTimestamp = BufferedEventsTimestamp.ORIGINAL,
    ) = SearchCollectorConfig(
        endpoint = "https://example.com",
        channel = "de",
        context = context,
        queueSettings = QueueSettings(batchIntervalMs = 60_000L),
        bufferedEventsTimestamp = timestamp,
        overrides = DependencyOverrides(
            transport = transport,
            sessionStore = InMemorySessionStore(),
            trailStore = InMemoryTrailStore(),
            eventQueue = eventQueue,
            timestampProvider = SystemTimestampProvider(),
        ),
    )

    @Before
    fun setup() {
        sentBatches = mutableListOf()
        queue = InMemoryEventQueue(maxBatchSize = 10)
        SearchCollector.reset()
    }

    @After
    fun teardown() {
        SearchCollector.reset()
    }

    // --- pre-configure buffering ---

    @Test
    fun `calls before configure are buffered and replayed after configure`() = runTest {
        SearchCollector.trackFiredSearch("jeans")
        SearchCollector.configure(makeConfig())
        Thread.sleep(300) // replay runs on Dispatchers.IO; give it time to enqueue
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        val event = sentBatches[0][0] as SearchCollectorEvent.FiredSearch
        assertEquals("\$s=jeans/", event.query)
    }

    @Test
    fun `buffered events use original timestamp when ORIGINAL mode`() = runTest {
        val before = System.currentTimeMillis()
        SearchCollector.trackFiredSearch("jeans")
        Thread.sleep(10)
        SearchCollector.configure(makeConfig(timestamp = BufferedEventsTimestamp.ORIGINAL))
        Thread.sleep(300)
        SearchCollector.flush()
        val event = sentBatches[0][0]
        assertTrue(event.timestamp in before..(before + 5000))
    }

    @Test
    fun `buffered events use replay timestamp when REPLAY mode`() = runTest {
        SearchCollector.trackFiredSearch("jeans")
        val configureTime = System.currentTimeMillis()
        SearchCollector.configure(makeConfig(timestamp = BufferedEventsTimestamp.REPLAY))
        Thread.sleep(300)
        SearchCollector.flush()
        val event = sentBatches[0][0]
        assertTrue(event.timestamp >= configureTime)
    }

    // --- reconfigure ---

    @Test
    fun `configure called twice disposes previous instance`() = runTest {
        val queue1 = InMemoryEventQueue(maxBatchSize = 10)
        SearchCollector.configure(makeConfig(eventQueue = queue1))
        SearchCollector.trackFiredSearch("first")

        val queue2 = InMemoryEventQueue(maxBatchSize = 10)
        SearchCollector.configure(makeConfig(eventQueue = queue2))
        SearchCollector.flush()

        assertEquals(0, sentBatches.size)
        assertEquals(0, queue2.drain().size)
    }

    // --- reset ---

    @Test
    fun `reset discards buffered pre-configure actions`() = runTest {
        SearchCollector.trackFiredSearch("should be discarded")
        SearchCollector.reset()
        SearchCollector.configure(makeConfig())
        SearchCollector.flush()
        assertEquals(0, sentBatches.size)
    }

    @Test
    fun `reset before configure does not throw`() {
        SearchCollector.reset()
        SearchCollector.reset()
    }

    // --- reset(clearStorage) ---

    @Test
    fun `reset without clearStorage does not clear SharedPreferences`() {
        val prefs = context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE)
        prefs.edit().putString("session_id", "test-session").apply()
        SearchCollector.configure(makeConfig())
        SearchCollector.reset(clearStorage = false)
        assertEquals("test-session", prefs.getString("session_id", null))
    }

    @Test
    fun `reset with clearStorage clears all SharedPreferences files`() {
        SearchCollector.configure(makeConfig())
        context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE)
            .edit().putString("session_id", "abc").apply()
        context.getSharedPreferences("SearchCollectorTrail", Context.MODE_PRIVATE)
            .edit().putString("prod-1", "trail").apply()
        context.getSharedPreferences("search-collector-queue", Context.MODE_PRIVATE)
            .edit().putString("queue", "[]").apply()

        SearchCollector.reset(clearStorage = true)

        assertNull(context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE).getString("session_id", null))
        assertNull(context.getSharedPreferences("SearchCollectorTrail", Context.MODE_PRIVATE).getString("prod-1", null))
        assertNull(context.getSharedPreferences("search-collector-queue", Context.MODE_PRIVATE).getString("queue", null))
    }

    @Test
    fun `reset with clearStorage before configure does not throw`() {
        SearchCollector.reset(clearStorage = true)
    }

    @Test
    fun `reset(false) followed by reset(clearStorage=true) still clears SharedPreferences`() {
        SearchCollector.configure(makeConfig())
        context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE)
            .edit().putString("session_id", "should-be-gone").apply()
        SearchCollector.reset(clearStorage = false)
        SearchCollector.configure(makeConfig())
        SearchCollector.reset(clearStorage = true)
        assertNull(
            context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE)
                .getString("session_id", null)
        )
    }

    @Test
    fun `reset without argument behaves like reset(clearStorage = false)`() {
        SearchCollector.configure(makeConfig())
        context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE)
            .edit().putString("session_id", "keep-me").apply()
        SearchCollector.reset()
        assertEquals("keep-me", context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE).getString("session_id", null))
    }

    // --- config-time debugToken ---

    @Test
    fun `configure with debugToken sets session override from first event`() = runTest {
        SearchCollector.configure(makeConfig().copy(
            debugRouting = DebugRoutingSettings(debugToken = "cfg-tok"),
        ))
        SearchCollector.trackFiredSearch("shoes")
        Thread.sleep(300) // trackFiredSearch dispatches to Dispatchers.IO
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        assertEquals("cfg-tok", sentBatches[0][0].session)
    }

    @Test
    fun `configure with debugToken and enabled=false respects the explicit false and stays on production endpoint`() = runTest {
        val sqsTransport = ShSqsTransport(
            queueUrl = "https://sqs.example.com/123/queue",
            debugEnabled = false,
        )
        SearchCollector.configure(makeConfig(transport = sqsTransport).copy(
            debugRouting = DebugRoutingSettings(enabled = false, debugToken = "tok"),
        ))
        assertFalse(sqsTransport.activeEndpointUrl.contains("/debug/"))
    }

    @Test
    fun `configure without debugToken does not change routing or session`() = runTest {
        SearchCollector.configure(makeConfig())
        SearchCollector.trackFiredSearch("shoes")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        assertNotEquals("", sentBatches[0][0].session)
        // session is a UUID from InMemorySessionStore, not null and not a debug token
        assertFalse(sentBatches[0][0].session.startsWith("cfg-"))
    }

    @Test
    fun `configure reconfigure clears debug token when new config has no debugToken`() = runTest {
        SearchCollector.configure(makeConfig().copy(
            debugRouting = DebugRoutingSettings(debugToken = "cfg-tok"),
        ))
        // reconfigure without token
        val queue2 = InMemoryEventQueue(maxBatchSize = 10)
        SearchCollector.configure(makeConfig(eventQueue = queue2))
        SearchCollector.trackFiredSearch("shoes")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        assertNotEquals("cfg-tok", sentBatches[0][0].session)
    }

    // --- activateDebugSession / deactivateDebugSession public API ---

    @Test(expected = IllegalStateException::class)
    fun `activateDebugSession before configure throws`() {
        SearchCollector.activateDebugSession("tok")
    }

    @Test(expected = IllegalStateException::class)
    fun `deactivateDebugSession before configure throws`() {
        SearchCollector.deactivateDebugSession()
    }

    @Test
    fun `activateDebugSession after configure sets debug token on flushed events`() = runTest {
        SearchCollector.configure(makeConfig())
        SearchCollector.activateDebugSession("rt-tok")
        Thread.sleep(300) // activation + trackFiredSearch both dispatch to Dispatchers.IO
        SearchCollector.trackFiredSearch("shoes")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        assertEquals("rt-tok", sentBatches[0][0].session)
    }

    @Test
    fun `deactivateDebugSession after activation restores normal session`() = runTest {
        SearchCollector.configure(makeConfig())
        SearchCollector.activateDebugSession("rt-tok")
        Thread.sleep(300)
        SearchCollector.deactivateDebugSession()
        Thread.sleep(300)
        SearchCollector.trackFiredSearch("shoes")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        assertNotEquals("rt-tok", sentBatches[0][0].session)
    }

    // --- extractDebugToken ---

    @Test
    fun `extractDebugToken returns token from intent data URI`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("myapp://search?___scForceNewSession_=abc123"))
        assertEquals("abc123", SearchCollector.extractDebugToken(intent))
    }

    @Test
    fun `extractDebugToken returns null when sid param absent`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("myapp://search?q=shoes"))
        assertNull(SearchCollector.extractDebugToken(intent))
    }

    @Test
    fun `extractDebugToken returns null when intent has no data URI`() {
        val intent = Intent(Intent.ACTION_VIEW)
        assertNull(SearchCollector.extractDebugToken(intent))
    }

    @Test
    fun `extractDebugToken returns empty string for empty sid value`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("myapp://search?___scForceNewSession_="))
        assertEquals("", SearchCollector.extractDebugToken(intent))
    }

    @Test
    fun `extractDebugToken does not change session state`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("myapp://search?___scForceNewSession_=abc123"))
        SearchCollector.extractDebugToken(intent) // pure — no side effects
        // core is null (no configure called in this test) — subsequent configure should work normally
        SearchCollector.configure(makeConfig())
        // no exception = state unchanged
    }

    @Test
    fun `DEBUG_TOKEN_PARAM is ___scForceNewSession_`() {
        assertEquals("___scForceNewSession_", SearchCollector.DEBUG_TOKEN_PARAM)
    }

    // --- U1: disable() ---

    @Test
    fun `disable clears pending buffer and discards subsequent calls`() = runTest {
        // AE1: buffer 50 events, then disable
        repeat(50) { SearchCollector.trackFiredSearch("event$it") }
        SearchCollector.disable()
        // 10 further calls are discarded
        repeat(10) { SearchCollector.trackFiredSearch("post-disable$it") }
        SearchCollector.configure(makeConfig())
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(0, sentBatches.size)
    }

    @Test
    fun `disable then configure re-enables and replays empty queue`() = runTest {
        // AE3: disable → configure → events flow normally
        SearchCollector.disable()
        SearchCollector.configure(makeConfig())
        SearchCollector.trackFiredSearch("jeans")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        assertEquals(1, sentBatches[0].size)
    }

    @Test
    fun `disable after configure discards new events but existing queue still flushes`() = runTest {
        SearchCollector.configure(makeConfig())
        SearchCollector.trackFiredSearch("pre-disable")
        Thread.sleep(300)
        SearchCollector.disable()
        SearchCollector.trackFiredSearch("post-disable")
        Thread.sleep(100)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        val eventKeywords = (sentBatches[0][0] as SearchCollectorEvent.FiredSearch).keywords
        assertEquals("pre-disable", eventKeywords)
    }

    @Test
    fun `configure after disable re-enables so new events flow normally`() = runTest {
        SearchCollector.disable()
        SearchCollector.configure(makeConfig())
        SearchCollector.trackFiredSearch("after-re-enable")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
    }

    // --- U1: maxPendingActions cap ---

    @Test
    fun `pending queue cap drops oldest events`() {
        // AE2: 260 buffered pre-configure against the default cap of 250 → oldest 10 dropped
        // Test the cap at buffer-time via direct inspection of the pending queue
        val pendingField = SearchCollector.javaClass.getDeclaredField("pendingActions")
        pendingField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(SearchCollector) as java.util.concurrent.ConcurrentLinkedQueue<*>

        repeat(260) { SearchCollector.trackFiredSearch("event$it") }

        assertEquals(250, pending.size)
    }

    // --- U1: clearPendingActions() ---

    @Test
    fun `clearPendingActions removes pre-configure buffer`() = runTest {
        // AE7
        repeat(5) { SearchCollector.trackFiredSearch("event$it") }
        SearchCollector.clearPendingActions()
        SearchCollector.configure(makeConfig())
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(0, sentBatches.size)
    }

    @Test
    fun `clearPendingActions is no-op when collector is active`() = runTest {
        // AE8
        SearchCollector.configure(makeConfig())
        SearchCollector.clearPendingActions() // no-op — active events bypass the buffer
        SearchCollector.trackFiredSearch("jeans")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
    }

    @Test
    fun `reset after disable restores pre-configure buffering`() = runTest {
        // P0 fix: reset() must clear isDisabled so that post-reset pre-configure events buffer
        SearchCollector.disable()
        SearchCollector.reset()
        SearchCollector.trackFiredSearch("should-be-buffered")
        SearchCollector.configure(makeConfig())
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
    }

    // --- U2: setNavContext() ---

    @Test
    fun `setNavContext after configure propagates to events`() = runTest {
        // AE4
        SearchCollector.configure(makeConfig())
        SearchCollector.setNavContext("pdp/12345", "search/results")
        SearchCollector.trackFiredSearch("jeans")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        val event = sentBatches[0][0] as SearchCollectorEvent.FiredSearch
        assertEquals("pdp/12345", event.url)
        assertEquals("search/results", event.ref)
    }

    @Test
    fun `setNavContext captures per-event url before configure`() = runTest {
        // AE5: each pre-configure event carries the context at the time it was buffered
        SearchCollector.setNavContext("home", "")
        SearchCollector.trackFiredSearch("jeans")
        SearchCollector.setNavContext("pdp", "home")
        SearchCollector.trackFiredSearch("blue jeans")
        SearchCollector.configure(makeConfig())
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        assertEquals(2, sentBatches[0].size)
        val first = sentBatches[0][0] as SearchCollectorEvent.FiredSearch
        val second = sentBatches[0][1] as SearchCollectorEvent.FiredSearch
        assertEquals("home", first.url)
        assertEquals("pdp", second.url)
    }

    @Test
    fun `setNavContext while disabled is cached and applied after configure`() = runTest {
        SearchCollector.disable()
        SearchCollector.setNavContext("pdp/999", "home")
        SearchCollector.configure(makeConfig())
        SearchCollector.trackFiredSearch("shoes")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        val event = sentBatches[0][0] as SearchCollectorEvent.FiredSearch
        assertEquals("pdp/999", event.url)
        assertEquals("home", event.ref)
    }

    @Test
    fun `setNavContext url and ref take effect even with a custom BrowserInfoProvider`() = runTest {
        val customProvider = object : BrowserInfoProvider {
            override suspend fun getUserAgent() = "custom-agent"
            override suspend fun isTouchDevice() = false
            override suspend fun getLanguage() = "en"
        }
        SearchCollector.configure(makeConfig().copy(
            overrides = makeConfig().overrides.copy(browserInfoProvider = customProvider)
        ))
        SearchCollector.setNavContext("some/screen", "previous")
        SearchCollector.trackFiredSearch("jeans")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        val event = sentBatches[0][0] as SearchCollectorEvent.FiredSearch
        // url/ref are captured at call time from cachedContext, not from the custom provider
        assertEquals("some/screen", event.url)
        assertEquals("previous", event.ref)
    }

    @Test
    fun `post-replay context restore applies cached context to subsequent active events`() = runTest {
        SearchCollector.setNavContext("final-screen", "prev")
        // Buffer one event with a different per-event context
        SearchCollector.setNavContext("first-screen", "")
        SearchCollector.trackFiredSearch("buffered")
        SearchCollector.setNavContext("final-screen", "prev")
        SearchCollector.configure(makeConfig())
        Thread.sleep(300)
        // After replay, active events should carry the final cached context
        SearchCollector.trackFiredSearch("active")
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        assertEquals(2, sentBatches[0].size)
        val activeEvent = sentBatches[0][1] as SearchCollectorEvent.FiredSearch
        assertEquals("final-screen", activeEvent.url)
    }

    // --- P1.5: replay race / late arrivals ---

    @Test
    fun `events arriving right after configure during replay are drained and sent`() = runTest {
        // Buffer one event so configure() keeps core==null during replay.
        // "post-configure" is fired before replay settles — it lands in pendingActions
        // and must be drained by the second drain at the end of replay.
        SearchCollector.trackFiredSearch("buffered")
        SearchCollector.configure(makeConfig())
        SearchCollector.trackFiredSearch("post-configure") // may arrive while core==null
        Thread.sleep(300)
        SearchCollector.flush()
        assertEquals(1, sentBatches.size)
        val keywords = sentBatches[0].map { (it as SearchCollectorEvent.FiredSearch).keywords }
        assertTrue("buffered event missing", keywords.contains("buffered"))
        assertTrue("post-configure event missing", keywords.contains("post-configure"))
    }

    @Test
    fun `reset before replay settles prevents stale events from activating the core`() = runTest {
        // Buffer events, configure (replay launches async), reset immediately.
        // pendingCore?.dispose() cancels the replay scope; the if (pendingCore===newCore)
        // guard prevents core from being set even if the coroutine body runs through.
        repeat(5) { SearchCollector.trackFiredSearch("should-not-appear$it") }
        val replayQueue = InMemoryEventQueue(maxBatchSize = 10)
        SearchCollector.configure(makeConfig(eventQueue = replayQueue))
        SearchCollector.reset()
        Thread.sleep(300) // let any in-flight coroutine settle
        // Re-configure with fresh queue — no events from the cancelled replay should appear
        SearchCollector.configure(makeConfig())
        SearchCollector.flush()
        assertEquals(0, sentBatches.size)
    }

    // --- U3: flushAsync() ---

    @Test
    fun `flushAsync before configure is a no-op and does not throw`() {
        SearchCollector.flushAsync() // no-op — core is null
    }

    @Test
    fun `flushAsync after configure sends queued events`() = runTest {
        SearchCollector.configure(makeConfig())
        SearchCollector.trackFiredSearch("shoes")
        Thread.sleep(300)
        SearchCollector.flushAsync()
        Thread.sleep(300) // give the async flush time to complete
        assertEquals(1, sentBatches.size)
    }

    // --- U4: debug routing auto-detection ---

    private fun configureWithoutTransportOverride(debugRouting: DebugRoutingSettings?) {
        SearchCollector.configure(
            SearchCollectorConfig(
                endpoint = "https://sqs.example.com/123/queue",
                channel = "de",
                context = context,
                queueSettings = QueueSettings(batchIntervalMs = 60_000L),
                debugRouting = debugRouting,
                overrides = DependencyOverrides(
                    sessionStore = InMemorySessionStore(),
                    trailStore = InMemoryTrailStore(),
                    eventQueue = queue,
                    timestampProvider = SystemTimestampProvider(),
                ),
            )
        )
    }

    private fun activeTransportUrl(): String? {
        val coreField = SearchCollector.javaClass.getDeclaredField("core")
        coreField.isAccessible = true
        val core = coreField.get(SearchCollector) as? SearchCollectorCore ?: return null
        val transportField = SearchCollectorCore::class.java.getDeclaredField("transport")
        transportField.isAccessible = true
        val transport = transportField.get(core) as? ShSqsTransport ?: return null
        return transport.activeEndpointUrl
    }

    @Test
    fun `debugRouting enabled=null on release build uses production endpoint`() {
        // AE6 release path
        val originalCodename = Build.VERSION.CODENAME
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", "REL")
            configureWithoutTransportOverride(DebugRoutingSettings(enabled = null))
            val url = activeTransportUrl()
            assertNotNull(url)
            assertFalse("expected prod URL, got: $url", url!!.contains("/debug/"))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", originalCodename)
        }
    }

    @Test
    fun `debugRouting enabled=null on debug build uses debug endpoint`() {
        // AE6 debug path
        val originalCodename = Build.VERSION.CODENAME
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", "Tiramisu")
            configureWithoutTransportOverride(DebugRoutingSettings(enabled = null))
            val url = activeTransportUrl()
            assertNotNull(url)
            assertTrue("expected /debug/ in URL, got: $url", url!!.contains("/debug/"))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", originalCodename)
        }
    }

    @Test
    fun `debugRouting enabled=true always uses debug endpoint regardless of CODENAME`() {
        val originalCodename = Build.VERSION.CODENAME
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", "REL")
            configureWithoutTransportOverride(DebugRoutingSettings(enabled = true))
            val url = activeTransportUrl()
            assertNotNull(url)
            assertTrue("expected /debug/ in URL, got: $url", url!!.contains("/debug/"))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", originalCodename)
        }
    }

    @Test
    fun `debugRouting enabled=false always uses production endpoint regardless of CODENAME`() {
        val originalCodename = Build.VERSION.CODENAME
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", "Tiramisu")
            configureWithoutTransportOverride(DebugRoutingSettings(enabled = false))
            val url = activeTransportUrl()
            assertNotNull(url)
            assertFalse("expected prod URL, got: $url", url!!.contains("/debug/"))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", originalCodename)
        }
    }

    @Test
    fun `debugRouting=null uses production endpoint`() {
        val originalCodename = Build.VERSION.CODENAME
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", "Tiramisu")
            configureWithoutTransportOverride(null)
            val url = activeTransportUrl()
            assertNotNull(url)
            assertFalse("expected prod URL when debugRouting=null, got: $url", url!!.contains("/debug/"))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "CODENAME", originalCodename)
        }
    }
}
