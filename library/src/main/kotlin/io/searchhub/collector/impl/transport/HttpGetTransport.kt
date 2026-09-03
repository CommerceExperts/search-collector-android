package io.searchhub.collector.impl.transport

import android.net.Uri
import android.os.Build
import android.util.Base64
import io.searchhub.collector.interfaces.Logger
import io.searchhub.collector.interfaces.Transport
import io.searchhub.collector.interfaces.silentLogger
import io.searchhub.collector.model.SearchCollectorEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends events to an SQS endpoint via HTTP GET.
 * Mirrors the JS ImgSourceSqsTransport pixel-tracking technique.
 *
 * Encoding pipeline: JSON → URL-encode → Base64url → SQS MessageBody query param
 */
class HttpGetTransport(
    private val queueUrl: String,
    private val fifo: Boolean = false,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val logger: Logger = silentLogger,
) : Transport {

    private val json = Json { encodeDefaults = true }

    override suspend fun send(events: List<SearchCollectorEvent>) {
        withContext(Dispatchers.IO) {
            val jsonBody = json.encodeToString(events)
            val base64Body = base64UrlEncode(encodeURIComponent(jsonBody).toByteArray(Charsets.UTF_8))

            var src = "$queueUrl?Version=2012-11-05&Action=SendMessage"
            if (fifo) {
                src += "&MessageGroupId=1&MessageDeduplicationId=${System.nanoTime()}"
            }
            src += "&MessageBody=$base64Body"

            val connection = URL(src).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = false
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    logger.debug("Sent ${events.size} event(s) to $queueUrl", "HTTP $responseCode")
                } else {
                    logger.warn("Unexpected response sending ${events.size} event(s) to $queueUrl", "HTTP $responseCode")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to send ${events.size} event(s) to $queueUrl", e)
                throw e
            } finally {
                connection.disconnect()
            }
        }
    }

    internal fun encodeURIComponent(value: String): String = Uri.encode(value)

    private fun base64UrlEncode(bytes: ByteArray): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.util.Base64.getUrlEncoder().encodeToString(bytes)
        } else {
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        }
}
