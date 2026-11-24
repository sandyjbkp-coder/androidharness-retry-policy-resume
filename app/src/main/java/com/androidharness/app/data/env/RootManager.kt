package com.androidharness.app.data.env

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Whether root is available to the app on this device. */
enum class RootState {
    /** No root manager (Magisk/SuperSU/KernelSU) responded at all. */
    UNAVAILABLE,

    /** A root manager responded but refused: a superuser prompt is pending. */
    DENIED,

    /** `/system/bin/su -c` executes commands as uid 0. */
    GRANTED,
}

/** Result of one root command execution. */
data class RootResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val output: String,
    val stderr: String = "",
)

/**
 * Runs privileged commands as real Android root, entering through
 * `/system/bin/su -c` only.
 *
 * Two deliberately separate execution paths, because a Linux toolchain binary
 * and an Android system binary need opposite environments:
 *
 * - [runSystem] runs Android system shells/binaries (`/system/bin/sh`, `id`,
 *   `pm`, `am`, `cmd`) under a sanitized environment. It never points
 *   `LD_LIBRARY_PATH` at the bundled prefix, so a root command can never drag
 *   bionic-prefixed libraries into a system binary.
 * - [runLinux] enters root the same way but then explicitly exports the
 *   bundled prefix's `LD_LIBRARY_PATH=<prefix>/lib` and
 *   `PATH=<prefix>/bin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin`
 *   before running the command, so `git`, `bash`, `python3` and friends
 *   resolve their own `libandroid-support.so` and friends instead of dying in
 *   the loader.
 *
 * Both paths reuse the process-group/zombie-reaping approach that
 * `HarnessUserService` proved works on this codebase.
 */
class RootManager(
    private val context: Context,
) {
    /**
     * Where the root-executed toolchain is deployed. Per-package so two
     * installed builds never share (and clobber) one copy, mirroring
     * `LinuxEnvironmentManager.tmpBase`.
     */
    private val deployedBase: String =
        LinuxEnvironmentManager.deployedBaseFor(context.packageName)

    private val _state = MutableStateFlow(RootState.UNAVAILABLE)
    val state: StateFlow<RootState> = _state.asStateFlow()

    fun isGranted(): Boolean = _state.value == RootState.GRANTED

    /** True once root answered, whatever the answer was. */
    fun isServiceReady(): Boolean = isGranted()

    /**
     * Asks the user for root by running a harmless probe. Root has no
     * grant-API the way a binder-based privilege server does, so the prompt only appears when
     * something actually runs through `su`: this executes
     * `/system/bin/id`, which makes the superuser manager show its dialog and
     * reports back what it decided. [state] is updated from the real result.
     */
    suspend fun requestRootAccess(): Boolean {
        val r = runSystem(
            script = "exec /system/bin/id",
            timeoutMs = 120_000,
            maxBytes = 2_000,
        )
        val granted = r.exitCode == 0 && r.output.contains("uid=0(")
        probeCache = granted
        probeAt = System.currentTimeMillis()
        _state.value = when {
            granted -> RootState.GRANTED
            rootManagerPresent() -> RootState.DENIED
            else -> RootState.UNAVAILABLE
        }
        return granted
    }

    @Volatile private var probeCache: Boolean? = null
    @Volatile private var probeAt: Long = 0L

    /**
     * Probes root once and caches for [PROBE_TTL_MS]. A superuser prompt is
     * modal, so probing on every command would stack prompts; the cache lets
     * the user grant (or refuse) root and see the change within a few seconds.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        probeCache?.let { if (now - probeAt < PROBE_TTL_MS) return@withContext it }
        val ok = probeNow()
        probeCache = ok
        probeAt = now
        _state.value = when {
            ok -> RootState.GRANTED
            rootManagerPresent() -> RootState.DENIED
            else -> RootState.UNAVAILABLE
        }
        ok
    }

    private fun rootManagerPresent(): Boolean =
        File(SU).exists() || File(SU_FALLBACK).exists()

    private suspend fun probeNow(): Boolean {
        val r = runSystemRaw(
            script = "id -u",
            env = null,
            dir = null,
            timeoutMs = 8_000,
            maxBytes = 512,
        )
        return r.exitCode == 0 && r.output.trim().lines().firstOrNull()?.trim() == "0"
    }

    /**
     * SYSTEM_ROOT path: a sanitized root shell that only ever sees Android
     * system binaries. No prefix `LD_LIBRARY_PATH`/`PATH` is exported, so
     * `pm`, `am`, `cmd` and `id` run with stock bionic, not the bundled prefix.
     */
    suspend fun runSystem(
        script: String,
        env: Array<String>? = null,
        dir: String? = null,
        timeoutMs: Int = 120_000,
        maxBytes: Int = 60_000,
    ): RootResult = withContext(Dispatchers.IO) {
        runSystemRaw(script, env, dir, timeoutMs, maxBytes)
    }

    private suspend fun runSystemRaw(
        script: String,
        env: Array<String>?,
        dir: String?,
        timeoutMs: Int,
        maxBytes: Int,
    ): RootResult {
        val prelude = buildString {
            // Keep a root shell self-contained: the prefix must never leak in.
            append("PATH=/system/bin:/system/xbin:/vendor/bin:/vendor/xbin; ")
            append("export PATH; ")
            append("unset LD_LIBRARY_PATH; ")
            append("unset LD_PRELOAD; ")
            env?.forEach { e ->
                val i = e.indexOf('=')
                if (i > 0) {
                    val k = e.substring(0, i)
                    // A caller-supplied PATH/LD_LIBRARY_PATH would undo the
                    // point of this tier, so those two keys are dropped.
                    if (k != "PATH" && k != "LD_LIBRARY_PATH" && k != "LD_PRELOAD") {
                        append(k).append("=").append(shellQuote(e.substring(i + 1))).append("; export $k; ")
                    }
                }
            }
        }
        return execThroughSu(
            innerShell = "/system/bin/sh",
            script = prelude + script,
            env = null,
            dir = dir,
            timeoutMs = timeoutMs,
            maxBytes = maxBytes,
        )
    }

    /**
     * LINUX_ROOT path: root, plus the bundled Linux prefix environment. This is
     * the only path that may execute a prefix tool (`git --version`, `bash`,
     * `python3`, `npm`).
     * [prefix] must be the deployed, exec-capable Linux prefix
     * (see `LinuxEnvironmentManager.tmpPrefix`); [env] carries the extra
     * variables `tmpProcessEnv()` already builds (HOME/TMPDIR/PREFIX/…).
     */
    suspend fun runLinux(
        script: String,
        prefix: String,
        env: Array<String>? = null,
        dir: String? = null,
        timeoutMs: Int = 120_000,
        maxBytes: Int = 60_000,
    ): RootResult = withContext(Dispatchers.IO) {
        val prelude = buildString {
            append("PATH=").append(shellQuote(rootLinuxPath(prefix))).append("; export PATH; ")
            append("LD_LIBRARY_PATH=").append(shellQuote("$prefix/lib")).append("; export LD_LIBRARY_PATH; ")
            append("LD_PRELOAD=; export LD_PRELOAD; ")
            // The deployed prefix ships its own CA bundle, so TLS works for
            // curl/python/git/node without the app process's env.
            env?.forEach { e ->
                val i = e.indexOf('=')
                if (i > 0) {
                    val k = e.substring(0, i)
                    if (k != "PATH" && k != "LD_LIBRARY_PATH" && k != "LD_PRELOAD") {
                        append(k).append("=").append(shellQuote(e.substring(i + 1))).append("; export $k; ")
                    }
                }
            }
        }
        return@withContext execThroughSu(
            innerShell = "/system/bin/sh",
            script = prelude + script,
            env = null,
            dir = dir,
            timeoutMs = timeoutMs,
            maxBytes = maxBytes,
        )
    }

    /**
     * The single place root is entered: `/system/bin/su -c`. Everything the app
     * runs with elevated privilege goes through [execThroughSu], so there is
     * one place to audit and no code path that uses a bare `su`.
     */
    private suspend fun execThroughSu(
        innerShell: String,
        script: String,
        env: Array<String>?,
        dir: String?,
        timeoutMs: Int,
        maxBytes: Int,
    ): RootResult = withContext(Dispatchers.IO) {
        val suBin = suBinary()
        val sb = StringBuilder()
        if (dir != null) {
            sb.append("cd ").append(shellQuote(dir)).append(" && ")
        }
        sb.append("exec ").append(innerShell).append(" -c ").append(shellQuote(script))
        val full = sb.toString()

        val process = try {
            startProcess(arrayOf(suBin, "-c", full), env, dir)
        } catch (e: Exception) {
            Log.w(TAG, "su exec failed to start", e)
            return@withContext RootResult(-1, false, "failed to launch root shell: ${e.message}")
        }

        val limit = maxBytes.coerceIn(1024, 512_000)
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val out = Thread { gobble(process.inputStream, stdout, limit) }
        val err = Thread { gobble(process.errorStream, stderr, limit) }
        listOf(out, err).forEach { it.isDaemon = true; it.start() }

        val startedAt = System.currentTimeMillis()
        var timedOut = false
        var exit = -1
        while (true) {
            if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                exit = process.exitValue()
                break
            }
            if (System.currentTimeMillis() - startedAt > timeoutMs) {
                timedOut = true
                break
            }
        }

        val pid = pidOf(process)
        if (pid != null && pid > 0) {
            if (timedOut) {
                killGroup(pid)
                process.destroyForcibly()
                process.waitFor(200, TimeUnit.MILLISECONDS)
            } else {
                cleanOrphanGroup(pid)
            }
        }
        reapZombies()
        out.join(300)
        err.join(300)
        reapZombies()

        RootResult(exit, timedOut, stdout.toString().trimEnd('\n'), stderr.toString().trimEnd('\n'))
    }

    private fun startProcess(
        argv: Array<String>,
        env: Array<String>?,
        dir: String?,
    ): Process {
        val setsid = if (File("/system/bin/setsid").exists()) "/system/bin/setsid" else null
        val cmdList = if (setsid != null) {
            // setsid gives the child its own process group, so a timeout can
            // kill the whole tree (grandchildren included) with kill(-pid).
            listOf(setsid, "/system/bin/sh", "-c", "exec \"\$@\"", "sh", *argv)
        } else {
            argv.toList()
        }
        val pb = ProcessBuilder(cmdList)
        dir?.let { pb.directory(File(it)) }
        if (env != null) {
            val e = pb.environment()
            e.clear()
            env.forEach { entry ->
                val i = entry.indexOf('=')
                if (i > 0) e[entry.substring(0, i)] = entry.substring(i + 1)
            }
        }
        return pb.start()
    }

    private fun pidOf(process: Process): Int? = runCatching {
        val m = process.javaClass.getDeclaredMethod("pid").apply { isAccessible = true }
        m.invoke(process) as? Int
    }.getOrNull()

    private fun gobble(stream: java.io.InputStream, into: StringBuffer, max: Int) {
        try {
            val buf = CharArray(4096)
            stream.bufferedReader().use { reader ->
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    synchronized(into) {
                        if (into.length < max) into.append(buf, 0, minOf(n, max - into.length))
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun killGroup(pid: Int) {
        runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
        val killBin = if (File("/system/bin/kill").exists()) "/system/bin/kill" else "kill"
        runCatching {
            Runtime.getRuntime().exec(arrayOf(killBin, "-9", "-$pid")).waitFor(100, TimeUnit.MILLISECONDS)
        }
        val pkillBin = if (File("/system/bin/pkill").exists()) "/system/bin/pkill" else "pkill"
        runCatching {
            Runtime.getRuntime().exec(arrayOf(pkillBin, "-9", "-s", pid.toString())).waitFor(100, TimeUnit.MILLISECONDS)
        }
        runCatching {
            Runtime.getRuntime().exec(arrayOf(pkillBin, "-9", "-P", pid.toString())).waitFor(100, TimeUnit.MILLISECONDS)
        }
        runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
    }

    private fun cleanOrphanGroup(pid: Int) {
        runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
        val killBin = if (File("/system/bin/kill").exists()) "/system/bin/kill" else "kill"
        runCatching {
            Runtime.getRuntime().exec(arrayOf(killBin, "-9", "-$pid")).waitFor(100, TimeUnit.MILLISECONDS)
        }
        val pkillBin = if (File("/system/bin/pkill").exists()) "/system/bin/pkill" else "pkill"
        runCatching {
            Runtime.getRuntime().exec(arrayOf(pkillBin, "-9", "-s", pid.toString())).waitFor(100, TimeUnit.MILLISECONDS)
        }
    }

    private val waitpidMethod by lazy {
        runCatching { android.system.Os::class.java.methods.firstOrNull { it.name == "waitpid" } }.getOrNull()
    }

    /**
     * Reaps children the app is a subreaper parent of. The root shell is a
     * grandchild here, so this mirrors the service's reaper: a long-running
     * chat session that spawns many commands would otherwise accumulate
     * zombies that `Process.destroy` never collects.
     */
    private fun reapZombies() {
        val method = waitpidMethod ?: return
        val paramTypes = method.parameterTypes
        val dummyStatus = if (paramTypes.size >= 2 && paramTypes[1] != Int::class.javaPrimitiveType) {
            runCatching { paramTypes[1].getDeclaredConstructor().newInstance() }.getOrNull()
        } else null
        val wnohang = 1
        while (true) {
            val res = runCatching {
                if (paramTypes.size == 3) {
                    (method.invoke(null, -1, dummyStatus, wnohang) as? Number)?.toInt() ?: 0
                } else if (paramTypes.size == 2) {
                    (method.invoke(null, -1, wnohang) as? Number)?.toInt() ?: 0
                } else 0
            }.getOrDefault(0)
            if (res <= 0) break
        }
    }

    // ------------------------------------------------------------------
    // Deploying the Linux prefix to an exec-capable location
    // ------------------------------------------------------------------

    @Volatile private var tmpPrefixDeployed = false
    @Volatile private var deployedTag: String? = null

    /**
     * True while the deploy script is gutting and re-extracting the prefix:
     * during that window the deployed tree is partially absent even though
     * [isTmpPrefixDeployed] may still say true, so status tools must not trust
     * it.
     */
    @Volatile private var deploying = false

    fun isDeployInProgress(): Boolean = deploying

    private val deployLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Deploys (or refreshes) the Linux toolchain at [tmpBase] by untarring
     * [stagingTarPath] there. No-op when the deployed copy already matches
     * [tag], which is the package set plus the content hash of the tarball it
     * must have come from.
     *
     * Root replaces the old in-server deploy agent: the untar runs as uid 0
     * through `/system/bin/su -c`, so /data/local/tmp is writable and the
     * result is exec-capable.
     */
    suspend fun ensureTmpPrefix(stagingTarPath: String, tag: String): Boolean =
        deployLock.withLock {
            withContext(Dispatchers.IO) {
                deployLockRun(stagingTarPath, tag)
            }
        }

    private suspend fun deployLockRun(stagingTarPath: String, tag: String): Boolean {
        val tmpBase = deployedBase
        val caSource = File(File(stagingTarPath).parentFile, "etc/tls/cacert.pem")
        val caInstall = if (caSource.isFile) {
            "mkdir -p \"$tmpBase/linux/etc/tls\" && cp \"${caSource.absolutePath}\" " +
                "\"$tmpBase/linux/etc/tls/cacert.pem\" && "
        } else ""
        val check = runSystem(
            script = "test -x \"$tmpBase/linux/bin/bash\" && cat \"$tmpBase/.harness-hash\"",
            timeoutMs = 15_000,
            maxBytes = 2_000,
        )
        if (check.exitCode == 0 && check.output.trim() == tag) {
            tmpPrefixDeployed = true
            deployedTag = tag
            return true
        }
        deploying = true
        try {
            val script = buildString {
                append("rm -rf \"$tmpBase\" && ")
                append("mkdir -p \"$tmpBase\" && ")
                append("tar -xzf \"$stagingTarPath\" -C \"$tmpBase\" && ")
                append(caInstall)
                append("chmod -R 755 \"$tmpBase\" && ")
                // /data/local/tmp is o+x-traversable and the blanket 755 leaves
                // everything group/other-readable: without this, ANY app that
                // guesses the path could read the token-bearing copies below.
                append("chmod 700 \"$tmpBase\" \"$tmpBase/linux\" && ")
                append("chmod 700 \"$tmpBase/linux/home/.config\" \"$tmpBase/linux/home/.config/gh\" 2>/dev/null; ")
                append("chmod 600 \"$tmpBase/linux/home/.gh-token\" " +
                    "\"$tmpBase/linux/home/.config/gh/hosts.yml\" " +
                    "\"$tmpBase/linux/etc/gitconfig\" 2>/dev/null; ")
                append("echo ").append(shellQuote(tag)).append(" > \"$tmpBase/.harness-hash\" && ")
                append("test -x \"$tmpBase/linux/bin/bash\" && ")
                append("echo DEPLOY_OK")
            }
            val r = runSystem(script = script, timeoutMs = 300_000, maxBytes = 20_000)
            val ok = r.exitCode == 0 && r.output.contains("DEPLOY_OK")
            if (ok) {
                tmpPrefixDeployed = true
                deployedTag = tag
            }
            return ok
        } finally {
            deploying = false
        }
    }

    /**
     * Whether the root-executed toolchain is present under [tmpBase].
     *
     * Pass [expectedTag] (from `LinuxEnvironmentManager.deployedTag()`) to also
     * require the deployed copy to have come from those exact bytes. Anything
     * that runs the deployed binaries with the app-side environment must ask
     * the strict question: a copy that only answers "bin/bash exists" can be
     * missing the very libraries that environment names, and the linker then
     * refuses every binary with
     * `CANNOT LINK EXECUTABLE ... library "..." not found`.
     */
    suspend fun isTmpPrefixDeployed(expectedTag: String? = null): Boolean {
        if (tmpPrefixDeployed && (expectedTag == null || deployedTag == expectedTag)) return true
        val tmpBase = deployedBase
        val script = if (expectedTag != null) {
            "test -x \"$tmpBase/linux/bin/bash\" && cat \"$tmpBase/.harness-hash\""
        } else {
            "test -x \"$tmpBase/linux/bin/bash\" && echo OK"
        }
        val r = runSystem(script = script, timeoutMs = 10_000, maxBytes = 2_000)
        val matched = r.exitCode == 0 &&
            if (expectedTag != null) r.output.trim() == expectedTag else r.output.contains("OK")
        if (matched) {
            tmpPrefixDeployed = true
            if (expectedTag != null) deployedTag = expectedTag
        } else {
            tmpPrefixDeployed = false
            deployedTag = null
        }
        return tmpPrefixDeployed
    }

    /** Checks a path from the root side (existence + listable). */
    suspend fun privilegedCanAccess(path: String): Boolean {
        val r = runSystem(
            script = "test -d " + shellQuote(path) + " && ls -l " + shellQuote(path) + " >/dev/null 2>&1 && echo OK",
            timeoutMs = 10_000,
            maxBytes = 2_000,
        )
        return r.exitCode == 0 && r.output.contains("OK")
    }

    /** Forces the next deploy check to compare hashes again. */
    fun invalidateDeployState() {
        tmpPrefixDeployed = false
        deployedTag = null
        probeCache = null
    }

    // ------------------------------------------------------------------
    // Detached background processes
    //
    // These used to live in an external server process, so a background job
    // survived the app being killed. A root child has the same property: it is
    // reparented to init when this app dies, so the same call sites keep
    // working without the in-server service.
    // ------------------------------------------------------------------

    /** Starts [cmd] detached via root, logging to [logPath]. Returns its pid. */
    suspend fun spawnDetached(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        logPath: String,
    ): Int? = withContext(Dispatchers.IO) {
        val log = File(logPath)
        runCatching { log.parentFile?.mkdirs() }
        val sb = StringBuilder()
        if (dir != null) sb.append("cd ").append(shellQuote(dir)).append(" && ")
        env?.forEach { e ->
            val i = e.indexOf('=')
            if (i > 0) {
                val k = e.substring(0, i)
                if (k != "LD_PRELOAD") {
                    sb.append(k).append("=").append(shellQuote(e.substring(i + 1))).append("; export $k; ")
                }
            }
        }
        // Redirect inside the root shell itself. Doing it in ProcessBuilder as
        // well would open the log twice, and the shell's own redirection is
        // what survives this app being killed: the child keeps the descriptor
        // and keeps appending on its own.
        sb.append("exec ")
            .append(cmd.joinToString(" ") { shellQuote(it) })
            .append(" >>").append(shellQuote(logPath)).append(" 2>&1")
        val process = runCatching {
            startProcess(arrayOf(suBinary(), "-c", sb.toString()), env, dir)
        }.getOrNull()
        process?.let { pidOf(it) }
    }

    suspend fun isProcessAlive(pid: Int): Boolean =
        File("/proc/$pid").exists()
    suspend fun killProcess(pid: Int): Boolean {
        killGroup(pid)
        return !File("/proc/$pid").exists()
    }

    companion object {
        private const val TAG = "RootManager"

        /** The ONLY root entry point. Plain `su` is never invoked. */
        const val SU = "/system/bin/su"
        private const val SU_FALLBACK = "su"

        /**
         * The absolute /system/bin/su when it exists, else a bare `su` resolved
         * through the root shell's own PATH. The app never prepends the bundled
         * Linux prefix to the PATH it uses to find su, so a Linux-side `su`
         * shadow can never be executed.
         */
        fun suBinary(): String = if (File(SU).exists()) SU else SU_FALLBACK

        private const val PROBE_TTL_MS = 4_000L

        /** Root PATH for prefix commands: prefix first, then Android system dirs. */
        fun rootLinuxPath(prefix: String): String =
            "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin"

        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}
