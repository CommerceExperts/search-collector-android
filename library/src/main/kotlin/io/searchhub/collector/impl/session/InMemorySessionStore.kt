package io.searchhub.collector.impl.session

import io.searchhub.collector.interfaces.SessionStore
import io.searchhub.collector.interfaces.generateSessionId

const val DEFAULT_SESSION_LIFETIME_MS = 48L * 60 * 60 * 1000

class InMemorySessionStore(
    private val sessionLifetimeMs: Long = DEFAULT_SESSION_LIFETIME_MS,
) : SessionStore {

    @Volatile private var sessionId: String? = null
    @Volatile private var expiresAt: Long = 0L

    override suspend fun getOrCreateSessionId(): String {
        val current = sessionId
        return if (current != null && System.currentTimeMillis() < expiresAt) {
            current
        } else {
            val newId = generateSessionId()
            sessionId = newId
            expiresAt = System.currentTimeMillis() + sessionLifetimeMs
            newId
        }
    }

    override suspend fun touch() {
        expiresAt = System.currentTimeMillis() + sessionLifetimeMs
    }
}
