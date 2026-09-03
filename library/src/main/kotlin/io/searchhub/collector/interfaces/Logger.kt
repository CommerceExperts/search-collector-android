package io.searchhub.collector.interfaces

import io.searchhub.collector.model.LogLevel

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
        override fun debug(msg: String, data: Any?) { if (priority[LogLevel.DEBUG]!! >= min) safely { base.debug(msg, data) } }
        override fun info(msg: String, data: Any?) { if (priority[LogLevel.INFO]!! >= min) safely { base.info(msg, data) } }
        override fun warn(msg: String, data: Any?) { if (priority[LogLevel.WARN]!! >= min) safely { base.warn(msg, data) } }
        override fun error(msg: String, data: Any?) { if (priority[LogLevel.ERROR]!! >= min) safely { base.error(msg, data) } }
    }
}

private inline fun safely(block: () -> Unit) {
    try {
        block()
    } catch (_: Exception) {
        // A broken custom Logger must never break event tracking.
    }
}
