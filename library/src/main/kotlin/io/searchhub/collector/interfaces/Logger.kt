package io.searchhub.collector.interfaces

import io.searchhub.collector.model.LogLevel

/**
 * Implementations must be fast and non-blocking — some calls run synchronously on the
 * caller's thread (e.g. the UI thread, via [io.searchhub.collector.SearchCollector.setNavContext]).
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
    // Guards every call so a throwing custom Logger can never crash tracking.
    return object : Logger {
        override fun debug(msg: String, data: Any?) { if (priority[LogLevel.DEBUG]!! >= min) safely(msg) { base.debug(msg, data) } }
        override fun info(msg: String, data: Any?) { if (priority[LogLevel.INFO]!! >= min) safely(msg) { base.info(msg, data) } }
        override fun warn(msg: String, data: Any?) { if (priority[LogLevel.WARN]!! >= min) safely(msg) { base.warn(msg, data) } }
        override fun error(msg: String, data: Any?) { if (priority[LogLevel.ERROR]!! >= min) safely(msg) { base.error(msg, data) } }
    }
}

// Falls back to Log.e directly (not consoleLogger.error(), which would lose the stack trace)
// so a broken Logger doesn't crash tracking and doesn't vanish without a trace either.
private inline fun safely(originalMsg: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        android.util.Log.e("SearchCollector", "Custom Logger threw while logging: \"$originalMsg\"", e)
    }
}
