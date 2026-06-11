package io.searchhub.collector.model

import android.content.Context
import io.searchhub.collector.impl.session.DEFAULT_SESSION_LIFETIME_MS
import io.searchhub.collector.interfaces.*

data class SearchCollectorConfig(
    val endpoint: String,
    val channel: String,
    /** Must be application context or a context from which applicationContext can be obtained. */
    val context: Context,
    val queueSettings: QueueSettings = QueueSettings(),
    /** How long a session stays alive after the last touch. Default: 48 hours. */
    val sessionLifetimeMs: Long = DEFAULT_SESSION_LIFETIME_MS,
    val logLevel: LogLevel = LogLevel.ERROR,
    val logger: Logger? = null,
    val debugRouting: DebugRoutingSettings? = null,
    val bufferedEventsTimestamp: BufferedEventsTimestamp = BufferedEventsTimestamp.ORIGINAL,
    val overrides: DependencyOverrides = DependencyOverrides(),
)

data class QueueSettings(
    val batchIntervalMs: Long = 5_000L,
    val maxBatchSize: Int = 10,
)

data class DebugRoutingSettings(
    /** true = always debug, false = always prod, null = auto-detect */
    val enabled: Boolean? = null,
    val debugEndpoint: String? = null,
    /** When set, activates a debug session: overrides the session ID and routes to the /debug endpoint. */
    val debugToken: String? = null,
)

data class DependencyOverrides(
    val transport: Transport? = null,
    val sessionStore: SessionStore? = null,
    val trailStore: TrailStore? = null,
    val eventQueue: EventQueue? = null,
    val contextProvider: ContextProvider? = null,
    val timestampProvider: TimestampProvider? = null,
)
