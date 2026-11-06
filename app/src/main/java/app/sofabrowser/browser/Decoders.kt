package app.sofabrowser.browser

import android.media.MediaCodecList
import android.os.Build

/** Which video codecs the TV decodes in hardware, for picking what another player is offered ([Playlist.decodableOnly]). */
object Decoders {

    /** Whether a hardware decoder exists for an HLS CODECS entry ("avc1.640028"); true for sound and unknown ones. */
    fun hardware(codec: String): Boolean {
        val mime = videoMime(codec) ?: return true
        return mime in hardwareTypes
    }

    /** Whether a hardware decoder exists for a video MIME type ("video/avc"). */
    fun hardwareFor(mime: String): Boolean = mime.lowercase() in hardwareTypes

    /** Whether the decoder [name] is a hardware one; older Androids don't say, so it goes by Android's own names. */
    fun isHardware(name: String): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == name }?.let { return it.isHardwareAccelerated }
        }
        return softwareByName(name).not()
    }

    private val hardwareTypes: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && (if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated else !softwareByName(it.name)) }
            .flatMapTo(HashSet()) { it.supportedTypes.map(String::lowercase) }
    }

    private fun softwareByName(name: String) = name.startsWith("OMX.google.") || name.startsWith("c2.android.")

    // What the TV says its video decoders take, for Video info: a report from a TV then shows what it can decode
    // (such as no AV1 decoder at all, or H.264 only up to 1080p) without anyone guessing. The TV's word, not a test.

    /** One decoder for [codec] ("AV1"): hardware or not, the largest common picture it takes and its frame rate there. */
    data class Listed(val codec: String, val hardware: Boolean, val width: Int, val height: Int, val fps: Int?)

    /** A codec's largest hardware and software decoders; both null when the TV lists none. */
    data class Summary(val codec: String, val hardware: Listed?, val software: Listed?)

    /** Per codec in [CODECS] order; Dolby Vision only when the TV has a decoder for it. */
    fun summarize(listed: List<Listed>): List<Summary> = CODECS.mapNotNull { (codec, _) ->
        val of = listed.filter { it.codec == codec }
        if (codec == DOLBY_VISION && of.isEmpty()) null
        else Summary(codec, of.filter { it.hardware }.maxWithOrNull(LARGER), of.filter { !it.hardware }.maxWithOrNull(LARGER))
    }

    /** This TV's decoders, read once. */
    val tv: List<Summary> by lazy { summarize(listTv()) }

    private fun listTv(): List<Listed> = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && !(Build.VERSION.SDK_INT >= 29 && it.isAlias) }
            .flatMap { info ->
                CODECS.mapNotNull { (codec, mime) ->
                    val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: return@mapNotNull null
                    val caps = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull() ?: return@mapNotNull null
                    val (w, h) = SIZES.firstOrNull { (w, h) -> caps.isSizeSupported(w, h) } ?: return@mapNotNull null
                    val fps = runCatching { caps.getSupportedFrameRatesFor(w, h).upper.toInt() }.getOrNull()
                    val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !softwareByName(info.name)
                    Listed(codec, hardware, w, h, fps)
                }
            }
    }.getOrDefault(emptyList())

    private const val DOLBY_VISION = "Dolby Vision"
    private val CODECS = listOf("AV1" to "video/av01", "HEVC" to "video/hevc", "VP9" to "video/x-vnd.on2.vp9",
        "H.264" to "video/avc", DOLBY_VISION to "video/dolby-vision")
    /** Common pictures, largest first: a decoder is described by the first it takes. */
    private val SIZES = listOf(7680 to 4320, 3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480)
    private val LARGER = compareBy<Listed>({ it.width.toLong() * it.height }, { it.fps ?: 0 })

    private fun videoMime(codec: String): String? {
        val c = codec.lowercase()
        return when {
            c.startsWith("avc1") || c.startsWith("avc3") -> "video/avc"
            c.startsWith("hvc1") || c.startsWith("hev1") -> "video/hevc"
            c.startsWith("dvh1") || c.startsWith("dvhe") || c.startsWith("dva1") || c.startsWith("dvav") -> "video/dolby-vision"
            c.startsWith("av01") -> "video/av01"
            c.startsWith("vp09") -> "video/x-vnd.on2.vp9"
            c.startsWith("vp8") -> "video/x-vnd.on2.vp8"
            else -> null
        }
    }
}
