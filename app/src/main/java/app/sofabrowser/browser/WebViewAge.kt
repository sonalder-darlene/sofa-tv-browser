package app.sofabrowser.browser

/** Whether the TV's WebView is old enough to warn about. Plain Kotlin, so the rules are unit-tested. */
object WebViewAge {

    /**
     * Versions below this get the warning: about a year of Chrome releases behind the stable one (155 on
     * 2026-10-20). Raise it at each release.
     */
    const val MINIMUM = 142

    /** How long a dismissed warning stays quiet when the version doesn't change. */
    private const val REMIND_AFTER_MS = 90L * 24 * 3600 * 1000

    /**
     * The Chromium major version, from the WebView package's version name, or else from the user agent's Chrome/NN
     * (TV makers' WebView packages don't always use Google's version format). Null when neither has one.
     */
    fun major(versionName: String?, userAgent: String?): Int? =
        versionName?.trim()?.let { LEADING.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: userAgent?.let { CHROME.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * Warn when [major] is below [MINIMUM], once per WebView [version], and again after about three months.
     * [warnedVersion] and [warnedAt] describe the last warning shown.
     */
    fun shouldWarn(major: Int?, version: String, warnedVersion: String, warnedAt: Long, now: Long): Boolean =
        major != null && major < MINIMUM && (version != warnedVersion || now - warnedAt > REMIND_AFTER_MS)

    private val LEADING = Regex("""^(\d+)\.""")
    private val CHROME = Regex("""Chrome/(\d+)""")
}
