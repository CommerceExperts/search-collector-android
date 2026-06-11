package io.searchhub.collector.interfaces

import io.searchhub.collector.model.TrailData
import io.searchhub.collector.model.TrailType

interface TrailStore {
    suspend fun register(key: String, query: String, trailType: TrailType = TrailType.MAIN)
    suspend fun get(key: String): TrailData?
}
