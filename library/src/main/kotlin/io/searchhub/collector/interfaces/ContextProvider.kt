package io.searchhub.collector.interfaces

interface ContextProvider {
    suspend fun getCurrentUrl(): String
    suspend fun getReferrer(): String
    suspend fun getUserAgent(): String
    suspend fun isTouchDevice(): Boolean
    suspend fun getLanguage(): String
}
