package app.sofabrowser.browser

/** How a site's request for protected (DRM) playback is answered. Plain Kotlin, so the rules are unit-tested. */
object ProtectedMedia {

    /** The "Protected content (DRM)" setting's values. */
    const val ASK = "ask"
    const val ALLOW = "allow"
    const val BLOCK = "block"

    enum class Decision { GRANT, DENY, ASK }

    /**
     * [remembered] is the site's stored answer from an earlier prompt, or null when it has none. Allow and Block
     * ignore it but keep it, so the answers apply again when the user goes back to Ask.
     */
    fun decide(mode: String, remembered: Boolean?): Decision = when {
        mode == ALLOW -> Decision.GRANT
        mode == BLOCK -> Decision.DENY
        remembered == true -> Decision.GRANT
        remembered == false -> Decision.DENY
        else -> Decision.ASK
    }
}
