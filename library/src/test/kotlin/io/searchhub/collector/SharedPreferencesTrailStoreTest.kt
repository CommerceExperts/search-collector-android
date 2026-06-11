package io.searchhub.collector

import android.content.Context
import io.searchhub.collector.impl.trail.SharedPreferencesTrailStore
import io.searchhub.collector.model.TrailType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesTrailStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("SearchCollectorTrail", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun `register and get returns correct query and type`() = runTest {
        val store = SharedPreferencesTrailStore(context)
        store.register("prod-1", "\$s=jeans/", TrailType.MAIN)
        val trail = store.get("prod-1")
        assertNotNull(trail)
        assertEquals("\$s=jeans/", trail!!.query)
        assertEquals(TrailType.MAIN, trail.type)
    }

    @Test
    fun `get returns null for unknown key`() = runTest {
        val store = SharedPreferencesTrailStore(context)
        assertNull(store.get("unknown-product"))
    }

    @Test
    fun `expired trail returns null and is removed from prefs`() = runTest {
        val store = SharedPreferencesTrailStore(context, ttlMs = 1L)
        store.register("prod-2", "\$s=shoes/", TrailType.MAIN)
        Thread.sleep(10)
        assertNull(store.get("prod-2"))
        assertNull(
            context.getSharedPreferences("SearchCollectorTrail", Context.MODE_PRIVATE)
                .getString("prod-2", null)
        )
    }

    @Test
    fun `TrailType ASSOCIATED is round-tripped correctly`() = runTest {
        val store = SharedPreferencesTrailStore(context)
        store.register("prod-3", "\$s=sneakers/", TrailType.ASSOCIATED)
        val trail = store.get("prod-3")
        assertNotNull(trail)
        assertEquals(TrailType.ASSOCIATED, trail!!.type)
    }

    @Test
    fun `register overwrites existing trail for same key`() = runTest {
        val store = SharedPreferencesTrailStore(context)
        store.register("prod-4", "\$s=first/", TrailType.MAIN)
        store.register("prod-4", "\$s=second/", TrailType.ASSOCIATED)
        val trail = store.get("prod-4")
        assertEquals("\$s=second/", trail!!.query)
        assertEquals(TrailType.ASSOCIATED, trail.type)
    }
}
