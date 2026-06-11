package io.searchhub.collector.impl.timestamp

import io.searchhub.collector.interfaces.TimestampProvider

class SystemTimestampProvider : TimestampProvider {
    override fun now(): Long = System.currentTimeMillis()
}
