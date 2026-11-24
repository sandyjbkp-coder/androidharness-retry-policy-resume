package com.androidharness.app.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.androidharness.app.data.env.LinuxEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Where the Gemini CLI is on disk and whether it is usable. */
enum class GeminiCliState {
    /** The Linux prefix is not installed yet, so npm cannot run. */
    NO_PREFIX,

    /** @google/gemini-cli is not installed in the prefix. */
    NOT_INSTALLED,

    /** Installed but no usable OAuth/API-key credentials are configured. */
    NEEDS_LOGIN,

    /** Installed and authenticated; non-interactive prompts can run. */
    READY,

    /** An install/update is running right now. */
    INSTALLING,
}

/**
 * Owns the real `@google/gemini-cli` inside the app-private Linux prefix.
 *
 * Important layout:
 *   GEMINI_CLI_HOME = <app files>/gemini-home
 *   user settings   = <app files>/gemini-home/.gemini/settings.json
 *   OAuth cache     = <app files>/gemini-home/.gemini/oauth_creds.json
 *   API-key env     = <app files>/gemini-home/.gemini/.env
 *
 * The manager supports both official Gemini CLI auth paths:
 *   - Sign in with Google (`oauth-personal`)
 *   - Gemini API key (`gemini-api-key` / GEMINI_API_KEY)
 *
 *  - Chrome cookies or browser profile data are read by this app.
 */
class GeminiManager(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
) {
    /** App-private, persistent HOME for Gemini CLI. Survives `pm install -r`. */
    val geminiHome: File = File(context.filesDir, "gemini-home")

    /** Current Gemini CLI global config directory. */
    val geminiConfigDir: File = File(geminiHome, ".gemini")

    private val tmpDir: File = File(geminiHome, "tmp")
    private val settingsFile: File = File(geminiConfigDir, "settings.json")
    private val apiEnvFile: File = File(geminiConfigDir, ".env")

    private val _state = MutableStateFlow(GeminiCliState.NO_PREFIX)
    val state: StateFlow<GeminiCliState> = _state.asStateFlow()

    init {
        runCatching {
            geminiHome.mkdirs()
            geminiConfigDir.mkdirs()
            tmpDir.mkdirs()
        }
        refresh()
    }

    /** The npm-created `gemini` shim/symlink inside the app Linux prefix. */
    fun geminiBinary(): File? =
        File(linuxEnv.prefix, "bin/gemini").takeIf { it.isFile }

    /** OAuth cache written by Gemini CLI after a successful Google sign-in. */
    fun oauthTokenFile(): File =
        File(geminiConfigDir, "oauth_creds.json")

    /** Prefix-local Node.js runtime. Gemini CLI currently requires Node 20+. */
    fun nodeBinary(): File? =
        File(linuxEnv.prefix, "bin/node").takeIf { it.isFile }

    /**
     * Resolve the actual JavaScript entrypoint behind npm's `bin/gemini` link.
     *
     * Android has no normal `/usr/bin/env` in the app namespace, so executing
     * the npm shim directly can fail even though the file exists. We therefore
     * always invoke the CLI as: `<prefix>/bin/node <resolved gemini.js> ...`.
     */
    fun geminiEntry(): File? {
        val shim = geminiBinary() ?: return null
        val target = runCatching {
            shim.canonicalFile
        }.getOrElse {
            shim
        }

        return target.takeIf {
            it.isFile && nodeBinary()?.isFile == true
        }
    }

    /** `[node, gemini.js, ...args]`, or null when the CLI/runtime is missing. */
    fun cliArgv(args: List<String>): Array<String>? {
        val node = nodeBinary() ?: return null
        val entry = geminiEntry() ?: return null

        return arrayOf(
            node.absolutePath,
            entry.absolutePath,
            // Headless runs are refused in untrusted directories otherwise.
            "--skip-trust",
        ) + args
    }

    /** Convenience argv for current Gemini CLI headless mode. */
    fun promptArgv(
        prompt: String,
        model: String? = null,
        streamJson: Boolean = true,
    ): Array<String>? {
        val args = mutableListOf<String>()

        model
            ?.takeIf { it.isNotBlank() }
            ?.let {
                args += listOf(
                    "--model",
                    it.trim(),
                )
            }

        args += listOf(
            "--prompt",
            prompt,
        )

        if (streamJson) {
            args += listOf(
                "--output-format",
                "stream-json",
            )
        }

        return cliArgv(args)
    }

    /** Recompute state from the real installation/auth files. */
    fun refresh() = refreshState()

    private fun refreshState() {
        _state.value = when {
            !linuxEnv.isReady ->
                GeminiCliState.NO_PREFIX

            geminiBinary()?.isFile != true ||
                nodeBinary()?.isFile != true ->
                GeminiCliState.NOT_INSTALLED

            hasOAuthCredentials() ||
                hasApiKeyConfigured() ->
                GeminiCliState.READY

            else ->
                GeminiCliState.NEEDS_LOGIN
        }
    }

    fun hasOAuthCredentials(): Boolean =
        oauthTokenFile().isFile &&
            oauthTokenFile().length() > 0L

    /** True without exposing the API-key value. */
    fun hasApiKeyConfigured(): Boolean {
        if (!apiEnvFile.isFile) {
            return false
        }

        return runCatching {
            apiEnvFile.useLines { lines ->
                lines.any { line ->
                    val t = line.trim()

                    t.startsWith("GEMINI_API_KEY=") &&
                        t
                            .substringAfter('=')
                            .trim()
                            .trim('"', '\'')
                            .isNotEmpty()
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Base environment inherited by every Gemini process.
     *
     * Do not force `CI=1`: current Gemini CLI uses interactive mode for OAuth,
     * while `--prompt` already forces headless mode for normal agent requests.
     */
    fun processEnv(): Array<String> =
        buildMap<String, String> {
            putAll(linuxEnv.processEnv())

            put(
                "HOME",
                geminiHome.absolutePath,
            )

            put(
                "GEMINI_CLI_HOME",
                geminiHome.absolutePath,
            )

            put(
                "TMPDIR",
                tmpDir.absolutePath,
            )

            put(
                "TERM",
                get("TERM")
                    ?.takeIf { it.isNotBlank() }
                    ?: "xterm-256color",
            )

            /*
             * Some dependencies still probe Android/Termux clipboard
             * behaviour at import time.
             */
            put(
                "TERMUX_VERSION",
                get("TERMUX_VERSION")
                    ?: "0.118.0",
            )

            /*
             * A stale CI/BROWSER value can incorrectly force auth into
             * headless mode.
             */
            remove("CI")
            remove("BROWSER")
        }
            .map { "${it.key}=${it.value}" }
            .toTypedArray()

    /**
     * Install/update Gemini CLI from npm and smoke-test
     * the installed binary.
     */
    suspend fun install(): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!linuxEnv.isReady) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Linux environment is not installed yet",
                    ),
                )
            }

            _state.value = GeminiCliState.INSTALLING

            geminiHome.mkdirs()
            geminiConfigDir.mkdirs()
            tmpDir.mkdirs()

            /*
             * Fail early when the bundled Node runtime
             * isn't available.
             */
            if (nodeBinary() == null) {
                _state.value =
                    GeminiCliState.NOT_INSTALLED

                return@withContext Result.failure(
                    IllegalStateException(
                        "Node.js is missing from the Linux prefix. " +
                            "Install Node $MIN_NODE_MAJOR+ first.",
                    ),
                )
            }

            val nodeCheck = nodeMajorVersion()

            if (
                nodeCheck != null &&
                nodeCheck < MIN_NODE_MAJOR
            ) {
                _state.value =
                    GeminiCliState.NOT_INSTALLED

                return@withContext Result.failure(
                    IllegalStateException(
                        "Gemini CLI requires Node " +
                            "$MIN_NODE_MAJOR+. " +
                            "Found Node $nodeCheck in the Linux prefix.",
                    ),
                )
            }

            /*
             * Invoke npm through the prefix Node instead of the `npm` shim:
             * its `#!/usr/bin/env node` shebang resolves against the *root*
             * prefix, which the app uid cannot exec, and npm defaults its global
             * prefix to node's baked-in node_prefix (also the root prefix), so
             * every install there fails with EACCES without an explicit --prefix.
             */
            val npmCli =
                File(
                    linuxEnv.prefix,
                    "lib/node_modules/npm/bin/npm-cli.js",
                )

            val script =
                buildString {
                    append("set -e\n")
                    append(
                        "export npm_config_yes=true\n",
                    )
                    append(
                        "'${nodeBinary()?.absolutePath}' " +
                            "'${npmCli.absolutePath}' install -g " +
                            "--prefix '${linuxEnv.prefix.absolutePath}' " +
                            // node-pty needs node-gyp and cannot build on
                            // Android; headless -p/--prompt does not need it.
                            "--ignore-scripts " +
                            "@google/gemini-cli@latest\n",
                    )
                }

            val proc =
                linuxEnv
                    .startShell(
                        script,
                        linuxEnv.prefix,
                    )
                    .first

            val out = StringBuffer()
            val err = StringBuffer()

            val t1 =
                readerThread(
                    proc.inputStream,
                    out,
                )

            val t2 =
                readerThread(
                    proc.errorStream,
                    err,
                )

            val finished =
                proc.waitFor(
                    INSTALL_TIMEOUT_MINUTES,
                    TimeUnit.MINUTES,
                )

            if (!finished) {
                proc.destroyForcibly()

                t1.join(1_000)
                t2.join(1_000)

                _state.value =
                    GeminiCliState.NOT_INSTALLED

                return@withContext Result.failure(
                    IllegalStateException(
                        "npm install timed out",
                    ),
                )
            }

            t1.join(1_000)
            t2.join(1_000)

            if (
                proc.exitValue() != 0 ||
                geminiBinary()?.isFile != true ||
                geminiEntry()?.isFile != true
            ) {
                _state.value =
                    GeminiCliState.NOT_INSTALLED

                val message =
                    bestError(
                        err,
                        out,
                        "npm install exited " +
                            proc.exitValue(),
                    )

                return@withContext Result.failure(
                    IllegalStateException(
                        "npm install failed: $message",
                    ),
                )
            }

            val smoke =
                runCliCapture(
                    argv =
                        cliArgv(
                            listOf("--version"),
                        )
                            ?: return@withContext Result.failure(
                                IllegalStateException(
                                    "Gemini CLI entrypoint is missing",
                                ),
                            ),
                    timeoutSeconds = 30,
                )

            refresh()

            if (
                smoke.first != 0 ||
                smoke.second.isBlank()
            ) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Gemini CLI installed but " +
                            "version smoke-test failed: " +
                            smoke.third.takeLast(600),
                    ),
                )
            }

            Result.success(Unit)
        }

    /**
     * Configure official Gemini API-key auth inside
     * the app-private CLI home.
     *
     * The key is never returned, logged or placed in argv.
     */
    fun configureApiKey(
        apiKey: String,
    ): Result<Unit> =
        runCatching {
            val key = apiKey.trim()

            require(key.isNotEmpty()) {
                "Gemini API key is empty"
            }

            require(
                !key.contains('\n') &&
                    !key.contains('\r'),
            ) {
                "Gemini API key contains a newline"
            }

            geminiConfigDir.mkdirs()

            val escaped =
                key
                    .replace(
                        "\\",
                        "\\\\",
                    )
                    .replace(
                        "\"",
                        "\\\"",
                    )

            apiEnvFile.writeText(
                "GEMINI_API_KEY=\"$escaped\"\n",
            )

            lockDown(apiEnvFile)

            selectAuthType(
                AUTH_API_KEY,
            ).getOrThrow()

            refresh()
        }

    /** Remove only the app-private Gemini API key. */
    fun clearApiKey() {
        runCatching {
            apiEnvFile.delete()
        }

        refresh()
    }

    /**
     * Run the real Gemini CLI Google OAuth flow.
     *
     * We preselect `oauth-personal`, start Gemini without
     * CI/headless forcing, watch for the Google consent URL,
     * and launch that URL using Android's normal browser intent.
     */
    suspend fun signIn(
        timeoutMs: Int = OAUTH_TIMEOUT_MS,
    ): String {
        var result: String = ""
        withContext(Dispatchers.IO) {
            fun fail(message: String): String {
                refresh()
                return message
            }

            if (geminiEntry() == null) {
                result = fail("Gemini CLI is not installed yet.")
                return@withContext
            }

            selectAuthType(
                AUTH_OAUTH,
            ).getOrElse {
                result = fail("Could not prepare Gemini OAuth settings: " + it.message)
                return@withContext
            }

            /*
             * Avoid cloud-project variables accidentally
             * changing personal OAuth behaviour.
             */
            val launch =
                cliArgv(emptyList())
                    ?: run {
                        result = fail("Gemini CLI is not installed yet.")
                        return@withContext
                    }

            val proc =
                startCli(
                    argv = launch,
                    extraEnv =
                        mapOf(
                            "GOOGLE_CLOUD_PROJECT" to "",
                            "GOOGLE_CLOUD_PROJECT_ID" to "",
                        ),
                )
                    ?: run {
                        result = fail("Could not start the Gemini CLI.")
                        return@withContext
                    }

            val out = StringBuffer()
            val err = StringBuffer()

            val t1 =
                readerThread(
                    proc.inputStream,
                    out,
                )

            val t2 =
                readerThread(
                    proc.errorStream,
                    err,
                )

            val deadline =
                System.currentTimeMillis() +
                    timeoutMs

            var browserOpened = false
            var authUrl: String? = null

            while (
                proc.isAlive &&
                System.currentTimeMillis() < deadline
            ) {
                val combined =
                    synchronized(out) {
                        out.toString()
                    } +
                        "\n" +
                        synchronized(err) {
                            err.toString()
                        }

                val found =
                    authUrl
                        ?: extractGoogleAuthUrl(
                            combined,
                        )

                if (
                    found != null &&
                    authUrl == null
                ) {
                    authUrl = found
                }

                if (
                    !browserOpened &&
                    found != null
                ) {
                    browserOpened =
                        openInBrowser(found)

                    if (!browserOpened) {
                        proc.destroyForcibly()

                        t1.join(1_000)
                        t2.join(1_000)

                        refresh()

                        result = buildString {
                            append(
                                "Open this URL in your browser " +
                                    "to finish Gemini sign-in:\n",
                            )
                            append(found)
                        }.toString()
                        return@withContext
                    }
                }

                if (
                    proc.waitFor(
                        250,
                        TimeUnit.MILLISECONDS,
                    )
                ) {
                    break
                }
            }

            if (proc.isAlive) {
                proc.destroyForcibly()
            }

            t1.join(1_000)
            t2.join(1_000)

            refresh()

            if (hasOAuthCredentials()) {
                result = "Gemini CLI Google sign-in completed."
                return@withContext
            }

            val combined =
                buildString {
                    append(
                        synchronized(out) {
                            out
                                .toString()
                                .trim()
                        },
                    )

                    val e =
                        synchronized(err) {
                            err
                                .toString()
                                .trim()
                        }

                    if (e.isNotEmpty()) {
                        if (isNotEmpty()) {
                            append('\n')
                        }

                        append(e)
                    }
                }.trim()

            result = when {
                combined.isNotEmpty() ->
                    combined.takeLast(
                        MAX_USER_MESSAGE,
                    )

                authUrl != null ->
                    "Gemini sign-in did not complete. " +
                        "Re-open the authorization flow " +
                        "and finish the browser callback."

                else ->
                    "Gemini CLI did not provide an OAuth URL. " +
                        "API-key auth can be configured instead."
            }
        }
        return result
    }

    /**
     * Clear OAuth, API-key auth,
     * and selected auth mode.
     */
    fun signOut() {
        runCatching {
            oauthTokenFile().delete()
        }

        runCatching {
            File(
                geminiConfigDir,
                "google_accounts.json",
            ).delete()
        }

        runCatching {
            apiEnvFile.delete()
        }

        runCatching {
            clearSelectedAuthType()
        }

        refresh()
    }

    /**
     * Start a Gemini CLI process with the isolated
     * app-private home.
     *
     * `workingDirectory` lets the provider run Gemini against
     * a real project instead of accidentally scanning the
     * credential/config directory.
     */
    fun startCli(
        argv: Array<String>,
        extraEnv: Map<String, String> =
            emptyMap(),
        workingDirectory: File =
            geminiHome,
    ): Process {
        val pb =
            ProcessBuilder(argv.toList())
        pb.directory(workingDirectory)
        val env = pb.environment()
        env.clear()
        processEnv().forEach { entry ->
            val i = entry.indexOf('=')
            if (i > 0) env[entry.substring(0, i)] = entry.substring(i + 1)
        }
        extraEnv.forEach { (k, v) -> env[k] = v }
        return pb.start()
    }

    /** Best-effort error summary for failed installs. */
    private fun bestError(err: StringBuffer, out: StringBuffer, fallback: String): String {
        val e = synchronized(err) { err.toString().trim() }
        if (e.isNotEmpty()) return e.takeLast(MAX_DIAG_CHARS)
        val o = synchronized(out) { out.toString().trim() }
        if (o.isNotEmpty()) return o.takeLast(MAX_DIAG_CHARS)
        return fallback
    }

    /** Drain a process stream into a buffer on a daemon thread. */
    private fun readerThread(stream: java.io.InputStream, into: StringBuffer): Thread {
        val t = Thread {
            runCatching {
                val reader = stream.bufferedReader()
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    synchronized(into) { into.append(buf, 0, n) }
                }
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    /** Parse the installed Node's major version, or null when unavailable. */
    private fun nodeMajorVersion(): Int? = runCatching {
        val node = nodeBinary() ?: return null
        val proc = ProcessBuilder(listOf(node.absolutePath, "--version"))
            .redirectErrorStream(true)
            .start()
        val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return null
        }
        val out = proc.inputStream.bufferedReader().readText().trim()
        val m = Regex("""v?(\d+)""").find(out) ?: return null
        m.groupValues[1].toIntOrNull()
    }.getOrNull()

    /** Restrict a credential file to owner read/write. */
    private fun lockDown(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    private fun selectedAuthTypeFile(): File = File(geminiConfigDir, "selected_auth_type")

    private fun selectAuthType(authType: String): Result<Unit> = runCatching<Unit> {
        geminiConfigDir.mkdirs()
        val f = settingsFile
        val root =
            runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }

        root.put(
            "security",
            JSONObject().put(
                "auth",
                JSONObject().put(
                    "selectedType",
                    authType.trim(),
                ),
            ),
        )

        f.writeText(root.toString(2) + "\n")
        lockDown(f)
        // Legacy marker from older CLI builds; harmless, but no longer read.
        runCatching { selectedAuthTypeFile().delete() }
        refreshState()
    }

    private fun clearSelectedAuthType() {
        runCatching { selectedAuthTypeFile().delete() }

        // Leave any unrelated settings intact; only clear our own auth key.
        runCatching {
            val root = JSONObject(settingsFile.readText())
            root.optJSONObject("security")
                ?.optJSONObject("auth")
                ?.remove("selectedType")

            settingsFile.writeText(root.toString(2) + "\n")
            lockDown(settingsFile)
        }
    }

    private fun extractGoogleAuthUrl(text: String): String? {
        val m = Regex("""https://[^\s"']*accounts\.google\.com[^\s"']*""").find(text)
        return m?.value
    }

    private fun openInBrowser(url: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun runCliCapture(argv: Array<String>, timeoutSeconds: Long): Triple<Int, String, String> {
        val proc = startCli(argv) ?: return Triple(-1, "", "could not start CLI")
        val out = StringBuffer()
        val err = StringBuffer()
        val t1 = readerThread(proc.inputStream, out)
        val t2 = readerThread(proc.errorStream, err)
        val finished = proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        t1.join(1_000)
        t2.join(1_000)
        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
        return Triple(
            code,
            synchronized(out) { out.toString() }.trim().takeLast(MAX_DIAG_CHARS),
            synchronized(err) { err.toString() }.trim().takeLast(MAX_DIAG_CHARS),
        )
    }

    private companion object {
        /** Minimum supported Node major for @google/gemini-cli. */
        const val MIN_NODE_MAJOR = 20

        /** Install step budget; npm on a phone needs room. */
        const val INSTALL_TIMEOUT_MINUTES = 15L

        /** OAuth round-trip budget. */
        const val OAUTH_TIMEOUT_MS = 120_000

        /** Cap on surfaced CLI/npm diagnostics. */
        const val MAX_DIAG_CHARS = 2_048

        /** Cap on surfaced sign-in output. */
        const val MAX_USER_MESSAGE = 4_096

        /** Auth-mode marker for API-key auth. */
        const val AUTH_API_KEY = "gemini-api-key"

        /** Auth-mode marker for Google OAuth. */
        const val AUTH_OAUTH = "oauth-personal"
    }
}

