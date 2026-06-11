package io.searchhub.collector.interfaces

import io.searchhub.collector.model.SearchCollectorEvent

interface EventQueue {
    /** Enqueues an event. Returns true if the queue is full and should be flushed immediately. */
    fun push(event: SearchCollectorEvent): Boolean

    /** Returns and clears all queued events. */
    fun drain(): List<SearchCollectorEvent>

    /**
     * Atomically drains the queue and executes [block] with the snapshot.
     * Only the events passed to [block] are removed — events pushed during the async
     * [block] execution are preserved for the next flush.
     */
    suspend fun transactionalDrain(block: suspend (List<SearchCollectorEvent>) -> Unit): List<SearchCollectorEvent>

    fun clear()
}
