package io.searchhub.collector

import android.content.Context
import io.searchhub.collector.impl.session.SharedPreferencesSessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesSessionStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun `getOrCreateSessionId returns non-empty string on fresh store`() = runTest {
        val store = SharedPreferencesSessionStore(context)
        val id = store.getOrCreateSessionId()
        assertTrue(id.isNotEmpty())
    }

    @Test
    fun `getOrCreateSessionId returns same id within TTL`() = runTest {
        val store = SharedPreferencesSessionStore(context)
        val id1 = store.getOrCreateSessionId()
        val id2 = store.getOrCreateSessionId()
        assertEquals(id1, id2)
    }

    @Test
    fun `getOrCreateSessionId creates new id after TTL expiry`() = runTest {
        val prefs = context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("session_id", "old-session")
            .putLong("session_expires_at", System.currentTimeMillis() - 1000)
            .apply()
        val store = SharedPreferencesSessionStore(context)
        val id = store.getOrCreateSessionId()
        assertNotEquals("old-session", id)
        assertTrue(id.isNotEmpty())
    }

    @Test
    fun `touch extends TTL so session survives past original expiry`() = runTest {
        val store = SharedPreferencesSessionStore(context)
        val id = store.getOrCreateSessionId()

        // Simulate time passing close to expiry by writing a nearly-expired TTL
        val prefs = context.getSharedPreferences("SearchCollectorSession", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("session_expires_at", System.currentTimeMillis() + 500)
            .apply()

        store.touch()

        // After touch, expires_at should be well in the future
        val expiresAt = prefs.getLong("session_expires_at", 0L)
        assertTrue(expiresAt > System.currentTimeMillis() + 1000)

        val idAfterTouch = store.getOrCreateSessionId()
        assertEquals(id, idAfterTouch)
    }
}
