package com.androidharness.app.data.env

import android.content.Context
import android.os.Build
import android.os.Environment
import com.androidharness.app.tools.ShellPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Classifies a filesystem path into one of the regions the shell tiers can reach. */
object PathClassifier {
    enum class Region { APP_DATA, SHARED_STORAGE, SYSTEM }

    fun regionOf(path: String, internalDataRoot: String): Region = when {
        path == internalDataRoot || path.startsWith("$internalDataRoot/") -> Region.APP_DATA
        path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/") -> Region.SHARED_STORAGE
        else -> Region.SYSTEM
    }
}

/** Which engine actually runs the shell command. */
enum class ExecutionTier {
    TERMUX_SSH,

    /**
     * Root (uid 0) running Android system shells/binaries only (`/system/bin/sh`,
     * `id`, `pm`, `am`, `cmd`). Entered through `/system/bin/su -c` with a
     * sanitized PATH and no prefix `LD_LIBRARY_PATH`, so system binaries never
     * pick up bionic-prefixed libraries.
     */
    SYSTEM_ROOT,

    /**
     * Root (uid 0) running the bundled Linux toolchain: `/system/bin/su -c`
     * plus the deployed prefix's `LD_LIBRARY_PATH`/`PATH`, so `git`, `bash`,
     * `python3` and `npm` resolve their own libraries.
     */
    LINUX_ROOT,

    /** The app's own uid running the bundled Linux toolchain (with linker workaround). */
    APP_LINUX,

    /** Bare toybox sh when the app-side toolchain is not installed. */
    TOYBOX,
}

data class ShellRunResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val rawOutput: String,
    val rawStderr: String,
    val tier: ExecutionTier,
    val note: String?,
)

    /**
     * Decides which execution tier runs each shell command, based on where the
     * working directory lives and which privileges are currently available:
     *
     * - Root granted   -> [ExecutionTier.LINUX_ROOT] when the deployed Linux
     *   prefix is usable (real `git`/`python3`/`npm` as uid 0), otherwise
     *   [ExecutionTier.SYSTEM_ROOT] (Android system binaries as uid 0).
     * - No root        -> the app uid: [ExecutionTier.APP_LINUX] on shared
     *   storage and system paths, with an explanatory note.
     */
class ShellTierRouter(
    private val context: Context,
    private val root: RootManager,
    private val linuxEnv: LinuxEnvironmentManager,
) {

    /** "All files access" (MANAGE_EXTERNAL_STORAGE). Pre-API-30 apps were not scoped. */
    fun isAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    fun resolveTier(cwd: File): ExecutionTier {
        val region = PathClassifier.regionOf(cwd.absolutePath, linuxEnv.internalDataRoot.absolutePath)
        return when (region) {
            PathClassifier.Region.APP_DATA ->
                if (linuxEnv.isReady) ExecutionTier.APP_LINUX else ExecutionTier.TOYBOX
            PathClassifier.Region.SHARED_STORAGE,
            PathClassifier.Region.SYSTEM -> when {
                // Root reaches both /sdcard and system paths. Prefer the Linux
                // prefix tier so real toolchain commands work as uid 0; fall
                // back to plain system binaries when no prefix is deployed.
                root.isGranted() ->
                    if (linuxEnv.isReady) ExecutionTier.LINUX_ROOT else ExecutionTier.SYSTEM_ROOT
                isAllFilesAccess() -> if (linuxEnv.isReady) ExecutionTier.APP_LINUX else ExecutionTier.TOYBOX
                else -> if (linuxEnv.isReady) ExecutionTier.APP_LINUX else ExecutionTier.TOYBOX
            }
        }
    }

    /** A user-facing note explaining why the tier may be degraded, if so. */
    fun permissionNote(cwd: File, tier: ExecutionTier): String? {
        val region = PathClassifier.regionOf(cwd.absolutePath, linuxEnv.internalDataRoot.absolutePath)
        return when {
                tier == ExecutionTier.APP_LINUX &&
                region == PathClassifier.Region.SHARED_STORAGE &&
                !isAllFilesAccess() ->
                "[note: on this Android version the app can't reach shared storage without \"All files access\": expect permission errors here. Grant it in Settings → Storage access, or grant root for shell access.]"

            tier == ExecutionTier.TOYBOX &&
                region == PathClassifier.Region.SYSTEM &&
                !root.isGranted() ->
                "[note: this is a system path the app cannot touch on its own. Grant root to unlock it.]"

            tier == ExecutionTier.APP_LINUX &&
                region == PathClassifier.Region.SYSTEM ->
                "[note: running as the app user without root: system paths and pm/am/cmd are out of reach. Grant root to unlock them.]"

            else -> null
        }
    }

    /** Executes [command] with cwd [cwd] and returns a uniform result. */
    suspend fun run(command: String, cwd: File, timeoutMs: Int, maxOutput: Int): ShellRunResult =
        // Both tiers block for the whole run: they poll the child every 50ms,
        // join the reader threads and, in the root tiers, wait on the su
        // child. Callers include Compose scopes on the main dispatcher, which
        // would freeze the UI for the command's full duration, so the work
        // never inherits the caller's dispatcher.
        withContext(Dispatchers.IO) {
            val tier = resolveTier(cwd)
            // A command that invokes an Android system binary must never run in
            // a Linux tier: those tiers export the prefix LD_LIBRARY_PATH, and
            // bionic binaries that pick it up fail to link
            // ("CANNOT LINK EXECUTABLE \"cmd\": cannot locate symbol
            // \"Xzs_Construct\""). Downgrade to SYSTEM_ROOT, whose environment
            // is sanitized by RootManager.runSystemRaw. No-op when the tier is
            // already SYSTEM_ROOT or the command is not a system binary.
            val effectiveTier =
                if (tier == ExecutionTier.SYSTEM_ROOT ||
                    !SystemCommandClassifier.isSystemBinaryCommand(command)
                ) tier else ExecutionTier.SYSTEM_ROOT
            when (effectiveTier) {
                ExecutionTier.TERMUX_SSH -> error("SSH requires a captured workspace")
                ExecutionTier.LINUX_ROOT -> runLinuxRoot(command, cwd, timeoutMs, maxOutput)
                ExecutionTier.SYSTEM_ROOT -> runSystemRoot(command, cwd, timeoutMs, maxOutput)
                ExecutionTier.APP_LINUX -> runApp(command, cwd, timeoutMs, maxOutput, ExecutionTier.APP_LINUX)
                ExecutionTier.TOYBOX -> runApp(command, cwd, timeoutMs, maxOutput, ExecutionTier.TOYBOX)
            }
        }

    suspend fun runWorkspace(command: String, workspace: com.androidharness.app.workspace.WorkspaceFs,
                             timeoutMs: Int, maxOutput: Int): ShellRunResult {
        if (workspace is com.androidharness.app.workspace.SshFs) return workspace.run(command, timeoutMs = timeoutMs, maxOutput = maxOutput)
        return run(command, requireNotNull(workspace.shellRoot) { "This workspace has no shell" }, timeoutMs, maxOutput)
    }

    // --- root tiers --------------------------------------------------------

    /**
     * Provisions the exec-capable scratch dir from the root side. Best-effort;
     * the app-side init also creates it directly. 0700 root-owned: only root
     * (and adb) exec from it, and the app uid is walled off from
     * /data/local/tmp by DAC and SELinux anyway.
     */
    private suspend fun ensureRootScratch() {
        val scratch = ShellPolicy.SCRATCH_TMP
        root.runSystem(
            script = "mkdir -p '$scratch' && chmod 700 '$scratch'",
            timeoutMs = 10_000,
            maxBytes = 1_000,
        )
    }

    /**
     * SYSTEM_ROOT: run [command] as uid 0 through `/system/bin/su -c` using
     * Android system binaries only. Correct for `id`, `pm`, `am`, `cmd` and
     * anything that must not pick up the bundled Linux libraries.
     */
    private suspend fun runSystemRoot(
        command: String,
        cwd: File,
        timeoutMs: Int,
        maxOutput: Int,
    ): ShellRunResult {
        runCatching { ensureRootScratch() }
        val r = root.runSystem(
            script = command,
            dir = cwd.absolutePath,
            timeoutMs = timeoutMs,
            maxBytes = maxOutput,
        )
        return ShellRunResult(
            r.exitCode, r.timedOut, r.output, r.stderr, ExecutionTier.SYSTEM_ROOT,
            note = "[note: running as root with Android system binaries only. " +
                "Install the Linux environment in Settings → Terminal for bash/git/python/node here.]",
        )
    }

    /**
     * LINUX_ROOT: run [command] as uid 0 through `/system/bin/su -c` with the
     * deployed prefix's `LD_LIBRARY_PATH` and `PATH` exported, so the bundled
     * Linux toolchain (bash/git/python3/npm) resolves its own libraries. The
     * environment itself still comes from
     * [LinuxEnvironmentManager.tmpProcessEnv], so CA bundle, git templates and
     * HOME stay exactly as they were built.
     */
    private suspend fun runLinuxRoot(
        command: String,
        cwd: File,
        timeoutMs: Int,
        maxOutput: Int,
    ): ShellRunResult {
        // Self-heal: catch a stale or vanished deployed copy even when nothing
        // in-process changed the staging state (throttled internally).
        linuxEnv.verifyDeployedCopyThrottled(root)
        // The deployed copy has to come from the same package set this tier's
        // environment (tmpProcessEnv) is built for. Asking only whether it
        // exists accepts a copy that is missing the libraries the environment
        // names, and every binary started in it then dies in the linker with
        // `CANNOT LINK EXECUTABLE ... library "..." not found`.
        val expectedTag = linuxEnv.deployedTag()
        var toolchain = linuxEnv.isReady && root.isTmpPrefixDeployed(expectedTag)
        if (linuxEnv.isReady && !toolchain) {
            // One-time deploy of the toolchain to an exec-allowed location.
            linuxEnv.ensureShellDeploy(root)
            toolchain = root.isTmpPrefixDeployed(expectedTag)
        }
        // Bug 2 fix: make sure the designated exec-capable scratch dir exists
        // and is writable by both the root uid and the app uid.
        runCatching { ensureRootScratch() }

        val env = if (toolchain) {
            linuxEnv.tmpProcessEnv().map { "${it.key}=${it.value}" }.toTypedArray()
        } else null

        val r = root.runLinux(
            // bash is only available when the prefix is really deployed; a
            // real /system/bin/sh is the honest fallback otherwise.
            script = if (toolchain) {
                "exec '${linuxEnv.tmpPrefix}/bin/bash' -c " +
                    RootManager.shellQuote(command)
            } else {
                command
            },
            prefix = linuxEnv.tmpPrefix,
            env = env,
            dir = cwd.absolutePath,
            timeoutMs = timeoutMs,
            maxBytes = maxOutput,
        )
        if (r.exitCode == -1 && r.output.contains("failed to launch root shell")) {
            // su refused mid-flight: fall back to the app uid tier.
            val fb = runApp(command, cwd, timeoutMs, maxOutput, ExecutionTier.APP_LINUX)
            return ShellRunResult(
                exitCode = fb.exitCode,
                timedOut = fb.timedOut,
                rawOutput = fb.rawOutput,
                rawStderr = fb.rawStderr,
                tier = ExecutionTier.APP_LINUX,
                note = "[note: root was unavailable, so this ran as the app user: expect permission errors on this directory]",
            )
        }
        val note = if (!toolchain) {
            "[note: running as root with Android system binaries only: install the Linux environment in Settings → Terminal for bash/git/python/node here]"
        } else null
        return ShellRunResult(r.exitCode, r.timedOut, r.output, r.stderr, ExecutionTier.LINUX_ROOT, note)
    }

    // --- app-uid tier ------------------------------------------------------

    private suspend fun runApp(
        command: String,
        cwd: File,
        timeoutMs: Int,
        maxOutput: Int,
        tier: ExecutionTier,
    ): ShellRunResult {
        val process = try {
            linuxEnv.startShell(command, cwd).first
        } catch (e: Exception) {
            return ShellRunResult(
                exitCode = -1,
                timedOut = false,
                rawOutput = "Failed to start shell (directory not reachable by the app?): ${e.message}",
                rawStderr = "",
                tier = ExecutionTier.TOYBOX,
                note = permissionNote(cwd, tier),
            )
        }

        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val out = Thread { gobble(process.inputStream, stdout, maxOutput) }
        val err = Thread { gobble(process.errorStream, stderr, maxOutput) }
        listOf(out, err).forEach { it.isDaemon = true; it.start() }

        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        var exitCode = -1
        while (true) {
            if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue()
                break
            }
            if (System.currentTimeMillis() > deadline) {
                timedOut = true
                break
            }
        }

        val pid = runCatching {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(process)
        }.getOrNull()
        if (pid != null && pid > 0) {
            if (timedOut) {
                killGroup(pid)
                process.destroyForcibly()
                process.waitFor(200, TimeUnit.MILLISECONDS)
            } else {
                // Command finished normally: clean up any detached/orphan grandchildren in this process group
                cleanOrphanGroup(pid)
            }
        }
        reapZombies()

        out.join(300)
        err.join(300)
        reapZombies()

        return ShellRunResult(
            exitCode,
            timedOut,
            stdout.toString(),
            stderr.toString(),
            tier,
            permissionNote(cwd, tier),
        )
    }

    private fun gobble(stream: java.io.InputStream, into: StringBuffer, max: Int) {
        try {
            val buf = CharArray(4096)
            stream.bufferedReader().use { reader ->
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    synchronized(into) {
                        if (into.length < max) {
                            val toAppend = minOf(n, max - into.length)
                            into.append(buf, 0, toAppend)
                        }
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
            val k = Runtime.getRuntime().exec(arrayOf(killBin, "-9", "-$pid"))
            k.waitFor(100, TimeUnit.MILLISECONDS)
        }
        val pkillBin = if (File("/system/bin/pkill").exists()) "/system/bin/pkill" else "pkill"
        runCatching {
            val pkillS = Runtime.getRuntime().exec(arrayOf(pkillBin, "-9", "-s", pid.toString()))
            pkillS.waitFor(100, TimeUnit.MILLISECONDS)
        }
        runCatching {
            val pkillP = Runtime.getRuntime().exec(arrayOf(pkillBin, "-9", "-P", pid.toString()))
            pkillP.waitFor(100, TimeUnit.MILLISECONDS)
        }
        runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
        runCatching {
            val k = Runtime.getRuntime().exec(arrayOf(killBin, "-9", pid.toString()))
            k.waitFor(100, TimeUnit.MILLISECONDS)
        }
    }

    private fun cleanOrphanGroup(pid: Int) {
        runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
        val killBin = if (File("/system/bin/kill").exists()) "/system/bin/kill" else "kill"
        runCatching {
            val k = Runtime.getRuntime().exec(arrayOf(killBin, "-9", "-$pid"))
            k.waitFor(100, TimeUnit.MILLISECONDS)
        }
        val pkillBin = if (File("/system/bin/pkill").exists()) "/system/bin/pkill" else "pkill"
        runCatching {
            val pkillS = Runtime.getRuntime().exec(arrayOf(pkillBin, "-9", "-s", pid.toString()))
            pkillS.waitFor(100, TimeUnit.MILLISECONDS)
        }
    }

    private val waitpidMethod by lazy {
        runCatching {
            android.system.Os::class.java.methods.firstOrNull { it.name == "waitpid" }
        }.getOrNull()
    }

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
}
