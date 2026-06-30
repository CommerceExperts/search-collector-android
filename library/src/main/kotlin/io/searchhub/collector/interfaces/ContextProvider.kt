package io.searchhub.collector.interfaces

interface ContextProvider {
    suspend fun getCurrentUrl(): String
    suspend fun getReferrer(): String
    suspend fun getUserAgent(): String
    suspend fun isTouchDevice(): Boolean
    suspend fun getLanguage(): String
    /**
     * Called before each event and at the end of replay to set the current screen URL
     * and referrer. The default implementation is a no-op.
     *
     * Custom [ContextProvider] implementations **must override this** if they want
     * [getCurrentUrl] and [getReferrer] to reflect per-event context set via
     * [SearchCollector.setContext]. Without an override, all events will return the
     * same static values regardless of what [SearchCollector.setContext] was called with.
     */
    fun setContext(url: String, referrer: String) {}
}
