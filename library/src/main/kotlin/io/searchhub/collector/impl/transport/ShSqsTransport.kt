package io.searchhub.collector.impl.transport

import io.searchhub.collector.interfaces.DebugCapable
import io.searchhub.collector.interfaces.Logger
import io.searchhub.collector.interfaces.Transport
import io.searchhub.collector.interfaces.silentLogger
import io.searchhub.collector.model.SearchCollectorEvent

/**
 * SearchHub-specific SQS transport.
 * Holds separate delegates for the production and debug endpoints and switches between them at runtime.
 */
class ShSqsTransport(
    queueUrl: String,
    debugEnabled: Boolean = false,
    debugEndpoint: String? = null,
    fifo: Boolean = false,
    private val logger: Logger = silentLogger,
) : Transport, DebugCapable {

    private val prodUrl: String = resolveEndpoint(queueUrl, false)
    private val resolvedDebugUrl: String = debugEndpoint ?: resolveEndpoint(queueUrl, true)

    private val prodDelegate: HttpGetTransport =
        HttpGetTransport(queueUrl = prodUrl, fifo = fifo, logger = logger)
    private val debugDelegate: HttpGetTransport =
        HttpGetTransport(queueUrl = resolvedDebugUrl, fifo = fifo, logger = logger)

    @Volatile
    private var debugActive: Boolean = debugEnabled

    override fun setDebugActive(active: Boolean) {
        debugActive = active
        logger.debug("Debug routing ${if (active) "activated" else "deactivated"}", if (active) resolvedDebugUrl else prodUrl)
    }

    /** Exposed for tests to verify which endpoint is currently active without making HTTP calls. */
    internal val activeEndpointUrl: String get() = if (debugActive) resolvedDebugUrl else prodUrl

    override suspend fun send(events: List<SearchCollectorEvent>) =
        if (debugActive) debugDelegate.send(events) else prodDelegate.send(events)

    companion object {
        @JvmStatic
        internal fun resolveEndpoint(endpoint: String, debugEnabled: Boolean): String {
            if (!debugEnabled) return endpoint

            // Prefix /debug to the path component, matching JS shEndpointResolver behavior
            val url = java.net.URL(endpoint)
            val debugPath = "/debug${url.path}"
            return java.net.URL(url.protocol, url.host, url.port, debugPath).toString()
        }
    }
}
