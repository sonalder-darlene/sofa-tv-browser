/*
 * Copyright 2020 Hazuki
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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: plain RequestUrl instead of android.net.Uri, tags made lazily, no headers or method, a pop-up type.

package jp.hazuki.yuzubrowser.adblock.core

import app.sofabrowser.adblock.RequestUrl
import jp.hazuki.yuzubrowser.adblock.filter.unified.Tag

class ContentRequest(
    val url: RequestUrl,
    val pageHost: String?,
    val type: Int,
    val isThirdParty: Int,
) {
    val urlLowercase: RequestUrl get() = url.lowercase

    val tags: List<String> by lazy(LazyThreadSafetyMode.NONE) { Tag.create(url.full) }

    companion object {
        const val TYPE_OTHER = 0x01
        const val TYPE_SCRIPT = 0x02
        const val TYPE_IMAGE = 0x04
        const val TYPE_STYLE_SHEET = 0x08
        const val TYPE_SUB_DOCUMENT = 0x10
        const val TYPE_DOCUMENT = 0x20
        const val TYPE_MEDIA = 0x40
        const val TYPE_FONT = 0x80
        const val TYPE_UNSUPPORTED = 0x0100 // was TYPE_POPUP, now gathers all types that are not (currently) supported
        const val TYPE_WEB_SOCKET = 0x0200
        const val TYPE_XHR = 0x0400
        // added for Sofa: a window a page opens ($popup), never set on network requests
        const val TYPE_POPUP = 0x0800
        const val TYPE_ALL = 0xffff
        // INVERSE and TYPE_ is 0
        //  except if content type has ~ in filter string (again exception: no type results in TYPE_ALL)
        // use 0x8000 so further types can be added: 0x0800, 0x1000, 0x2000, 0x4000
        const val INVERSE = 0x8000

        const val TYPE_ELEMENT_HIDE = 0x4000_0000
        const val TYPE_ELEMENT_GENERIC_HIDE = 0x2000_0000
    }
}
