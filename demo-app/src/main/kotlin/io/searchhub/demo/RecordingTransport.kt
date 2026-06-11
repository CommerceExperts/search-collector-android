package io.searchhub.demo

import io.searchhub.collector.impl.transport.ShSqsTransport
import io.searchhub.collector.interfaces.DebugCapable
import io.searchhub.collector.interfaces.Transport
import io.searchhub.collector.model.SearchCollectorEvent

class RecordingTransport(queueUrl: String) : Transport, DebugCapable {

    private val sqsTransport = ShSqsTransport(queueUrl = queueUrl)
    private val events = mutableListOf<SearchCollectorEvent>()

    override fun setDebugActive(active: Boolean) = sqsTransport.setDebugActive(active)

    override suspend fun send(batch: List<SearchCollectorEvent>) {
        synchronized(events) { events.addAll(batch) }
        sqsTransport.send(batch)
    }

    fun getEvents(): List<SearchCollectorEvent> = synchronized(events) { events.toList() }

    fun clear() = synchronized(events) { events.clear() }
}
