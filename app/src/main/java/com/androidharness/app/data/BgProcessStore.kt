package com.androidharness.app.data

import android.content.Context
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class BgProcessEntry(
    val id: Int,
    val command: String,
    val cwd: String,
    val logPath: String,
    /** uid-0 pid for root-spawned processes; -1 for app-tier. */
    val pid: Int = -1,
    /** "APP" = child of the app process; "ROOT" = spawned as uid 0. */
    val source: String = "APP",
    val startedAt: Long = 0L,
)

/**
 * Registry of detached background shell processes (dev servers, watchers…),
 * persisted to a JSON file so it survives app restarts.
 *
 * When root is granted, processes are spawned as uid 0 through
 * /system/bin/su ([BgProcessEntry.source] = ROOT), so they keep running even if
 * the app is killed, and are re-adopted on the next launch. Without root they
 * run as plain app children (APP), kept alive by the foreground service +
 * wakelock while the app is running.
 *
 * Entries written by an older build carry a legacy source tag; see LEGACY_ROOT_SOURCE.
 * It is read as a root-spawned process (the pids were still uid 0) and
 * rewritten to ROOT on the next persist, so old records keep working.
 */
class BgProcessStore(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val root: RootManager,
    /** App workspace root, so detached logs live under it so file tools can read them. */
    workspaceRoot: File,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val storeFile = File(context.filesDir, "bg-processes.json")

    private val entries = ConcurrentHashMap<Int, BgProcessEntry>()
    private val appProcesses = ConcurrentHashMap<Int, Process>()
    private val nextId = AtomicInteger(1)
    private val ioLock = Mutex()

    private companion object {
        /** [BgProcessEntry.source] for a uid-0 background process. */
        const val SOURCE_ROOT = "ROOT"
        /** Tag written by older builds; those pids were uid 0 too. */
        const val LEGACY_ROOT_SOURCE = "SHIZUKU"
    }

    /**
     * Bug 2 fix: unique-per-app-run token inside every log filename. Process
     * ids restart at 1 when the registry state is gone (fresh install, >24h
     * prune), which used to APPEND new output to an old session's log. The
     * token makes log names collision-free across app restarts and lets
     * bg_list flag entries that started in a previous app session.
     */
    private val bootToken: String = java.lang.Long.toString(System.currentTimeMillis(), 36)
    /** When THIS app process started; older entries began in a previous session. */
    val bootAt: Long = System.currentTimeMillis()

    /** Logs for background processes: inside the workspace, readable by both uids and all tools. */
    private val detachedLogDir: File = File(workspaceRoot, ".harness/bg").apply { mkdirs() }

    init {
        runCatching {
            if (storeFile.exists()) {
                val loaded = json.decodeFromString<List<BgProcessEntry>>(storeFile.readText())
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                // prune stale entries from runs older than a day
                loaded.filter { it.startedAt >= cutoff }.forEach { loadedEntry ->
                    // An older build wrote the legacy tag for what are now
                    // root-spawned processes; normalize on load so the
                    // persisted file stops carrying it.
                    entries[loadedEntry.id] = if (loadedEntry.source == LEGACY_ROOT_SOURCE) {
                        loadedEntry.copy(source = SOURCE_ROOT)
                    } else {
                        loadedEntry
                    }
                }
                nextId.set((entries.keys.maxOrNull() ?: 0) + 1)
            }
        }
    }

    private suspend fun persist() {
        ioLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
                    tmp.writeText(
                        json.encodeToString(
                            entries.values
                                .map {
                                    if (it.source == LEGACY_ROOT_SOURCE) it.copy(source = SOURCE_ROOT) else it
                                }
                                .sortedBy { it.id },
                        ),
                    )
                    tmp.renameTo(storeFile)
                }
            }
        }
    }

    data class Started(val entry: BgProcessEntry, val viaRoot: Boolean)

    /**
     * Starts [command] in [cwd]. Prefers the root-detached tier (survives
     * app death) when root is granted; otherwise an app-child process.
     * May throw when the app tier can't reach [cwd]; callers report that.
     */
    suspend fun start(command: String, cwd: File): Started {
        val id = nextId.getAndIncrement()
        val bgDir = File(cwd, ".harness/bg").apply { mkdirs() }
        val logName = "$id-$bootToken.log"
        val logFile = File(bgDir, logName).apply {
            runCatching { createNewFile() }
        }.let { if (it.exists() || it.canWrite()) it else File(detachedLogDir, logName).apply { createNewFile() } }

        if (root.isGranted()) {
            // Make sure the shell-user toolchain copy matches the package set
            // tmpProcessEnv() is built for: a copy from an older set is missing
            // the libraries that env names.
            val expected = linuxEnv.deployedTag()
            if (linuxEnv.isReady && !root.isTmpPrefixDeployed(expected)) {
                linuxEnv.ensureShellDeploy(root)
            }
            val toolchainDeployed = root.isTmpPrefixDeployed(expected)
            val cmd = if (toolchainDeployed) {
                arrayOf("${linuxEnv.tmpPrefix}/bin/bash", "-c", command)
            } else {
                arrayOf("/system/bin/sh", "-c", command)
            }
            val env = if (toolchainDeployed) {
                linuxEnv.tmpProcessEnv().map { "${it.key}=${it.value}" }.toTypedArray()
            } else null
            val pid = root.spawnDetached(cmd, env, cwd.absolutePath, logFile.absolutePath)
            if (pid != null && pid > 0) {
                val entry = BgProcessEntry(
                    id = id, command = command, cwd = cwd.absolutePath,
                    logPath = ".harness/bg/$logName", pid = pid, source = "ROOT",
                    startedAt = System.currentTimeMillis(),
                )
                entries[id] = entry
                persist()
                return Started(entry, viaRoot = true)
            }
            // service dropped mid-flight, so fall back to the app tier below
        }

        val process = linuxEnv.shellProcessBuilder(command)
            .directory(cwd)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectErrorStream(true)
            .start()
        appProcesses[id] = process
        val entry = BgProcessEntry(
            id = id, command = command, cwd = cwd.absolutePath,
            logPath = ".harness/bg/$logName", pid = -1, source = "APP",
            startedAt = System.currentTimeMillis(),
        )
        entries[id] = entry
        persist()
        return Started(entry, viaRoot = false)
    }

    /** All tracked processes with a live liveness check, pruning exited ones. */
    suspend fun list(): List<Pair<BgProcessEntry, Boolean>> {
        val out = mutableListOf<Pair<BgProcessEntry, Boolean>>()
        val deadIds = mutableListOf<Int>()
        entries.values.sortedBy { it.id }.forEach { e ->
            val alive = when (e.source) {
                SOURCE_ROOT, LEGACY_ROOT_SOURCE -> root.isProcessAlive(e.pid)
                else -> appProcesses[e.id]?.isAlive == true
            }
            if (alive) {
                out += e to true
            } else {
                deadIds += e.id
            }
        }
        if (deadIds.isNotEmpty()) {
            deadIds.forEach { id ->
                entries.remove(id)
                appProcesses.remove(id)
            }
            persist()
        }
        return out
    }

    fun get(id: Int): BgProcessEntry? = entries[id]

    /** Stops every background process launched through Harness. */
    suspend fun killAll(): Int {
        val ids = entries.keys.toList()
        var killed = 0
        for (id in ids) {
            if (runCatching { kill(id) }.getOrDefault(false)) killed++
        }
        return killed
    }

    suspend fun kill(id: Int): Boolean {
        val e = entries[id] ?: return false
        val logFile = File(e.cwd, e.logPath).takeIf { it.exists() }
            ?: File(e.logPath).let { if (it.isAbsolute) it else File(detachedLogDir.parentFile, e.logPath) }
        val ok = when (e.source) {
            SOURCE_ROOT, LEGACY_ROOT_SOURCE -> {
                root.killProcess(e.pid)
                killPidsWithFd(logFile)
                true
            }
            else -> {
                val p = appProcesses[id]
                if (p != null) {
                    val pid = runCatching {
                        val field = p.javaClass.getDeclaredField("pid")
                        field.isAccessible = true
                        field.getInt(p)
                    }.getOrNull()
                    if (pid != null && pid > 0) {
                        runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
                        runCatching {
                            val pkill = Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-9", "-P", pid.toString()))
                            pkill.waitFor(1, TimeUnit.SECONDS)
                        }
                        runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                    }
                    killPidsWithFd(logFile)
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                    true
                } else {
                    killPidsWithFd(logFile)
                    true
                }
            }
        }
        appProcesses.remove(id)
        entries.remove(id)
        persist()
        return ok
    }

    fun tail(entry: BgProcessEntry, chars: Int = 2_000): String {
        val f = File(entry.cwd, entry.logPath).takeIf { it.exists() }
            ?: File(entry.logPath).let { if (it.isAbsolute) it else File(detachedLogDir.parentFile, entry.logPath) }
        return if (f.exists()) {
            val text = f.readText()
            val filtered = text.lines()
                .filterNot { isHeartbeatLine(it) }
                .joinToString("\n")
            if (filtered.length <= chars) filtered else "…\n" + filtered.takeLast(chars)
        } else ""
    }

    private fun isHeartbeatLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.contains("heartbeat", ignoreCase = true)
    }

    private fun killPidsWithFd(logFile: File) {
        if (!logFile.exists()) return
        val canonTarget = runCatching { logFile.canonicalPath }.getOrDefault(logFile.absolutePath)
        val proc = File("/proc")
        val pidDirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return
        for (dir in pidDirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val fdDir = File(dir, "fd")
            val fds = runCatching { fdDir.listFiles() }.getOrNull() ?: continue
            for (fd in fds) {
                val link = runCatching { android.system.Os.readlink(fd.absolutePath) }.getOrNull()
                if (link == canonTarget || link == logFile.absolutePath) {
                    runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
                    runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                    break
                }
            }
        }
    }
}
