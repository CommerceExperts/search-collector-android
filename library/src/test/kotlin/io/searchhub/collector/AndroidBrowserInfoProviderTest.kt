package io.searchhub.collector

import io.searchhub.collector.impl.context.AndroidBrowserInfoProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidBrowserInfoProviderTest {

    @Before
    fun clearHttpAgent() {
        System.clearProperty("http.agent")
    }

    @After
    fun restoreHttpAgent() {
        System.clearProperty("http.agent")
    }

    @Test
    fun `getUserAgent returns http agent system property when set`() = runTest {
        System.setProperty("http.agent", "TestAgent/2.0")
        val provider = AndroidBrowserInfoProvider()
        assertEquals("TestAgent/2.0", provider.getUserAgent())
    }

    @Test
    fun `getUserAgent falls back to manufacturer and model when property not set`() = runTest {
        val provider = AndroidBrowserInfoProvider()
        val agent = provider.getUserAgent()
        assertTrue(agent.isNotEmpty())
    }

    @Test
    fun `getLanguage returns BCP-47 tag containing hyphen separator`() = runTest {
        val provider = AndroidBrowserInfoProvider()
        val lang = provider.getLanguage()
        assertTrue("expected BCP-47 format like 'en-US', got: $lang", lang.contains("-"))
    }

    @Test
    fun `isTouchDevice always returns true`() = runTest {
        val provider = AndroidBrowserInfoProvider()
        assertTrue(provider.isTouchDevice())
    }
}
