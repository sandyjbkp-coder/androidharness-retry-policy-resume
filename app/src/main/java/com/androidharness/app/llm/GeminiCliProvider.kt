package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs prompts through the real Google Gemini CLI as a subprocess.
 *
 * This is not an HTTP provider wearing a CLI's name: the request is handed to
 * the `gemini` binary installed by [GeminiManager] and whatever the CLI writes
 * back is what the chat shows. Authentication is the CLI's own Google OAuth
 * state under the app-private `gemini-home`, so no API key is ever read or
 * stored here.
 *
 * `gemini -p <prompt>` is the CLI's documented non-interactive mode, which is
 * what makes a single-shot provider request possible at all.
 */
class GeminiCliProvider(
    private val manager: GeminiManager,
) : LlmProvider {

    override fun streamChat(
        config: ProviderConfig,
        // The CLI authenticates from its own gemini-home, so no key is used.
        @Suppress("UNUSED_PARAMETER") apiKey: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        @Suppress("UNUSED_PARAMETER") tools: List<ToolSchema>,
        @Suppress("UNUSED_PARAMETER") options: RequestOptions,
    ): Flow<StreamEvent> = callbackFlow {
        // Launched as [node, bundle/gemini.js, ...]: Android has no
        // /usr/bin/env, so the kernel cannot exec the bin symlink's shebang
        // directly. cliArgv() resolves exactly that for us.
        val prompt = renderPrompt(systemPrompt, messages)
        val argv = manager.cliArgv(
            listOf("--output-format", "stream-json", "--prompt", prompt)
        )
        if (argv == null) {
            trySend(
                StreamEvent.Failure(
                    "Gemini CLI is not installed. Install @google/gemini-cli from " +
                        "Settings → Providers → Gemini CLI, then sign in with Google.",
                ),
            )
            trySend(StreamEvent.Done(null))
            close()
            return@callbackFlow
        }
        if (!manager.oauthTokenFile().isFile) {
            trySend(
                StreamEvent.Failure(
                    "Gemini CLI is not signed in. Open Settings → Providers → Gemini CLI " +
                        "and tap \"Sign in with Google\" first; no API key is used.",
                ),
            )
            trySend(StreamEvent.Done(null))
            close()
            return@callbackFlow
        }

        val process = try {
            manager.startCli(argv)
        } catch (e: Exception) {
            null
        }
        if (process == null) {
            trySend(StreamEvent.Failure("Could not start the Gemini CLI: check the Linux environment in Settings → Terminal."))
            trySend(StreamEvent.Done(null))
            close()
            return@callbackFlow
        }

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
        val stderr = StringBuffer()
        val errThread = Thread {
            runCatching {
                stderrReader.forEachLine { line ->
                    synchronized(stderr) { stderr.append(line).append('\n') }
                }
            }
        }
        errThread.isDaemon = true
        errThread.start()

        val readerThread = Thread {
            try {
                // stream-json emits one JSON object per line; anything else
                // (a banner, a warning) is surfaced verbatim so the user is
                // never silently handed an empty answer.
                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val emitted = parseStreamJson(line)
                    if (emitted == null) {
                        trySend(StreamEvent.TextDelta(line + "\n"))
                    } else {
                        emitted.forEach { event ->
                            if (event is StreamEvent.TextDelta) {
                                trySend(event)
                            }
                        }
                    }
                }
                val done = waitFor(process, TIMEOUT_MINUTES)
                val errorText = synchronized(stderr) { stderr.toString() }
                if (!done) {
                    process.destroyForcibly()
                    trySend(StreamEvent.Failure("Gemini CLI timed out after $TIMEOUT_MINUTES minutes."))
                    trySend(StreamEvent.Done("timeout"))
                } else if (process.exitValue() != 0) {
                    trySend(
                        StreamEvent.Failure(
                            "Gemini CLI exited ${process.exitValue()}: " +
                                (errorText.trim().ifEmpty { "no error output" }).takeLast(800),
                        ),
                    )
                    trySend(StreamEvent.Done("error"))
                } else {
                    if (errorText.isNotBlank()) {
                        trySend(StreamEvent.TextDelta(errorText))
                    }
                    trySend(StreamEvent.Done("stop"))
                }
            } catch (e: Exception) {
                trySend(StreamEvent.Failure("Gemini CLI stream failed: ${e.message}"))
                trySend(StreamEvent.Done("error"))
            } finally {
                close()
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        awaitClose {
            runCatching { process.destroyForcibly() }
        }
    }.flowOn(Dispatchers.IO)

    private fun waitFor(process: Process, minutes: Long): Boolean =
        process.waitFor(minutes, TimeUnit.MINUTES)

    /**
     * Pulls assistant text out of one `stream-json` line. Returns null when the
     * line is not a recognizable event, so the caller can show it as-is rather
     * than dropping output on the floor.
     */
    private fun parseStreamJson(line: String): List<StreamEvent>? = runCatching {
        val json = ProviderFactory.json.parseToJsonElement(line).let {
            it as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
        }
        val type = json["type"]?.jsonPrimitiveOrNull() ?: return@runCatching null
        when (type) {
            "content", "assistant" -> {
                val text = extractText(json) ?: return@runCatching null
                listOf(StreamEvent.TextDelta(text))
            }
            "text" -> {
                val text = extractText(json) ?: return@runCatching null
                listOf(StreamEvent.TextDelta(text))
            }
            "error" -> {
                val message = json["error"]?.jsonPrimitiveOrNull()
                    ?: json["message"]?.jsonPrimitiveOrNull()
                    ?: "the CLI reported an error"
                listOf(StreamEvent.Failure(message))
            }
            else -> null
        }
    }.getOrNull()

    private fun extractText(obj: kotlinx.serialization.json.JsonObject): String? {
        obj["content"]?.let { c ->
            (c as? kotlinx.serialization.json.JsonObject)?.get("text")
                ?.jsonPrimitiveOrNull()
                ?.let { return it }
        }
        return obj["text"]?.jsonPrimitiveOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content

    /**
     * Flattens the conversation into the single prompt the CLI accepts.
     * Roles are kept as plain labels so the model can still tell who said
     * what. The harness tool schema is deliberately NOT forwarded: the CLI
     * runs its own tools internally, and injecting a second tool protocol
     * would collide with it rather than extend it.
     */
    private fun renderPrompt(systemPrompt: String, messages: List<ChatMessage>): String =
        buildString {
            if (systemPrompt.isNotBlank()) {
                append("System instructions:\n").append(systemPrompt.trim()).append("\n\n")
            }
            messages.forEach { m ->
                val text = m.text.trim()
                if (text.isEmpty() || m.role == Role.SYSTEM) return@forEach
                val who = when (m.role) {
                    Role.USER -> "User"
                    Role.ASSISTANT -> "Assistant"
                    else -> m.role.name.lowercase().replaceFirstChar { it.uppercase() }
                }
                append(who).append(": ").append(text).append("\n\n")
            }
        }.trim()

    private companion object {
        /** A single CLI turn can legitimately run long (it may fetch and test). */
        const val TIMEOUT_MINUTES = 10L
    }
}
