package app.sofabrowser.browser

/** The lower-case extension of [url]'s last path segment, ignoring query and fragment ("" without one). */
fun extensionOf(url: String): String =
    url.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
