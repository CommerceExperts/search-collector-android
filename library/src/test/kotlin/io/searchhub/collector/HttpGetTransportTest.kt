package io.searchhub.collector

import io.searchhub.collector.impl.transport.HttpGetTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HttpGetTransportTest {

    private val transport = HttpGetTransport(queueUrl = "https://example.com/queue")

    @Test
    fun `encodeURIComponent encodes spaces as percent-20 not plus`() {
        val result = transport.encodeURIComponent("jeans jacke")
        assertEquals("jeans%20jacke", result)
        assertFalse("space must not be encoded as +", result.contains("+"))
    }

    @Test
    fun `encodeURIComponent encodes JSON special characters`() {
        val result = transport.encodeURIComponent("""{"key":"value"}""")
        assertFalse(result.contains("{"))
        assertFalse(result.contains("}"))
        assertFalse(result.contains("\""))
    }

    @Test
    fun `encodeURIComponent leaves alphanumeric characters unchanged`() {
        val result = transport.encodeURIComponent("jeans123")
        assertEquals("jeans123", result)
    }
}
