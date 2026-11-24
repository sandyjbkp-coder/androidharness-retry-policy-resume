package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Decides which provider failures are worth retrying and how long to wait.
 * Transient conditions only, rate limits, server overload, network drops.
 * Permanent errors (bad request, auth, not found) surface to the user at once.
 */
internal object RetryPolicy {
    const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1_000L

    private val TRANSIENT_MESSAGE = Regex(
        "overloaded|rate.?limit|timeout|timed out|temporarily|try again|" +
            "connection reset|connection refused|ECONNRESET|socket closed",
        RegexOption.IGNORE_CASE,
    )

    /** HTTP codes worth another attempt: request timeout, rate limit, server errors. */
    fun isRetryableCode(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /**
     * Classifies a failure from its (optional) thrown cause and (optional)
     * in-stream message. [ApiException] is checked before [IOException]
     * because it subclasses it, an HTTP 400 must not retry even though it
     * arrives as an IOException.
     */
    fun isRetryable(cause: Throwable?, message: String?): Boolean {
        var c = cause
        while (c != null) {
            if (c is ApiException) return isRetryableCode(c.code)
            if (c is IOException) return true
            c = c.cause
        }
        return message != null && TRANSIENT_MESSAGE.containsMatchIn(message)
    }

    /** Exponential backoff 1s / 2s / 4s with +/-30% jitter. [attempt] is 1-based. */
    fun delayMs(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 4)
        val base = BASE_DELAY_MS shl exp
        val jitter = (base * 0.3 * (Math.random() * 2 - 1)).toLong()
        return base + jitter
    }

    /**
     * Parses rate-limit reset hints from [headers] into a delay in milliseconds
     * relative to [nowMs]. Returns null when none of the known headers are
     * present or parseable, in which case the caller falls back to [delayMs].
     *
     * Header priority:
     *   1. `retry-after-ms` — explicit delay in ms
     *   2. `retry-after`   — seconds (decimal) or an HTTP-date (RFC 9110)
     *   3. `x-ratelimit-reset` — epoch seconds (or a future HTTP-date)
     *
     * Rules:
     *   - Never returns a negative delay; past timestamps yield 0.
     *   - Invalid/unparseable values fall back to null (caller uses backoff).
     *   - [nowMs] is injected for testability; tests use fixed timestamps,
     *     never real wall-clock time.
     */
    fun parseRetryAfter(headers: Map<String, String>, nowMs: Long): Long? {
        // 1. retry-after-ms: explicit delay in milliseconds.
        val retryAfterMsRaw = headers["retry-after-ms"]?.trim()
        val retryAfterMs = retryAfterMsRaw?.toLongOrNull()
        if (retryAfterMs != null) return coerceAtLeastZero(retryAfterMs)

        // 2. retry-after: seconds (decimal) or an HTTP-date.
        val retryAfter = headers["retry-after"]?.trim() ?: ""
        if (retryAfter.isNotEmpty()) {
            val seconds = retryAfter.toLongOrNull()
            if (seconds != null) return coerceAtLeastZero(seconds * 1000)
            val httpDate = parseHttpDate(retryAfter)
            if (httpDate != null) return coerceAtLeastZero(httpDate - nowMs)
        }

        // 3. x-ratelimit-reset: epoch seconds remaining.
        val reset = headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()
        if (reset != null) return coerceAtLeastZero(reset * 1000 - nowMs)

        return null
    }

    private fun coerceAtLeastZero(value: Long): Long = if (value < 0) 0 else value

    /** Parses an RFC 9110 HTTP-date into epoch milliseconds, or null if unparseable. */
    private fun parseHttpDate(value: String): Long? {
        val trimmed = value.trim()
        val formats = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME, // IMF-fixdate: Sun, 06 Nov 1994 08:49:37 GMT
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz"), // RFC 850
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.US), // asctime
            DateTimeFormatter.ofPattern("EEE MMM  dd HH:mm:ss yyyy", java.util.Locale.US), // asctime, single-digit day padded with space
        )
        for (fmt in formats) {
            try {
                return Instant.from(fmt.parse(trimmed)).toEpochMilli()
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }
}
