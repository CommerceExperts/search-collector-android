package io.searchhub.collector.impl.queue

import io.searchhub.collector.interfaces.EventQueue
import io.searchhub.collector.model.SearchCollectorEvent

class InMemoryEventQueue(private val maxBatchSize: Int = 10) : EventQueue {

    private val queue = mutableListOf<SearchCollectorEvent>()

    override fun push(event: SearchCollectorEvent): Boolean {
        synchronized(queue) {
            queue.add(event)
            return queue.size >= maxBatchSize
        }
    }

    override fun drain(): List<SearchCollectorEvent> {
        synchronized(queue) {
            val snapshot = queue.toList()
            queue.clear()
            return snapshot
        }
    }

    override suspend fun transactionalDrain(
        block: suspend (List<SearchCollectorEvent>) -> Unit
    ): List<SearchCollectorEvent> {
        val snapshot = synchronized(queue) { queue.toList() }
        block(snapshot)
        synchronized(queue) {
            // Remove exactly the snapshot prefix — new events pushed during block are preserved.
            // If block() threw, this line is never reached and events remain for the next flush.
            val remaining = queue.drop(snapshot.size)
            queue.clear()
            queue.addAll(remaining)
        }
        return snapshot
    }

    override fun clear() {
        synchronized(queue) { queue.clear() }
    }
}
