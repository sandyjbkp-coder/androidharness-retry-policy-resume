package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StreamRetrierTest {

    private val noopEmit: suspend (AgentEvent.Retrying) -> Unit = {}

    @Test
    fun `clean stream returns null and forwards all events`() = runTest {
        val seen = mutableListOf<StreamEvent>()
        val result = StreamRetrier.run(
            streamFor = {
                flowOf(
                    StreamEvent.TextDelta("hi"),
                    StreamEvent.Done("stop"),
                )
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = { seen += it },
            retryReason = { it },
            emitEvent = noopEmit,
        )
        assertEquals(null, result)
        assertEquals(2, seen.size)
        assertTrue(seen[0] is StreamEvent.TextDelta)
        assertEquals("stop", (seen[1] as StreamEvent.Done).finishReason)
    }

    @Test
    fun `transient in-stream failure retries and succeeds`() = runTest {
        var attempt = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) flowOf(StreamEvent.Failure("server overloaded"))
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        assertEquals(1, retries[0].attempt)
        assertEquals("server overloaded", retries[0].reason)
        assertEquals(retries[0].delayMs, slept.single())
        // First-attempt base delay 1s ±30% jitter.
        assertTrue(slept.single() in 700..1300)
    }

    @Test
    fun `permanent in-stream failure never retries`() = runTest {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                flowOf(StreamEvent.Failure("Invalid API key provided"))
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = { error("no retry expected") },
        )
        assertEquals("Invalid API key provided", result)
        assertEquals(1, builds)
        assertTrue(retries.isEmpty())
    }

    @Test
    fun `terminal 404 api exception is never retried`() = runTest {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                throw ApiException(404, "model not found")
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = { error("no retry expected") },
        )
        assertTrue(result!!.contains("404"))
        assertEquals(1, builds)
        assertTrue(retries.isEmpty())
    }

    @Test
    fun `io exception exhausts retries then returns last failure`() = runTest {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        var attemptsStarted = 0
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                throw IOException("connection reset by peer")
            },
            onAttemptStart = { attemptsStarted++ },
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = {},
        )
        assertEquals(RetryPolicy.MAX_RETRIES, retries.size)
        assertEquals(listOf(1, 2, 3), retries.map { it.attempt })
        assertEquals(RetryPolicy.MAX_RETRIES + 1, attemptsStarted)
        assertTrue(result!!.contains("connection reset"))
    }

    @Test
    fun `api exception 400 is terminal`() = runTest {
        var builds = 0
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                throw ApiException(400, "bad request")
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { error("no retry expected") },
            sleep = { error("no retry expected") },
        )
        assertTrue(result!!.contains("400"))
        assertEquals(1, builds)
    }

    @Test
    fun `prior output blocks retry`() = runTest {
        var builds = 0
        val deltasSeen = mutableListOf<String>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                flowOf(StreamEvent.TextDelta("partial"), StreamEvent.Failure("overloaded"))
            },
            onAttemptStart = {},
            hasOutput = { deltasSeen.isNotEmpty() },
            handleEvent = { if (it is StreamEvent.TextDelta) deltasSeen += it.text },
            retryReason = { it },
            emitEvent = { error("no retry after output was streamed") },
            sleep = { error("no retry after output was streamed") },
        )
        assertEquals("overloaded", result)
        assertEquals(1, builds)
        assertEquals(listOf("partial"), deltasSeen)
    }

    @Test
    fun `429 with retry-after-ms header honors header delay`() = runTest {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                if (builds == 1) {
                    throw ApiException(
                        429,
                        "rate limited",
                        headers = mapOf("retry-after-ms" to "4200"),
                    )
                } else {
                    flowOf(StreamEvent.TextDelta("recovered"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        // Honor the header delay exactly, not the default backoff.
        assertEquals(4200L, slept.single())
    }

    @Test
    fun `429 without header falls back to exponential backoff`() = runTest {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                if (builds == 1) {
                    throw ApiException(429, "rate limited", headers = emptyMap())
                } else {
                    flowOf(StreamEvent.TextDelta("recovered"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        assertEquals(1, retries[0].attempt)
        // Header absent -> default 1s backoff ±30%.
        assertTrue(slept.single() in 700..1300)
    }

    @Test
    fun `retry resets buffers via onAttemptStart`() = runTest {
        var attempt = 0
        var resets = 0
        var lastText = ""
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) flowOf(StreamEvent.Failure("timed out"))
                    else flowOf(StreamEvent.TextDelta("second try"))
                }
            },
            onAttemptStart = {
                resets++
                lastText = ""
            },
            hasOutput = { lastText.isNotEmpty() },
            handleEvent = { if (it is StreamEvent.TextDelta) lastText = it.text },
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        assertEquals(null, result)
        assertEquals(2, resets)
        assertEquals("second try", lastText)
    }

    @Test
    fun `stalled stream is treated as transient failure`() = runTest {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                if (builds == 1) {
                    // Never emits; stallGuard times out.
                    kotlinx.coroutines.flow.flow {
                        kotlinx.coroutines.awaitCancellation()
                    }
                } else {
                    flowOf(StreamEvent.TextDelta("recovered"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = {},
            stallTimeoutMs = 150,
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        assertEquals(StreamRetrier.STALLED_STREAM_MESSAGE, retries[0].reason)
    }
}
