package io.searchhub.collector

import io.searchhub.collector.impl.transport.ShSqsTransport
import org.junit.Assert.*
import org.junit.Test

class ShSqsTransportTest {

    private val endpoint = "https://sqs.eu-central-1.amazonaws.com/123456789/queue"
    private val debugEndpointUrl get() = ShSqsTransport.resolveEndpoint(endpoint, true)
    private val prodEndpointUrl get() = ShSqsTransport.resolveEndpoint(endpoint, false)

    // --- resolveEndpoint ---

    @Test
    fun `debugEnabled true prefixes debug to path`() {
        val result = ShSqsTransport.resolveEndpoint(endpoint, debugEnabled = true)
        assertTrue("expected /debug prefix, got: $result", result.contains("/debug/"))
    }

    @Test
    fun `debugEnabled false returns original endpoint unchanged`() {
        val result = ShSqsTransport.resolveEndpoint(endpoint, debugEnabled = false)
        assertEquals(endpoint, result)
    }

    @Test
    fun `debug path prefix is inserted before existing path`() {
        val endpointWithPath = "https://sqs.eu-central-1.amazonaws.com/123/queue-name"
        val result = ShSqsTransport.resolveEndpoint(endpointWithPath, debugEnabled = true)
        assertTrue(result.startsWith("https://sqs.eu-central-1.amazonaws.com/debug/"))
        assertTrue(result.endsWith("/123/queue-name"))
    }

    // --- initial debugActive state from constructor ---

    @Test
    fun `debugEnabled true at construction routes to debug endpoint`() {
        val t = ShSqsTransport(queueUrl = endpoint, debugEnabled = true)
        assertEquals(debugEndpointUrl, t.activeEndpointUrl)
    }

    @Test
    fun `debugEnabled false at construction routes to prod endpoint`() {
        val t = ShSqsTransport(queueUrl = endpoint, debugEnabled = false)
        assertEquals(prodEndpointUrl, t.activeEndpointUrl)
    }

    // --- setDebugActive toggle ---

    @Test
    fun `setDebugActive true on prod-mode instance switches to debug endpoint`() {
        val t = ShSqsTransport(queueUrl = endpoint, debugEnabled = false)
        t.setDebugActive(true)
        assertEquals(debugEndpointUrl, t.activeEndpointUrl)
    }

    @Test
    fun `setDebugActive false on debug-mode instance switches to prod endpoint`() {
        val t = ShSqsTransport(queueUrl = endpoint, debugEnabled = true)
        t.setDebugActive(false)
        assertEquals(prodEndpointUrl, t.activeEndpointUrl)
    }

    @Test
    fun `setDebugActive can toggle back and forth`() {
        val t = ShSqsTransport(queueUrl = endpoint, debugEnabled = false)
        t.setDebugActive(true)
        assertEquals(debugEndpointUrl, t.activeEndpointUrl)
        t.setDebugActive(false)
        assertEquals(prodEndpointUrl, t.activeEndpointUrl)
    }

    // --- custom debugEndpoint ---

    @Test
    fun `explicit debugEndpoint is used for debug delegate prod delegate uses queueUrl`() {
        val customDebugUrl = "https://debug.example.com/custom"
        val t = ShSqsTransport(queueUrl = endpoint, debugEnabled = true, debugEndpoint = customDebugUrl)
        assertEquals(customDebugUrl, t.activeEndpointUrl)
        t.setDebugActive(false)
        assertEquals(prodEndpointUrl, t.activeEndpointUrl)
    }

    // --- backward compatibility ---

    @Test
    fun `construction with same args as before the change produces correct initial routing`() {
        val debugTransport = ShSqsTransport(queueUrl = endpoint, debugEnabled = true)
        assertTrue(debugTransport.activeEndpointUrl.contains("/debug/"))

        val prodTransport = ShSqsTransport(queueUrl = endpoint, debugEnabled = false)
        assertEquals(endpoint, prodTransport.activeEndpointUrl)
    }
}
