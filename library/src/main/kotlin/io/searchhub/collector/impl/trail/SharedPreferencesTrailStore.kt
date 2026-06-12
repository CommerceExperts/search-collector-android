package io.searchhub.collector.impl.trail

import android.content.Context
import android.content.SharedPreferences
import io.searchhub.collector.interfaces.TrailStore
import io.searchhub.collector.model.TrailData
import io.searchhub.collector.model.TrailType
import org.json.JSONObject

internal const val PREFS_NAME = "SearchCollectorTrail"
private const val DEFAULT_TTL_MS = 48L * 60 * 60 * 1000

class SharedPreferencesTrailStore(
    context: Context,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : TrailStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        purgeExpired()
    }

    override suspend fun register(key: String, query: String, trailType: TrailType) {
        purgeExpired()
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("query", query)
            put("type", trailType.value)
        }
        prefs.edit().putString(key, json.toString()).apply()
    }

    private fun purgeExpired() {
        val all = prefs.all
        if (all.isEmpty()) return
        val now = System.currentTimeMillis()
        val expired = all.keys.filter { key ->
            runCatching {
                val timestamp = JSONObject(all[key] as String).getLong("timestamp")
                now - timestamp > ttlMs
            }.getOrDefault(false)
        }
        if (expired.isEmpty()) return
        val editor = prefs.edit()
        expired.forEach { editor.remove(it) }
        editor.apply()
    }

    override suspend fun get(key: String): TrailData? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val timestamp = json.getLong("timestamp")
            if (System.currentTimeMillis() - timestamp > ttlMs) {
                prefs.edit().remove(key).apply()
                return null
            }
            TrailData(
                timestamp = timestamp,
                query = json.getString("query"),
                type = TrailType.entries.first { it.value == json.getString("type") },
            )
        }.getOrNull()
    }
}
