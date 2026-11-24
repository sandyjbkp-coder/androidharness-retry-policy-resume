package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RetryPolicy].
 *
 * Covers:
 *  - isRetryableCode  — permanent vs transient HTTP codes
 *  - isRetryable      — ApiException, IOException, plain message, null inputs
 *  - delayMs          — exponential back-off bounds with jitter
 */
class RetryAfterTest {

    // -------------------------------------------------------------------------
    // isRetryableCode
    // -------------------------------------------------------------------------

    @Test
    fun `408 request timeout is retryable`() {
        assertTrue(RetryPolicy.isRetryableCode(408))
    }

    @Test
    fun `429 rate limit is retryable`() {
        assertTrue(RetryPolicy.isRetryableCode(429))
    }

    @Test
    fun `500 internal server error is retryable`() {
        assertTrue(RetryPolicy.isRetryableCode(500))
    }

    @Test
    fun `503 service unavailable is retryable`() {
        assertTrue(RetryPolicy.isRetryableCode(503))
    }

    @Test
    fun `200 ok is not retryable`() {
        assertFalse(RetryPolicy.isRetryableCode(200))
    }

    @Test
    fun `400 bad request is not retryable`() {
        assertFalse(RetryPolicy.isRetryableCode(400))
    }

    @Test
    fun `401 unauthorized is not retryable`() {
        assertFalse(RetryPolicy.isRetryableCode(401))
    }

    @Test
    fun `403 forbidden is not retryable`() {
        assertFalse(RetryPolicy.isRetryableCode(403))
    }

    @Test
    fun `404 not found is not retryable`() {
        assertFalse(RetryPolicy.isRetryableCode(404))
    }

    @Test
    fun `422 unprocessable is not retryable`() {
        assertFalse(RetryPolicy.isRetryableCode(422))
    }

    // -------------------------------------------------------------------------
    // isRetryable — ApiException
    // -------------------------------------------------------------------------

    @Test
    fun `ApiException 429 is retryable`() {
        assertTrue(RetryPolicy.isRetryable(ApiException(429, "rate limited"), null))
    }

    @Test
    fun `ApiException 503 is retryable`() {
        assertTrue(RetryPolicy.isRetryable(ApiException(503, "overloaded"), null))
    }

    @Test
    fun `ApiException 400 is NOT retryable`() {
        assertFalse(RetryPolicy.isRetryable(ApiException(400, "bad request"), null))
    }

    @Test
    fun `ApiException 401 is NOT retryable`() {
        assertFalse(RetryPolicy.isRetryable(ApiException(401, "unauthorized"), null))
    }

    // -------------------------------------------------------------------------
    // isRetryable — IOException (network-level)
    // -------------------------------------------------------------------------

    @Test
    fun `plain IOException is retryable`() {
        assertTrue(RetryPolicy.isRetryable(IOException("socket closed"), null))
    }

    @Test
    fun `IOException wrapping RuntimeException is retryable`() {
        val wrapped = IOException("wrapper", RuntimeException("inner"))
        assertTrue(RetryPolicy.isRetryable(wrapped, null))
    }

    // -------------------------------------------------------------------------
    // isRetryable — message keyword matching (no cause)
    // -------------------------------------------------------------------------

    @Test
    fun `overloaded keyword in message is retryable`() {
        assertTrue(RetryPolicy.isRetryable(null, "The model is overloaded, please try again."))
    }

    @Test
    fun `rate limit keyword in message is retryable`() {
        assertTrue(RetryPolicy.isRetryable(null, "rate limit exceeded"))
    }

    @Test
    fun `timeout keyword in message is retryable`() {
        assertTrue(RetryPolicy.isRetryable(null, "Request timeout after 30s"))
    }

    @Test
    fun `generic user error message is NOT retryable`() {
        assertFalse(RetryPolicy.isRetryable(null, "Invalid API key"))
    }

    @Test
    fun `null cause and null message is NOT retryable`() {
        assertFalse(RetryPolicy.isRetryable(null, null))
    }

    // -------------------------------------------------------------------------
    // delayMs — exponential back-off with ±30 % jitter
    // -------------------------------------------------------------------------

    @Test
    fun `attempt 1 delay is within jitter range of 1000ms`() {
        repeat(50) {
            val d = RetryPolicy.delayMs(1)
            assertTrue("delay=$d out of [700,1300]", d in 700L..1300L)
        }
    }

    @Test
    fun `attempt 2 delay is within jitter range of 2000ms`() {
        repeat(50) {
            val d = RetryPolicy.delayMs(2)
            assertTrue("delay=$d out of [1400,2600]", d in 1400L..2600L)
        }
    }

    @Test
    fun `attempt 3 delay is within jitter range of 4000ms`() {
        repeat(50) {
            val d = RetryPolicy.delayMs(3)
            assertTrue("delay=$d out of [2800,5200]", d in 2800L..5200L)
        }
    }

    @Test
    fun `attempt beyond max exponent is clamped`() {
        // Exponent is capped at 4 → max base = 1000 * 16 = 16000
        repeat(20) {
            val d = RetryPolicy.delayMs(10)
            assertTrue("delay=$d exceeds clamped ceiling", d <= 20_800L) // 16000 * 1.3
        }
    }

    @Test
    fun `MAX_RETRIES constant is 3`() {
        assertEquals(3, RetryPolicy.MAX_RETRIES)
    }
}
