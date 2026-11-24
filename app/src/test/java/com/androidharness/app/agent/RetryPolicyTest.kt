package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {

    @Test
    fun `http retryable codes`() {
        assertTrue(RetryPolicy.isRetryableCode(408))
        assertTrue(RetryPolicy.isRetryableCode(429))
        assertTrue(RetryPolicy.isRetryableCode(500))
        assertTrue(RetryPolicy.isRetryableCode(502))
        assertTrue(RetryPolicy.isRetryableCode(503))
        assertTrue(RetryPolicy.isRetryableCode(599))
        assertFalse(RetryPolicy.isRetryableCode(400))
        assertFalse(RetryPolicy.isRetryableCode(401))
        assertFalse(RetryPolicy.isRetryableCode(403))
        assertFalse(RetryPolicy.isRetryableCode(404))
        assertFalse(RetryPolicy.isRetryableCode(422))
    }

    @Test
    fun `terminal 404 is never retried even as ApiException`() {
        assertFalse(RetryPolicy.isRetryableCode(404))
        assertFalse(RetryPolicy.isRetryable(ApiException(404, "model not found"), null))
        assertFalse(RetryPolicy.isRetryable(null, "model not found"))
    }

    @Test
    fun `api exception classifies by code`() {
        assertTrue(RetryPolicy.isRetryable(ApiException(429, "rate limit"), null))
        assertFalse(RetryPolicy.isRetryable(ApiException(400, "bad request"), null))
    }

    @Test
    fun `message-only http-4xx text does not match transient regex`() {
        // No typed cause available (flattened message): must not retry.
        assertFalse(RetryPolicy.isRetryable(null, "HTTP 400: invalid request"))
        assertFalse(RetryPolicy.isRetryable(null, "HTTP 401: unauthorized"))
    }

    @Test
    fun `plain io exception is retryable`() {
        assertTrue(RetryPolicy.isRetryable(IOException("Connection reset by peer"), null))
        assertTrue(RetryPolicy.isRetryable(null, "Connection reset by peer"))
    }

    @Test
    fun `wrapped io exception is retryable`() {
        val wrapped = RuntimeException("stream failed", IOException("socket closed"))
        assertTrue(RetryPolicy.isRetryable(wrapped, "stream failed"))
    }

    @Test
    fun `wrapped api exception classifies by code not wrapper`() {
        val wrapped = RuntimeException("request failed", ApiException(500, "boom"))
        assertTrue(RetryPolicy.isRetryable(wrapped, null))
        val wrapped400 = RuntimeException("request failed", ApiException(400, "nope"))
        assertFalse(RetryPolicy.isRetryable(wrapped400, null))
    }

    @Test
    fun `transient in-stream messages are retryable`() {
        assertTrue(RetryPolicy.isRetryable(null, "The server is overloaded"))
        assertTrue(RetryPolicy.isRetryable(null, "Rate limit exceeded"))
        assertTrue(RetryPolicy.isRetryable(null, "Request timed out"))
    }

    @Test
    fun `permanent in-stream messages are not retryable`() {
        assertFalse(RetryPolicy.isRetryable(null, "Invalid API key provided"))
        assertFalse(RetryPolicy.isRetryable(null, "model not found"))
        assertFalse(RetryPolicy.isRetryable(null, null))
    }

    @Test
    fun `backoff grows exponentially within bounds`() {
        val d1 = RetryPolicy.delayMs(1)
        val d2 = RetryPolicy.delayMs(2)
        val d3 = RetryPolicy.delayMs(3)
        // 1s, 2s, 4s bases each ±30% jitter.
        assertTrue(d1 in 700..1300)
        assertTrue(d2 in 1400..2600)
        assertTrue(d3 in 2800..5200)
        assertEquals(3, RetryPolicy.MAX_RETRIES)
    }

    // -------------------------------------------------------------------------
    // parseRetryAfter — header-aware retry delay
    // -------------------------------------------------------------------------

    @Test
    fun `parseRetryAfter with retry-after-ms returns the ms delay`() {
        val headers = mapOf("retry-after-ms" to "5000")
        assertEquals(5000L, RetryPolicy.parseRetryAfter(headers, nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `parseRetryAfter with retry-after seconds converts to ms`() {
        val headers = mapOf("retry-after" to "10")
        assertEquals(10_000L, RetryPolicy.parseRetryAfter(headers, nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `parseRetryAfter with retry-after http-date computes remaining ms`() {
        // Sun, 06 Nov 1994 08:49:37 GMT = 784111777000 ms epoch
        val headers = mapOf("retry-after" to "Sun, 06 Nov 1994 08:49:37 GMT")
        // Past HTTP-date clamps to 0, never negative.
        assertEquals(0L, RetryPolicy.parseRetryAfter(headers, nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `parseRetryAfter with x-ratelimit-reset epoch seconds returns remaining ms`() {
        // reset 1_000_000_010_000 ms epoch => 10s from nowMs 1_000_000_000_000
        val headers = mapOf("x-ratelimit-reset" to "1000000010")
        assertEquals(10_000L, RetryPolicy.parseRetryAfter(headers, nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `parseRetryAfter past timestamp yields zero not negative`() {
        val headers = mapOf("retry-after" to "Sun, 06 Nov 1994 08:49:37 GMT")
        assertEquals(0L, RetryPolicy.parseRetryAfter(headers, nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `parseRetryAfter negative x-ratelimit-reset clamps to zero`() {
        val headers = mapOf("x-ratelimit-reset" to "1")
        assertEquals(0L, RetryPolicy.parseRetryAfter(headers, nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `parseRetryAfter invalid header falls back to null`() {
        val headers = mapOf("retry-after-ms" to "not-a-number")
        assertNull(RetryPolicy.parseRetryAfter(headers, nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `parseRetryAfter invalid retry-after falls back to null`() {
        val headers = mapOf("retry-after" to "not-a-date-or-seconds")
        assertNull(RetryPolicy.parseRetryAfter(headers, nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `parseRetryAfter empty headers returns null`() {
        assertNull(RetryPolicy.parseRetryAfter(emptyMap(), nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `parseRetryAfter header priority retry-after-ms wins`() {
        val headers = mapOf(
            "retry-after-ms" to "1000",
            "retry-after" to "100",
            "x-ratelimit-reset" to "2000000000",
        )
        assertEquals(1000L, RetryPolicy.parseRetryAfter(headers, nowMs = 0L))
    }

    @Test
    fun `parseRetryAfter header priority retry-after beats reset`() {
        val headers = mapOf("retry-after" to "5", "x-ratelimit-reset" to "9999999999")
        assertEquals(5_000L, RetryPolicy.parseRetryAfter(headers, nowMs = 0L))
    }

    @Test
    fun `api exception headers field defaults to empty`() {
        assertEquals(emptyMap<String, String>(), ApiException(429, "rate limited").headers)
    }

    @Test
    fun `api exception carries captured headers`() {
        val hdrs = mapOf("retry-after-ms" to "3000", "x-ratelimit-reset" to "99")
        val e = ApiException(429, "rate limited", hdrs)
        assertEquals(hdrs, e.headers)
        assertTrue(RetryPolicy.isRetryable(e, null))
    }
}
