package io.searchhub.collector.impl.session

import android.content.Context
import android.content.SharedPreferences
import io.searchhub.collector.interfaces.SessionStore
import io.searchhub.collector.interfaces.generateSessionId

internal const val PREFS_NAME = "SearchCollectorSession"
private const val KEY_ID = "session_id"
private const val KEY_EXPIRES_AT = "session_expires_at"
class SharedPreferencesSessionStore(
    context: Context,
    private val sessionLifetimeMs: Long = DEFAULT_SESSION_LIFETIME_MS,
) : SessionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun getOrCreateSessionId(): String {
        val storedId = prefs.getString(KEY_ID, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

        return if (storedId != null && System.currentTimeMillis() < expiresAt) {
            storedId
        } else {
            val newId = generateSessionId()
            prefs.edit()
                .putString(KEY_ID, newId)
                .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + sessionLifetimeMs)
                .apply()
            newId
        }
    }

    override suspend fun touch() {
        prefs.edit()
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + sessionLifetimeMs)
            .apply()
    }
}
