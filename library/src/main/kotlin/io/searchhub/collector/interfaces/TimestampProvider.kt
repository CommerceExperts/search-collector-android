package io.searchhub.collector.interfaces

interface TimestampProvider {
    fun now(): Long
}
