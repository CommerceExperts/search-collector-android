package io.searchhub.collector.impl.trail

import io.searchhub.collector.interfaces.TrailStore
import io.searchhub.collector.model.TrailData
import io.searchhub.collector.model.TrailType

private const val DEFAULT_TTL_MS = 48L * 60 * 60 * 1000

class InMemoryTrailStore(private val ttlMs: Long = DEFAULT_TTL_MS) : TrailStore {

    private val trails = mutableMapOf<String, TrailData>()

    override suspend fun register(key: String, query: String, trailType: TrailType) {
        synchronized(trails) {
            purgeExpired()
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
            if (isExpired(trail.timestamp)) {
                trails.remove(key)
                return null
            }
            return trail
        }
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        trails.entries.removeAll { now - it.value.timestamp > ttlMs }
    }

    private fun isExpired(timestamp: Long) = System.currentTimeMillis() - timestamp > ttlMs
}
