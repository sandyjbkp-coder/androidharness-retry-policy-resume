package com.androidharness.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserConsoleClassifierTest {

    @Test
    fun `qcloud accelerometer warning is non fatal`() {
        val c = BrowserConsoleClassifier.classify(
            "Permissions policy violation: accelerometer is not allowed in this document.",
            "https://gcaptcha.eo.qcloud.com/tdc.js",
        )
        assertEquals(BrowserConsoleClassifier.VERDICT_NON_FATAL_BROWSER_WARNING, c.verdict)
        assertEquals(BrowserConsoleClassifier.CONSOLE_CATEGORY_THIRD_PARTY_PERMISSION_POLICY, c.category)
        assertEquals(BrowserConsoleClassifier.SEVERITY_WARNING, c.severity)
        assertEquals(BrowserConsoleClassifier.TASK_IMPACT_NONE, c.taskImpact)
        assertTrue(c.nonFatal)
    }

    @Test
    fun `gyroscope policy warning is non fatal`() {
        val c = BrowserConsoleClassifier.classify(
            "Permissions policy violation: gyroscope is not allowed in this document.",
            "https://third-party.example/captcha.js",
        )
        assertEquals(BrowserConsoleClassifier.VERDICT_NON_FATAL_BROWSER_WARNING, c.verdict)
        assertTrue(c.nonFatal)
    }

    @Test
    fun `feature policy warning is non fatal`() {
        val c = BrowserConsoleClassifier.classify("Feature-Policy: unrecognized feature: 'battery'.")
        assertTrue(c.nonFatal)
        assertEquals(BrowserConsoleClassifier.TASK_IMPACT_NONE, c.taskImpact)
    }

    @Test
    fun `deviceorientation policy warning is non fatal`() {
        val c = BrowserConsoleClassifier.classify(
            "The deviceorientation events are blocked by permissions policy.",
            "https://gcaptcha.eo.qcloud.com/tdc.js",
        )
        assertTrue(c.nonFatal)
    }

    @Test
    fun `genuine uncaught exception stays fatal candidate`() {
        val c = BrowserConsoleClassifier.classify(
            "Uncaught TypeError: Cannot read properties of null (reading 'init')",
            "https://app.example/main.js",
        )
        assertEquals(BrowserConsoleClassifier.VERDICT_FATAL_CANDIDATE, c.verdict)
        assertEquals(BrowserConsoleClassifier.CONSOLE_CATEGORY_GENERAL, c.category)
        assertFalse(c.nonFatal)
    }

    @Test
    fun `no global suppression of console errors`() {
        val c = BrowserConsoleClassifier.classify("Uncaught ReferenceError: harnessInit is not defined")
        assertFalse(c.nonFatal)
        assertEquals(BrowserConsoleClassifier.TASK_IMPACT_UNKNOWN, c.taskImpact)
    }

    @Test
    fun `third party analytics promise rejection is non fatal`() {
        // A tracking beacon rejecting (adblock/WAF/offline) is not an app failure.
        val c = BrowserConsoleClassifier.classify(
            "Uncaught (in promise) #<Object>",
            "https://www.bybit.com/common-static/infra-static/hecate/hecate.min.js",
        )
        assertEquals(BrowserConsoleClassifier.VERDICT_NON_FATAL_BROWSER_WARNING, c.verdict)
        assertEquals(BrowserConsoleClassifier.CONSOLE_CATEGORY_THIRD_PARTY_ANALYTICS, c.category)
        assertEquals(BrowserConsoleClassifier.TASK_IMPACT_NONE, c.taskImpact)
        assertTrue(c.nonFatal)
    }

    @Test
    fun `same rejection from first party code stays fatal`() {
        // Identical text, different origin: must NOT be downgraded. The allowlist
        // is what separates the two, so this guards against widening it.
        val c = BrowserConsoleClassifier.classify(
            "Uncaught (in promise) #<Object>",
            "https://app.example/main.js",
        )
        assertEquals(BrowserConsoleClassifier.VERDICT_FATAL_CANDIDATE, c.verdict)
        assertFalse(c.nonFatal)
    }

    @Test
    fun `third party real error is not downgraded`() {
        // Only opaque rejections are downgraded; a named error from the same
        // allowlisted host still indicates a real failure.
        val c = BrowserConsoleClassifier.classify(
            "Uncaught (in promise) TypeError: x is not a function",
            "https://www.bybit.com/common-static/infra-static/hecate/hecate.min.js",
        )
        assertFalse(c.nonFatal)
    }

    @Test
    fun `healthy page has no main frame error`() {
        assertTrue(BrowserConsoleClassifier.isPageHealthy(null))
        assertTrue(BrowserConsoleClassifier.isPageHealthy(""))
        assertFalse(BrowserConsoleClassifier.isPageHealthy("net::ERR_CONNECTION_REFUSED"))
    }
}
