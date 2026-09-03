package io.searchhub.collector.interfaces

import io.searchhub.collector.model.LogLevel

/**
 * Implementations must be fast and non-blocking: some calls happen synchronously on the
 * caller's thread (e.g. from [io.searchhub.collector.SearchCollector.setNavContext], typically
 * called on the UI thread on every navigation). Do heavy work (disk/network I/O) asynchronously
 * yourself. A throwing implementation is caught and never crashes tracking, but a slow one will
 * stall the caller.
 */
interface Logger {
    fun debug(msg: String, data: Any? = null)
    fun info(msg: String, data: Any? = null)
    fun warn(msg: String, data: Any? = null)
    fun error(msg: String, data: Any? = null)
}

val consoleLogger: Logger = object : Logger {
    override fun debug(msg: String, data: Any?) { android.util.Log.d("SearchCollector", format(msg, data)) }
    override fun info(msg: String, data: Any?) { android.util.Log.i("SearchCollector", format(msg, data)) }
    override fun warn(msg: String, data: Any?) { android.util.Log.w("SearchCollector", format(msg, data)) }
    override fun error(msg: String, data: Any?) { android.util.Log.e("SearchCollector", format(msg, data)) }
    private fun format(msg: String, data: Any?) = if (data != null) "$msg | $data" else msg
}

val silentLogger: Logger = object : Logger {
    override fun debug(msg: String, data: Any?) = Unit
    override fun info(msg: String, data: Any?) = Unit
    override fun warn(msg: String, data: Any?) = Unit
    override fun error(msg: String, data: Any?) = Unit
}

internal fun createFilteredLogger(base: Logger, minLevel: LogLevel): Logger {
    if (minLevel == LogLevel.SILENT) return silentLogger
    val priority = mapOf(LogLevel.DEBUG to 0, LogLevel.INFO to 1, LogLevel.WARN to 2, LogLevel.ERROR to 3)
    val min = priority[minLevel]!!
    // A caller-supplied Logger is arbitrary third-party code and may run on the calling thread
    // (e.g. SearchCollector.setNavContext(), fireAndForget()'s pre-configure paths). A throwing
    // implementation must never crash tracking calls, so every delegate call is guarded here —
    // once, at the single choke point every logger reference passes through — rather than at
    // each of the many call sites.
    return object : Logger {
        override fun debug(msg: String, data: Any?) { if (priority[LogLevel.DEBUG]!! >= min) safely(msg) { base.debug(msg, data) } }
        override fun info(msg: String, data: Any?) { if (priority[LogLevel.INFO]!! >= min) safely(msg) { base.info(msg, data) } }
        override fun warn(msg: String, data: Any?) { if (priority[LogLevel.WARN]!! >= min) safely(msg) { base.warn(msg, data) } }
        override fun error(msg: String, data: Any?) { if (priority[LogLevel.ERROR]!! >= min) safely(msg) { base.error(msg, data) } }
    }
}

/**
 * A broken custom Logger must never break event tracking — but silently discarding what it
 * threw would erase precisely the errors a developer needs to see, and hide the fact that their
 * own Logger is broken. Fall back to android.util.Log directly for that one line instead of
 * swallowing it — not consoleLogger.error(), whose format(msg, data) stringifies the Throwable
 * via toString() rather than passing it to Log's 3-arg overload, which would lose the stack trace.
 */
private inline fun safely(originalMsg: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        android.util.Log.e("SearchCollector", "Custom Logger threw while logging: \"$originalMsg\"", e)
    }
}
