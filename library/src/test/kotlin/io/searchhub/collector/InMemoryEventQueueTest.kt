package io.searchhub.collector

import io.searchhub.collector.impl.queue.InMemoryEventQueue
import io.searchhub.collector.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InMemoryEventQueueTest {

    private fun makeEvent(keywords: String = "test"): SearchCollectorEvent =
        SearchCollectorEvent.FiredSearch(
            timestamp = System.currentTimeMillis(),
            session = "abc1234",
            channel = "de",
            url = "https://example.com",
            ref = "",
            keywords = keywords,
        )

    @Test
    fun `push returns false when under maxBatchSize`() {
        val queue = InMemoryEventQueue(maxBatchSize = 3)
        assertFalse(queue.push(makeEvent()))
        assertFalse(queue.push(makeEvent()))
    }

    @Test
    fun `push returns true when maxBatchSize reached`() {
        val queue = InMemoryEventQueue(maxBatchSize = 2)
        queue.push(makeEvent())
        assertTrue(queue.push(makeEvent()))
    }

    @Test
    fun `drain returns all events and clears queue`() {
        val queue = InMemoryEventQueue()
        queue.push(makeEvent("a"))
        queue.push(makeEvent("b"))
        val drained = queue.drain()
        assertEquals(2, drained.size)
        assertEquals(0, queue.drain().size)
    }

    @Test
    fun `transactionalDrain only removes processed events`() = runTest {
        val queue = InMemoryEventQueue()
        queue.push(makeEvent("first"))

        queue.transactionalDrain { events ->
            assertEquals(1, events.size)
            // Simulate a new event arriving during async block
            queue.push(makeEvent("second"))
        }

        val remaining = queue.drain()
        assertEquals(1, remaining.size)
        assertEquals("second", (remaining[0] as SearchCollectorEvent.FiredSearch).query)
    }

    @Test
    fun `transactionalDrain on error does not remove events`() = runTest {
        val queue = InMemoryEventQueue()
        queue.push(makeEvent("keep"))

        runCatching {
            queue.transactionalDrain { throw RuntimeException("transport failure") }
        }

        // Events must still be there after failed drain
        val remaining = queue.drain()
        assertEquals(1, remaining.size)
    }

    @Test
    fun `clear empties the queue`() {
        val queue = InMemoryEventQueue()
        queue.push(makeEvent())
        queue.push(makeEvent())
        queue.clear()
        assertEquals(0, queue.drain().size)
    }
}
