package io.searchhub.collector

import io.searchhub.collector.impl.trail.InMemoryTrailStore
import io.searchhub.collector.model.TrailType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InMemoryTrailStoreTest {

    @Test
    fun `register and get returns correct query and type`() = runTest {
        val store = InMemoryTrailStore()
        store.register("prod-1", "\$s=jeans/", TrailType.MAIN)
        val trail = store.get("prod-1")
        assertNotNull(trail)
        assertEquals("\$s=jeans/", trail!!.query)
        assertEquals(TrailType.MAIN, trail.type)
    }

    @Test
    fun `get returns null for unknown key`() = runTest {
        val store = InMemoryTrailStore()
        assertNull(store.get("unknown"))
    }

    @Test
    fun `expired trail returns null and is removed`() = runTest {
        val store = InMemoryTrailStore(ttlMs = 1L)
        store.register("prod-2", "\$s=shoes/", TrailType.MAIN)
        Thread.sleep(10)
        assertNull(store.get("prod-2"))
    }

    @Test
    fun `register overwrites existing trail for same key`() = runTest {
        val store = InMemoryTrailStore()
        store.register("prod-3", "\$s=first/", TrailType.MAIN)
        store.register("prod-3", "\$s=second/", TrailType.ASSOCIATED)
        val trail = store.get("prod-3")
        assertEquals("\$s=second/", trail!!.query)
        assertEquals(TrailType.ASSOCIATED, trail.type)
    }

    @Test
    fun `expired entry is purged by register() without get() call`() = runTest {
        val store = InMemoryTrailStore(ttlMs = 1L)
        store.register("prod-4", "\$s=boots/", TrailType.MAIN)
        Thread.sleep(10)
        store.register("prod-5", "\$s=hats/", TrailType.MAIN)
        assertNull(store.get("prod-4"))
        assertNotNull(store.get("prod-5"))
    }

    @Test
    fun `non-expired entries survive register()`() = runTest {
        val store = InMemoryTrailStore()
        store.register("prod-6", "\$s=coats/", TrailType.MAIN)
        store.register("prod-7", "\$s=gloves/", TrailType.ASSOCIATED)
        store.register("prod-8", "\$s=scarves/", TrailType.MAIN)
        assertNotNull(store.get("prod-6"))
        assertNotNull(store.get("prod-7"))
        assertNotNull(store.get("prod-8"))
    }
}
