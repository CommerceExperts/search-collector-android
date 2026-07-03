package io.searchhub.collector.interfaces

interface BrowserInfoProvider {
    suspend fun getUserAgent(): String
    suspend fun isTouchDevice(): Boolean
    suspend fun getLanguage(): String
}
