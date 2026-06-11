package io.searchhub.collector

import android.content.Context
import io.searchhub.collector.impl.context.AndroidContextProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidContextProviderTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

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
        val provider = AndroidContextProvider(context)
        assertEquals("TestAgent/2.0", provider.getUserAgent())
    }

    @Test
    fun `getUserAgent falls back to manufacturer and model when property not set`() = runTest {
        val provider = AndroidContextProvider(context)
        val agent = provider.getUserAgent()
        assertTrue(agent.isNotEmpty())
    }

    @Test
    fun `getLanguage returns BCP-47 tag containing hyphen separator`() = runTest {
        val provider = AndroidContextProvider(context)
        val lang = provider.getLanguage()
        assertTrue("expected BCP-47 format like 'en-US', got: $lang", lang.contains("-"))
    }

    @Test
    fun `setUrl is reflected in getCurrentUrl`() = runTest {
        val provider = AndroidContextProvider(context)
        provider.setUrl("app://my-app/search?q=jeans")
        assertEquals("app://my-app/search?q=jeans", provider.getCurrentUrl())
    }

    @Test
    fun `setReferrer is reflected in getReferrer`() = runTest {
        val provider = AndroidContextProvider(context)
        provider.setReferrer("app://my-app/home")
        assertEquals("app://my-app/home", provider.getReferrer())
    }

    @Test
    fun `isTouchDevice always returns true`() = runTest {
        val provider = AndroidContextProvider(context)
        assertTrue(provider.isTouchDevice())
    }

    @Test
    fun `default url and referrer are empty strings`() = runTest {
        val provider = AndroidContextProvider(context)
        assertEquals("", provider.getCurrentUrl())
        assertEquals("", provider.getReferrer())
    }
}
