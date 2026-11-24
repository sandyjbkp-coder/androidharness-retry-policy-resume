package com.androidharness.app.browser

/**
 * Console/error classifier for WebView console messages.
 *
 * Browser Permissions-Policy warnings emitted by cross-origin third-party
 * content (for example QCloud captcha `tdc.js` reporting
 * "Permissions policy violation: accelerometer is not allowed in this
 * document") are NOT fatal JavaScript/application errors. They must be logged
 * for diagnostics while the agent keeps working.
 *
 * Page health is determined from actual functionality (page loaded, DOM
 * responsive, navigation works, required UI accessible, no uncaught
 * application exception blocking the requested action), never from the mere
 * presence of one of these policy warnings.
 *
 * Only known browser security/policy warnings are downgraded. All other
 * console errors stay fatal candidates.
 */
object BrowserConsoleClassifier {

    const val VERDICT_NON_FATAL_BROWSER_WARNING = "NON_FATAL_BROWSER_WARNING"
    const val VERDICT_FATAL_CANDIDATE = "FATAL_CANDIDATE"

    const val CONSOLE_CATEGORY_THIRD_PARTY_PERMISSION_POLICY = "THIRD_PARTY_PERMISSION_POLICY"
    const val CONSOLE_CATEGORY_THIRD_PARTY_ANALYTICS = "THIRD_PARTY_ANALYTICS"
    const val CONSOLE_CATEGORY_GENERAL = "GENERAL"

    const val SEVERITY_WARNING = "WARNING"
    const val SEVERITY_ERROR = "ERROR"

    const val TASK_IMPACT_NONE = "NONE"
    const val TASK_IMPACT_UNKNOWN = "UNKNOWN"

    data class ConsoleClassification(
        /** One of [VERDICT_NON_FATAL_BROWSER_WARNING] / [VERDICT_FATAL_CANDIDATE]. */
        val verdict: String,
        /** Diagnostic bucket, e.g. [CONSOLE_CATEGORY_THIRD_PARTY_PERMISSION_POLICY]. */
        val category: String,
        /** Downgraded severity for display: WARNING for policy warnings. */
        val severity: String,
        /** Expected effect on the agent task: NONE for policy warnings. */
        val taskImpact: String,
        /** True when the agent must NOT pause, fail, reload-loop, or patch site JS. */
        val nonFatal: Boolean,
    )

    /**
     * Classifies a console message. Matching is message-driven and
     * source-agnostic so any third-party frame (QCloud, captchas, ads,
     * embeds) emitting a Permissions-Policy / Feature-Policy warning is
     * downgraded, while genuine application failures pass through.
     */
    fun classify(message: String, source: String? = null): ConsoleClassification {
        if (isPermissionPolicyWarning(message, source)) {
            return ConsoleClassification(
                verdict = VERDICT_NON_FATAL_BROWSER_WARNING,
                category = CONSOLE_CATEGORY_THIRD_PARTY_PERMISSION_POLICY,
                severity = SEVERITY_WARNING,
                taskImpact = TASK_IMPACT_NONE,
                nonFatal = true,
            )
        }
        if (isThirdPartyAnalyticsRejection(message, source)) {
            return ConsoleClassification(
                verdict = VERDICT_NON_FATAL_BROWSER_WARNING,
                category = CONSOLE_CATEGORY_THIRD_PARTY_ANALYTICS,
                severity = SEVERITY_WARNING,
                taskImpact = TASK_IMPACT_NONE,
                nonFatal = true,
            )
        }
        val trimmed = message.trim()
        val looksError = trimmed.startsWith("Uncaught ", ignoreCase = true) ||
            trimmed.contains("uncaught exception", ignoreCase = true) ||
            trimmed.contains("failed to load resource", ignoreCase = true) ||
            trimmed.contains("net::ERR_", ignoreCase = true)
        return ConsoleClassification(
            verdict = VERDICT_FATAL_CANDIDATE,
            category = CONSOLE_CATEGORY_GENERAL,
            severity = if (looksError) SEVERITY_ERROR else SEVERITY_WARNING,
            taskImpact = TASK_IMPACT_UNKNOWN,
            nonFatal = false,
        )
    }

    /**
     * True for known browser security/policy warnings that do not affect page
     * functionality on their own: Permissions-Policy / Feature-Policy
     * violations such as "accelerometer is not allowed in this document" or
     * "gyroscope is not allowed in this document".
     */
    fun isPermissionPolicyWarning(message: String, source: String? = null): Boolean {
        val m = message.lowercase()
        if (m.contains("permissions policy violation")) return true
        if (m.contains("permissions-policy")) return true
        if (m.contains("permissions policy")) return true
        if (m.contains("feature policy")) return true
        if (m.contains("feature-policy")) return true
        // Canonical Chromium phrasing for a disallowed feature in a frame.
        if (m.contains("is not allowed in this document")) return true
        if (m.contains("not allowed") && m.contains("this document")) return true
        // Sensor features gated by Permissions-Policy; only when paired with
        // an explicit policy signal so real app errors are never downgraded.
        val sensor = m.contains("accelerometer") ||
            m.contains("gyroscope") ||
            m.contains("magnetometer") ||
            m.contains("ambient-light-sensor") ||
            m.contains("ambient_light_sensor")
        val policySignal = m.contains("policy") ||
            m.contains("permission") ||
            m.contains("feature") ||
            m.contains("allow=")
        if (sensor && policySignal) return true
        if (m.contains("unrecognized feature") && policySignal) return true
        // Source hint alone never classifies; message must already match above.
        return false
    }

    /**
     * True for an opaque unhandled promise rejection raised by a known
     * third-party analytics/beacon script, e.g.
     *   "Uncaught (in promise) #<Object>"  from  hecate.min.js
     *
     * Such a rejection means a tracking call failed (blocked by an ad blocker,
     * a WAF, or offline) — it carries no application failure and does not stop
     * the page from working.
     *
     * This is deliberately narrow. It requires BOTH:
     *  1. an allowlisted third-party script source, and
     *  2. a message that is exactly the opaque shape, naming no real error type.
     *
     * A named error from the same host ("Uncaught (in promise) TypeError: ...")
     * still indicates a genuine failure and stays fatal. Without the allowlist
     * an opaque rejection from first-party code would also be downgraded, so the
     * source check is what keeps real bugs visible.
     */
    fun isThirdPartyAnalyticsRejection(message: String, source: String? = null): Boolean {
        val m = message.trim().lowercase()
        if (!m.startsWith("uncaught (in promise)")) return false
        // Must be the opaque `#<object>` form. If Chromium reports a real
        // error type or message, it is not a silently-swallowed beacon failure.
        if (!m.matches(Regex("^uncaught \\(in promise\\)\\s*#<object>$"))) return false
        val src = source?.lowercase() ?: return false
        return ANALYTICS_HOSTS.any { src.contains(it) }
    }

    /**
     * Hosts whose scripts emit opaque rejections that are not application
     * errors. Keep this an allowlist — never a blocklist.
     */
    private val ANALYTICS_HOSTS = listOf(
        "hecate.min.js",      // Bybit infra/analytics
    )

    /**
     * Page health comes from actual functionality, not from the presence of a
     * third-party policy warning. A page with no main-frame error is treated
     * as loaded; DOM responsiveness / navigation / required UI are checked by
     * the caller operating on the live page.
     */
    fun isPageHealthy(mainFrameError: String?): Boolean = mainFrameError.isNullOrBlank()
}
