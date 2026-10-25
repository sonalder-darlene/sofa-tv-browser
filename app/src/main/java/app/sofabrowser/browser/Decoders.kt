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
