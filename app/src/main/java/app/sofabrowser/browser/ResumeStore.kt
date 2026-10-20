package app.sofabrowser.browser

import java.security.MessageDigest

/**
 * Where the user stopped long videos, on this device only, so "Continue from 12:30" can be offered. Pages are
 * keyed by a hash of their address, so the store holds no readable addresses. Rules
 * only: [storage] is any string map (the app keeps it in its own preferences file). Plain Kotlin, so it is
 * unit-tested.
 */
class ResumeStore(private val storage: MutableMap<String, String>) {

    /**
     * Remembers [position] (seconds) on [pageUrl], or forgets the page when there is nothing worth offering: a
     * short video, or the last minute (the film is over). The first minute changes nothing, so starting a film again
     * doesn't wipe the place it can still be continued from. Private browsing writes nothing.
     */
    fun save(pageUrl: String, position: Double, duration: Double, private: Boolean, now: Long) {
        if (private || position < START_SKIP) return
        val key = keyFor(pageUrl)
        if (duration < MIN_DURATION || position > duration - END_SKIP) {
            storage.remove(key)
            return
        }
        storage[key] = "$position,$now"
        if (storage.size > MAX_ENTRIES) {
            storage.entries.sortedBy { savedAt(it.value) }.take(storage.size - MAX_ENTRIES).map { it.key }.forEach(storage::remove)
        }
    }

    /** The position to offer on [pageUrl], or null when there is none or it is too old. */
    fun offer(pageUrl: String, now: Long): Double? {
        val value = storage[keyFor(pageUrl)] ?: return null
        if (now - savedAt(value) > MAX_AGE_MS) return null
        return value.substringBefore(',').toDoubleOrNull()
    }

    fun clear() = storage.clear()

    private fun savedAt(value: String) = value.substringAfter(',').toLongOrNull() ?: 0L

    private fun keyFor(url: String): String {
        // The query often names the video (watch?v=…), so it stays, minus tracking and start-time parameters.
        val noFragment = url.substringBefore('#')
        val params = noFragment.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() && !isIgnored(it.substringBefore('=').lowercase()) }
        val page = noFragment.substringBefore('?') + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return MessageDigest.getInstance("SHA-256").digest(page.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }

    private fun isIgnored(name: String) = name.startsWith("utm_") || name in IGNORED_PARAMS

    private companion object {
        const val MIN_DURATION = 600.0 // ten minutes: clips start over
        const val START_SKIP = 60.0
        const val END_SKIP = 60.0
        const val MAX_ENTRIES = 100
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
        val IGNORED_PARAMS = setOf("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "si", "ref",
            "t", "start", "time_continue")
    }
}
