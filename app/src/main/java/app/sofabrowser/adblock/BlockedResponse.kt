package app.sofabrowser.adblock

import app.sofabrowser.browser.extensionOf

/**
 * What a blocked subresource gets back. Bait checks read any failure as "blocker present", so images and
 * stylesheets get a harmless success instead. Everything else keeps the error: a script tag and fetch() look alike
 * to WebView (both accept anything), and an ad SDK that "loads" empty breaks video players that would skip their
 * ads when it fails. Plain Kotlin, so the choice is unit-tested.
 */
object BlockedResponse {

    enum class Kind { ERROR, IMAGE, CSS }

    /** Chooses from the request's Accept header, or from the address's extension when there is none. */
    fun kind(url: String, accept: String?): Kind {
        if (!accept.isNullOrBlank()) return when {
            accept.startsWith("image/") -> Kind.IMAGE
            accept.startsWith("text/css") -> Kind.CSS
            else -> Kind.ERROR
        }
        return when (extensionOf(url)) {
            in IMAGE_EXTENSIONS -> Kind.IMAGE
            "css" -> Kind.CSS
            else -> Kind.ERROR
        }
    }

    private val IMAGE_EXTENSIONS = setOf("png", "gif", "jpg", "jpeg", "webp", "avif", "svg", "ico", "bmp")

    /** A 1×1 transparent GIF. */
    val PIXEL: ByteArray = intArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00,
        0xFF, 0xFF, 0xFF, 0x21, 0xF9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C,
        0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
    ).map { it.toByte() }.toByteArray()
}
