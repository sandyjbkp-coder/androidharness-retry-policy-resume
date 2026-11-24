package com.androidharness.app.data.env

/**
 * Decides, from the command text alone, whether a shell command is an **Android
 * system-binary** command (`pm`, `am`, `cmd`, `dumpsys`, `input`, …).
 *
 * Why this exists: [ShellTierRouter] picks an execution tier from the *working
 * directory* and the available privileges, never from the command text. With
 * root granted and the Linux prefix deployed that means every command in a
 * workspace runs in `LINUX_ROOT`, which exports the prefix
 * `LD_LIBRARY_PATH`. `/system/bin` is still on that PATH
 * ([RootManager.rootLinuxPath]), so a bare `pm`/`cmd`/`dumpsys` resolves to the
 * real Android binary — which then picks up the prefix's glibc/musl libraries
 * and fails to link:
 *
 * ```
 * CANNOT LINK EXECUTABLE "cmd": cannot locate symbol "Xzs_Construct"
 * referenced by "/system/lib64/libunwindstack.so"
 * ```
 *
 * The classification is deliberately **conservative**: anything ambiguous stays
 * in the Linux tier. A false negative is the status quo (the broken linker
 * error); a false positive would break `git`/`python3`/`npm`, which need the
 * prefix. So we only claim a command is a system command when the *first* word
 * of some segment of the command line is a known Android system binary — never
 * when the binary appears in a later position (`echo pm list packages` is an
 * `echo`), and never when an absolute path into the Linux prefix is used.
 */
internal object SystemCommandClassifier {

    /**
     * Android system binary **base names** that must run with stock bionic.
     *
     * Kept in sync with the documented SYSTEM_ROOT set in AGENTS.md
     * (`pm`, `am`, `cmd`, `settings`, `dumpsys`, `logcat`, `input`,
     * `uiautomator`) plus the closely related `getprop`/`setprop`/`wm`/`svc`/
     * `id`/`su` that users reach for in the same breath. This is a *narrow*
     * list on purpose: a name that is also a plausible Linux tool (for example
     * `mount`, `start`, `stop`, `log`, `content`) is deliberately excluded
     * because misrouting those to SYSTEM_ROOT would strip the toolchain env
     * they may legitimately need.
     */
    val SYSTEM_BINARIES: Set<String> = setOf(
        "pm",
        "am",
        "cmd",
        "settings",
        "dumpsys",
        "logcat",
        "input",
        "uiautomator",
        "getprop",
        "setprop",
        "wm",
        "svc",
        "id",
        "su",
        "pidof",
    )

    /**
     * Directories that hold Android's own bionic binaries. An invocation that
     * starts with a path under one of these is always a system command.
     */
    private val SYSTEM_DIRS = listOf("/system/bin/", "/system/xbin/", "/vendor/bin/", "/apex/")

    /**
     * Any path that clearly points into a deployed/provisioned Linux prefix is
     * a Linux command, and outranks [SYSTEM_BINARIES] so an explicit
     * `<prefix>/bin/git` is never captured by the `git`-like name match.
     */
    private val LINUX_PREFIX_MARKERS = listOf(
        "/linux/bin/",
        "/linux/lib/",
        "/files/usr/bin/",
        "/usr/bin/",
    )

    /**
     * Wrapper words that precede the real command. `command pm …` and
     * `env pm …` still invoke `pm`.
     */
    private val COMMAND_WRAPPERS = setOf("command", "env", "exec", "nohup", "time")

    /**
     * True when [command] should run in the SYSTEM_ROOT tier.
     */
    fun isSystemBinaryCommand(command: String): Boolean =
        splitSegments(command).any { isSystemSegment(it) }

    /**
     * A single command segment (no `&&`/`;`/`|`). Tokens are examined left to
     * right: leading [COMMAND_WRAPPERS] are skipped, then the first real token
     * is the binary. Nothing later in the segment can promote it, so
     * `echo pm list packages` stays a Linux command.
     */
    private fun isSystemSegment(segment: String): Boolean {
        for (raw in tokenize(segment)) {
            val token = unquote(raw)
            if (token.isEmpty()) continue
            if (COMMAND_WRAPPERS.contains(token)) continue
            // `env FOO=bar pm …` — assignments belong to `env`, not the command.
            if (token.contains('=') && !token.startsWith("/") && !token.startsWith("-")) continue
            // An explicit Linux-prefix path always wins: the user asked for
            // that specific binary, and it needs the prefix libraries.
            if (LINUX_PREFIX_MARKERS.any { token.contains(it) }) return false
            return SYSTEM_DIRS.any { token.startsWith(it) } || token in SYSTEM_BINARIES
        }
        return false
    }

    /** Splits a segment into whitespace-delimited tokens, keeping quotes. */
    private fun tokenize(segment: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (c in segment) {
            if (quote != null) {
                cur.append(c)
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '\'', '"' -> {
                    quote = c
                    cur.append(c)
                }
                ' ', '\t' -> {
                    if (cur.isNotEmpty()) {
                        out += cur.toString()
                        cur.setLength(0)
                    }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /**
     * Splits a command line on the shell separators that start a new command
     * (`&&`, `||`, `;`, `|`, newline). Operators *inside* quotes are not split
     * on; the separators are always surrounded by spaces in real command lines,
     * and a quoted `;` inside an argument is not a command separator.
     */
    private fun splitSegments(command: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (quote != null) {
                cur.append(c)
                if (c == quote) quote = null
                i++
                continue
            }
            when {
                c == '\'' || c == '"' -> {
                    quote = c
                    cur.append(c)
                    i++
                }
                c == ';' || c == '\n' -> {
                    out += cur.toString()
                    cur.setLength(0)
                    i++
                }
                (c == '&' || c == '|') && i + 1 < command.length && command[i + 1] == c -> {
                    out += cur.toString()
                    cur.setLength(0)
                    i += 2
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Removes one layer of matching surrounding quotes. */
    private fun unquote(s: String): String {
        if (s.length >= 2 && (s.first() == '\'' || s.first() == '"') && s.last() == s.first()) {
            return s.substring(1, s.length - 1)
        }
        return s
    }
}
