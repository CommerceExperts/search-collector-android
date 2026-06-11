package io.searchhub.collector.interfaces

import io.searchhub.collector.model.SearchCollectorEvent

interface Transport {
    suspend fun send(events: List<SearchCollectorEvent>)
}
