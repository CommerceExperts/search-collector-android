package io.searchhub.collector

import android.content.Context
import io.searchhub.collector.impl.queue.SharedPreferencesEventQueue
import io.searchhub.collector.model.SearchCollectorEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesEventQueueTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun makeQueue(maxBatchSize: Int = 10) =
        SharedPreferencesEventQueue(context, id = "test", maxBatchSize = maxBatchSize)

    private fun makeEvent(keywords: String = "test"): SearchCollectorEvent =
        SearchCollectorEvent.FiredSearch(
            timestamp = System.currentTimeMillis(),
            session = "s1",
            channel = "de",
            url = "https://example.com",
            ref = "",
            keywords = keywords,
        )

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("search-collector-queue-test", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun `push and drain round-trips events`() {
        val queue = makeQueue()
        queue.push(makeEvent("a"))
        queue.push(makeEvent("b"))
        val drained = queue.drain()
        assertEquals(2, drained.size)
        assertEquals("a", (drained[0] as SearchCollectorEvent.FiredSearch).query)
        assertEquals("b", (drained[1] as SearchCollectorEvent.FiredSearch).query)
    }

    @Test
    fun `drain empties the queue`() {
        val queue = makeQueue()
        queue.push(makeEvent())
        queue.drain()
        assertEquals(0, queue.drain().size)
    }

    @Test
    fun `push returns true when maxBatchSize reached`() {
        val queue = makeQueue(maxBatchSize = 2)
        queue.push(makeEvent())
        assertTrue(queue.push(makeEvent()))
    }

    @Test
    fun `push returns false when under maxBatchSize`() {
        val queue = makeQueue(maxBatchSize = 3)
        assertFalse(queue.push(makeEvent()))
        assertFalse(queue.push(makeEvent()))
    }

    @Test
    fun `transactionalDrain preserves events pushed during block`() = runTest {
        val queue = makeQueue()
        queue.push(makeEvent("first"))
        queue.transactionalDrain { _ ->
            queue.push(makeEvent("second"))
        }
        val remaining = queue.drain()
        assertEquals(1, remaining.size)
        assertEquals("second", (remaining[0] as SearchCollectorEvent.FiredSearch).query)
    }

    @Test
    fun `transactionalDrain preserves all events when block throws`() = runTest {
        val queue = makeQueue()
        queue.push(makeEvent("keep"))
        runCatching {
            queue.transactionalDrain { throw RuntimeException("transport failure") }
        }
        val remaining = queue.drain()
        assertEquals(1, remaining.size)
    }

    @Test
    fun `clear empties the queue`() {
        val queue = makeQueue()
        queue.push(makeEvent())
        queue.clear()
        assertEquals(0, queue.drain().size)
    }

    @Test
    fun `events older than 24 hours are pruned`() {
        val prefs = context.getSharedPreferences("search-collector-queue-test", Context.MODE_PRIVATE)
        val oldTimestamp = System.currentTimeMillis() - (25L * 60 * 60 * 1000)
        prefs.edit().putString(
            "queue",
            """[{"id":"old-1","enqueuedAt":$oldTimestamp,"event":{"type":"fired-search","timestamp":$oldTimestamp,"session":"s","channel":"de","url":"","ref":"","keywords":"old"}}]"""
        ).apply()

        val queue = makeQueue()
        queue.push(makeEvent("fresh"))
        val drained = queue.drain()
        assertEquals(1, drained.size)
        assertEquals("fresh", (drained[0] as SearchCollectorEvent.FiredSearch).query)
    }
}
