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
    return object : Logger {
        override fun debug(msg: String, data: Any?) { if (priority[LogLevel.DEBUG]!! >= min) base.debug(msg, data) }
        override fun info(msg: String, data: Any?) { if (priority[LogLevel.INFO]!! >= min) base.info(msg, data) }
        override fun warn(msg: String, data: Any?) { if (priority[LogLevel.WARN]!! >= min) base.warn(msg, data) }
        override fun error(msg: String, data: Any?) { if (priority[LogLevel.ERROR]!! >= min) base.error(msg, data) }
    }
}
