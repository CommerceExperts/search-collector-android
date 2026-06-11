package io.searchhub.collector.impl.trail

import io.searchhub.collector.interfaces.TrailStore
import io.searchhub.collector.model.TrailData
import io.searchhub.collector.model.TrailType

private const val DEFAULT_TTL_MS = 48L * 60 * 60 * 1000

class InMemoryTrailStore(private val ttlMs: Long = DEFAULT_TTL_MS) : TrailStore {

    private val trails = mutableMapOf<String, TrailData>()

    override suspend fun register(key: String, query: String, trailType: TrailType) {
        synchronized(trails) {
            trails[key] = TrailData(
                timestamp = System.currentTimeMillis(),
                query = query,
                type = trailType,
            )
        }
    }

    override suspend fun get(key: String): TrailData? {
        synchronized(trails) {
            val trail = trails[key] ?: return null
            if (System.currentTimeMillis() - trail.timestamp > ttlMs) {
                trails.remove(key)
                return null
            }
            return trail
        }
    }
}
