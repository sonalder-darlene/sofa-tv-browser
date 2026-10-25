/*
 * Copyright (C) 2017-2019 Hazuki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: plain inputs instead of WebResourceRequest; file extensions mapped here instead of through Fulguris's MIME table; header names matched in any case.

package jp.hazuki.yuzubrowser.adblock

import app.sofabrowser.adblock.RequestUrl
import jp.hazuki.yuzubrowser.adblock.core.ContentRequest

/** The content types a request may be, from its address, frame and headers (WebView doesn't say). */
fun getContentType(url: RequestUrl, isForMainFrame: Boolean, headers: Map<String, String>): Int {
    var type = 0
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    val accept = header("Accept")

    if (isForMainFrame) {
        type = ContentRequest.TYPE_DOCUMENT
    } else if (accept != null && accept.contains("text/html"))
        type = ContentRequest.TYPE_SUB_DOCUMENT

    val scheme = url.full.substringBefore(':').lowercase()
    if (scheme == "ws" || scheme == "wss") {
        type = type or ContentRequest.TYPE_WEB_SOCKET
    }

    if (header("X-Requested-With") == "XMLHttpRequest") {
        type = type or ContentRequest.TYPE_XHR
    }

    val path = url.ssp.substringBefore('?').substringBefore('#').let { p ->
        if (p.startsWith("//")) p.substring(2).substringAfter('/', "") else p
    }
    val lastDot = path.lastIndexOf('.')
    if (lastDot >= 0 && lastDot > path.lastIndexOf('/')) {
        when (path.substring(lastDot + 1).lowercase()) {
            "js", "mjs" -> return type or ContentRequest.TYPE_SCRIPT
            "css" -> return type or ContentRequest.TYPE_STYLE_SHEET
            "otf", "ttf", "ttc", "woff", "woff2", "eot" -> return type or ContentRequest.TYPE_FONT
            "png", "gif", "jpg", "jpeg", "webp", "avif", "svg", "ico", "bmp", "apng" -> return type or ContentRequest.TYPE_IMAGE
            "mp4", "webm", "m4v", "m4a", "mp3", "ogg", "oga", "ogv", "wav", "flac", "aac", "m3u8", "mpd", "ts" ->
                return type or ContentRequest.TYPE_MEDIA
            "htm", "html", "php" -> Unit
        }
    }

    // why was this here?
    // breaks many $~document filters, because if there is no file extension (which is very common for main frame urls)
    //  then type_document ALWAYS has type_other and thus is blocked by $~document
    if (isForMainFrame) return type

    return if (accept != null && accept != "*/*") {
        val mimeType = accept.split(',')[0].trim()
        otherIfNone(type or mimeType.getFilterType())
    } else {
        type or ContentRequest.TYPE_OTHER or ContentRequest.TYPE_MEDIA or ContentRequest.TYPE_IMAGE or
            ContentRequest.TYPE_FONT or ContentRequest.TYPE_STYLE_SHEET or ContentRequest.TYPE_SCRIPT or
            ContentRequest.TYPE_XHR
    }
}

fun String.getFilterType(): Int {
    return when (this) {
        "application/javascript",
        "application/x-javascript",
        "text/javascript",
        "application/json" -> ContentRequest.TYPE_SCRIPT
        "text/css" -> ContentRequest.TYPE_STYLE_SHEET
        else -> when {
            startsWith("text/") -> 0 // don't add other for all text documents!
            startsWith("image/") -> ContentRequest.TYPE_IMAGE
            startsWith("video/") || startsWith("audio/") -> ContentRequest.TYPE_MEDIA
            startsWith("font/") -> ContentRequest.TYPE_FONT
            else -> ContentRequest.TYPE_OTHER
        }
    }
}

// text documents might have no type -> at least give them other, or filtering doesn't work
fun otherIfNone(type: Int) =
    if (type == 0) ContentRequest.TYPE_OTHER else type
