package io.searchhub.collector

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.searchhub.collector.impl.queue.InMemoryEventQueue
import io.searchhub.collector.impl.session.InMemorySessionStore
import io.searchhub.collector.impl.timestamp.SystemTimestampProvider
import io.searchhub.collector.impl.trail.InMemoryTrailStore
import io.searchhub.collector.impl.transport.ShSqsTransport
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
    fun `configure with debugToken and enabled false still activates debug routing`() = runTest {
        val sqsTransport = ShSqsTransport(
            queueUrl = "https://sqs.example.com/123/queue",
            debugEnabled = false,
        )
        SearchCollector.configure(makeConfig(transport = sqsTransport).copy(
            debugRouting = DebugRoutingSettings(enabled = false, debugToken = "tok"),
        ))
        assertTrue(sqsTransport.activeEndpointUrl.contains("/debug/"))
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
}
