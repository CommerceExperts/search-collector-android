package io.searchhub.collector.impl.queue

import android.content.Context
import android.content.SharedPreferences
import io.searchhub.collector.interfaces.EventQueue
import io.searchhub.collector.model.SearchCollectorEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private const val MAX_EVENT_AGE_MS = 24L * 60 * 60 * 1000
internal const val BASE_PREFS_KEY = "search-collector-queue"

class SharedPreferencesEventQueue(
    context: Context,
    id: String? = null,
    private val maxBatchSize: Int = 10,
) : EventQueue {

    private val prefsKey = BASE_PREFS_KEY + (if (id != null) "-$id" else "")
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsKey, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private data class QueueItem(val id: String, val enqueuedAt: Long, val event: SearchCollectorEvent)

    override fun push(event: SearchCollectorEvent): Boolean {
        synchronized(this) {
            val queue = loadAndClean().toMutableList()
            queue.add(
                QueueItem(
                    id = "${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toLong()}",
                    enqueuedAt = System.currentTimeMillis(),
                    event = event,
                )
            )
            save(queue)
            return queue.size >= maxBatchSize
        }
    }

    override fun drain(): List<SearchCollectorEvent> {
        synchronized(this) {
            val events = loadAndClean().map { it.event }
            clear()
            return events
        }
    }

    override suspend fun transactionalDrain(
        block: suspend (List<SearchCollectorEvent>) -> Unit
    ): List<SearchCollectorEvent> {
        val snapshot = synchronized(this) { loadAndClean() }
        val events = snapshot.map { it.event }
        val ids = snapshot.map { it.id }.toSet()
        block(events)
        synchronized(this) {
            val remaining = loadAndClean().filter { it.id !in ids }
            save(remaining)
        }
        return events
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun loadAndClean(): List<QueueItem> {
        val raw = prefs.getString("queue", null) ?: return emptyList()
        return runCatching {
            val arr = json.parseToJsonElement(raw).jsonArray
            val now = System.currentTimeMillis()
            arr.mapNotNull { parseItem(it) }.filter { now - it.enqueuedAt <= MAX_EVENT_AGE_MS }
        }.getOrElse { emptyList() }
    }

    private fun parseItem(element: JsonElement): QueueItem? = runCatching {
        val obj = element.jsonObject
        QueueItem(
            id = obj["id"]?.jsonPrimitive?.content
                ?: "${obj["enqueuedAt"]?.jsonPrimitive?.long}-fallback",
            enqueuedAt = obj["enqueuedAt"]!!.jsonPrimitive.long,
            event = json.decodeFromJsonElement(SearchCollectorEvent.serializer(), obj["event"]!!),
        )
    }.getOrNull()

    private fun save(items: List<QueueItem>) {
        val arr = JsonArray(items.map { item ->
            buildJsonObject {
                put("id", item.id)
                put("enqueuedAt", item.enqueuedAt)
                put("event", json.encodeToJsonElement(SearchCollectorEvent.serializer(), item.event))
            }
        })
        prefs.edit().putString("queue", arr.toString()).apply()
    }
}
