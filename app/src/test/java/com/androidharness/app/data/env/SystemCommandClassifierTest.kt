package com.androidharness.app.data.env

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug: Android system binaries invoked from a workspace run in LINUX_ROOT,
 * which exports the Linux prefix `LD_LIBRARY_PATH`. Bionic binaries then pick
 * up prefix libraries and fail to link:
 *
 *   CANNOT LINK EXECUTABLE "cmd": cannot locate symbol "Xzs_Construct"
 *   referenced by "/system/lib64/libunwindstack.so"
 *
 * [SystemCommandClassifier] must decide, from the command text alone, whether a
 * command is an Android system-binary command. ShellTierRouter uses that to run
 * such commands in SYSTEM_ROOT (sanitized env) instead.
 */
class SystemCommandClassifierTest {

    private fun isSystem(cmd: String) = SystemCommandClassifier.isSystemBinaryCommand(cmd)

    @Test
    fun `bare system binaries route to SYSTEM_ROOT`() {
        for (cmd in listOf(
            "pm list packages",
            "pm install -r /data/app/foo.apk",
            "dumpsys package com.foo.bar",
            "am start -n com.foo/.MainActivity",
            "cmd package list packages",
            "settings put system screen_off_timeout 60000",
            "input tap 100 200",
            "input swipe 1 2 3 4",
            "uiautomator dump",
            "logcat -d",
            "id",
            "getprop ro.build.version.sdk",
            "wm size",
            "svc wifi",
        )) {
            assertTrue("expected system command: $cmd", isSystem(cmd))
        }
    }

    @Test
    fun `absolute system paths are recognized`() {
        for (cmd in listOf(
            "/system/bin/pm list packages",
            "/system/bin/dumpsys meminfo",
            "/system/bin/id",
            "/system/xbin/su -c id",
        )) {
            assertTrue("expected system command: $cmd", isSystem(cmd))
        }
    }

    @Test
    fun `absolute paths win over PATH lookup`() {
        // The prefix's own git must not be dragged into the system tier just
        // because "git" appears later in the line.
        assertFalse(isSystem("/data/local/tmp/androidharness/x/linux/bin/git status"))
        assertFalse(isSystem("echo pm list packages"))
        assertFalse(isSystem("ls -l /system/bin/pm"))
    }

    @Test
    fun `linux toolchain commands stay in LINUX_ROOT`() {
        for (cmd in listOf(
            "git status",
            "git --version",
            "python3 --version",
            "node --version",
            "npm --version",
            "bash -c ls",
            "codegraph --no-color version",
            "ls -la",
            "grep -r foo .",
            "./gradlew :app:assembleDebug",
            "cat package.json | jq .",
        )) {
            assertFalse("must NOT be a system command: $cmd", isSystem(cmd))
        }
    }

    @Test
    fun `command separators do not hide a system binary`() {
        assertTrue(isSystem("cd /sdcard && pm list packages"))
        assertTrue(isSystem("echo hi; dumpsys battery"))
        assertTrue(isSystem("ls && pm install -r x.apk"))
    }

    @Test
    fun `a single pipe is not a command separator`() {
        // `ls | pm …` feeds ls's stdout to pm's stdin; it does not invoke pm
        // as a command, so it stays in the Linux tier. A system binary in the
        // *first* position still counts, whichever side of the pipe it is on.
        assertFalse(isSystem("ls | pm install -r x.apk"))
        assertTrue(isSystem("dumpsys battery | grep x"))
        assertFalse(isSystem("grep x | dumpsys battery"))
    }

    @Test
    fun `compound with a linux toolchain stays linux`() {
        assertFalse(isSystem("git status && python3 build.py"))
        assertFalse(isSystem("ls -la && rm -f x"))
    }

    @Test
    fun `empty and blank commands are not system commands`() {
        assertFalse(isSystem(""))
        assertFalse(isSystem("   "))
    }

    @Test
    fun `prefixed and quoted invocations are recognized`() {
        assertTrue(isSystem("command pm list packages"))
        assertTrue(isSystem("env pm list packages"))
    }
}
