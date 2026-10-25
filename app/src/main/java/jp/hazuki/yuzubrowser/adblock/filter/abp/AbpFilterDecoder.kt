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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: network rules only, one line at a time (Sofa reads files, hosts lines and element hiding itself); rules that would need header, parameter or redirect changes are skipped instead of decoded (they used to block or allow too much without them); $sitekey rules are skipped (one of them allowed every page); page-wide $document, $elemhide and $generichide exceptions get their own lists; $from is $domain; $popup and $document rules go to lists of their own, for windows and page navigations; no android dependencies.

package jp.hazuki.yuzubrowser.adblock.filter.abp

import jp.hazuki.yuzubrowser.adblock.core.ContentRequest
import jp.hazuki.yuzubrowser.adblock.filter.unified.*

class AbpFilterDecoder {

    /** Decodes one network rule (not a comment, not element hiding) into [filterLists]; returns false when it was skipped. */
    fun decodeFilter(line: String, filterLists: FilterMap): Boolean {
        val before = filterLists.size
        line.decodeFilter(filterLists)
        return filterLists.size > before
    }

    private fun String.decodeFilter(
        filterLists: FilterMap
    ) {
        var contentType = 0
        var ignoreCase = true
        var domain: String? = null
        var thirdParty = NO_PARTY_PREFERENCE
        var filter = this
        var elementFilter = 0
        var explicitDocument = false
        var explicitPopup = false
        var badfilter = ""
        var important = false
        val blocking = if (filter.startsWith("@@")) {
            filter = substring(2)
            false
        } else {
            true
        }

        val optionsIndex = filter.lastIndexOf('$')
        // a "$" inside a regular expression ("/ad$/") is not an option separator
        if (optionsIndex >= 0 && !(filter.length > 1 && filter.startsWith('/') && filter.endsWith('/'))) {
        val options = filter.substring(optionsIndex + 1).split(',').toMutableList()
        // move denyallow to last position, domains need to be before denyallow
        for (i in 0 until options.size) {
            if (options[i].startsWith("denyallow")) {
                options.add(options[i])
                options.removeAt(i)
                break
            }
        }
            options.forEach {
                var option = it
                var value: String? = null
                val separatorIndex = option.indexOf('=')
                if (separatorIndex >= 0) {
                    value = option.substring(separatorIndex + 1)
                    option = option.substring(0, separatorIndex)
                }
                if (option.isEmpty() || (option.startsWith("_") && option.matches("^_+$".toRegex()))) return@forEach

                val inverse = option[0] == '~'
                if (inverse) {
                    option = option.substring(1)
                }

                option = option.lowercase()
                val type = option.getOptionBit()
                if (type == -1) return

                when {
                    type > 0x00ff_ffff -> {
                        if (inverse) return
                        elementFilter = elementFilter or type
                    }
                    type > 0 -> {
                        if (type == ContentRequest.TYPE_DOCUMENT && !inverse) explicitDocument = true
                        if (type == ContentRequest.TYPE_POPUP && !inverse) explicitPopup = true
                        contentType = if (inverse) {
                            if (contentType == 0) contentType = ContentRequest.TYPE_ALL
                            contentType and (type.inv())
                        } else {
                            contentType or type
                        }
                    }
                    type == 0 -> {
                        when (option) {
                            "match-case" -> ignoreCase = inverse
                            "domain", "from" -> {
                                if (value.isNullOrEmpty()) return
                                // uBlock's regular-expression domains are not supported
                                if ('/' in value) return
                                domain = value.lowercase()
                            }
                            "third-party", "3p" -> thirdParty = if (inverse) FIRST_PARTY else THIRD_PARTY
                            "first-party", "1p" -> thirdParty = if (inverse) THIRD_PARTY else FIRST_PARTY
                            "strict3p" -> thirdParty = if (inverse) STRICT_FIRST_PARTY else STRICT_THIRD_PARTY
                            "strict1p" -> thirdParty = if (inverse) STRICT_THIRD_PARTY else STRICT_FIRST_PARTY
                            // a blocking rule that would serve a stand-in still blocks; an exception to one changes nothing here
                            "redirect", "empty", "mp4" -> {
                                if (!blocking) return
                                this.removeOption(it).decodeFilter(filterLists)
                                return
                            }
                            "important" -> important = true
                            // TODO: see above, all is not handled 100% correctly (but might still be fine)
                            "all" -> contentType = contentType or ContentRequest.TYPE_DOCUMENT or ContentRequest.TYPE_STYLE_SHEET or ContentRequest.TYPE_IMAGE or ContentRequest.TYPE_OTHER or ContentRequest.TYPE_SCRIPT or ContentRequest.TYPE_XHR or ContentRequest.TYPE_FONT or ContentRequest.TYPE_MEDIA or ContentRequest.TYPE_WEB_SOCKET
                            "badfilter" -> badfilter = ABP_PREFIX_BADFILTER
                            "denyallow" -> {
                                // https://kb.adguard.com/en/general/how-to-create-your-own-ad-filters#denyallow-modifier
                                //  create filter + exception filters -> a lot of filters, but no regex
                                if (domain == null || value.isNullOrEmpty()) return
                                if (value.any { it == '*' || it == '~' }) return
                                val withoutDenyallow = this.removeOption("denyallow=$value")
                                withoutDenyallow.decodeFilter(filterLists)
                                val optionsString = withoutDenyallow.substringAfterLast('$')
                                value.split('|').forEach { denyAllowDomain ->
                                    "@@||$denyAllowDomain^\$$optionsString".decodeFilter(filterLists)
                                }
                                return
                            }
                            // sitekey, csp, removeparam, removeheader, redirect-rule, header, method, to, … are
                            // not supported: the rule is skipped rather than applied without them
                            else -> return
                        }
                    }
                }
            }
            if (contentType and ContentRequest.TYPE_UNSUPPORTED != 0 && contentType and ContentRequest.INVERSE == 0) {
                // remove filter if type is exclusively TYPE_UNSUPPORTED
                if (contentType == ContentRequest.TYPE_UNSUPPORTED) return
                // remove TYPE_UNSUPPORTED if it's not the only type
                else contentType = contentType xor ContentRequest.TYPE_UNSUPPORTED
            }

            filter = filter.substring(0, optionsIndex)

            // some lists use * to match all, some use empty
            //  convert * to empty since it will result in a simple contains filter
            if (filter == "*") filter = ""
        }

        // element hiding options only mean something on exceptions
        if (elementFilter != 0 && blocking) return
        // a rule that matches everything everywhere is a mistake or a trap
        if (filter.isEmpty() && domain == null && (blocking || explicitDocument || elementFilter != 0)) return

        val domains = domain?.domainsToDomainMap('|')
        if (contentType == 0) contentType = ContentRequest.TYPE_ALL

        fun make(contentType: Int): UnifiedFilter? =
            if (filter.length >= 2 && filter[0] == '/' && filter[filter.lastIndex] == '/' && filter.mayContainRegexChars()) {
                try { RegexFilter(filter.substring(1, filter.lastIndex), contentType, ignoreCase, domains, thirdParty) }
                catch (e: Exception) { null }
            } else {
                val isStartsWith = filter.startsWith("||")
                val isEndWith = filter.endsWith('^')
                var content = filter.substring(
                    if (isStartsWith) 2 else 0,
                    if (isEndWith) filter.length - 1 else filter.length
                )
                if (ignoreCase) content = content.lowercase()
                val isLiteral = content.isLiteralFilter()
                if (isLiteral) {
                    when {
                        isStartsWith && isEndWith -> StartEndFilter(content, contentType, ignoreCase, domains, thirdParty)
                        isStartsWith -> StartsWithFilter(content, contentType, ignoreCase, domains, thirdParty)
                        isEndWith -> EndWithFilter(content, contentType, ignoreCase, domains, thirdParty)
                        else -> {
                            // mimic uBlock behavior: https://github.com/gorhill/uBlock/wiki/Static-filter-syntax#hosts-files
                            if (this == content && content.isHostname())
                                StartEndFilter(content, contentType, ignoreCase, domains, thirdParty)
                            else
                                ContainsFilter(content, contentType, ignoreCase, domains, thirdParty)
                        }
                    }
                } else {
                    PatternMatchFilter(if (ignoreCase) filter.lowercase() else filter, contentType, ignoreCase, domains, thirdParty)
                }
            }

        fun add(list: String, contentType: Int) {
            filterLists[badfilter + list] += make(contentType) ?: return
        }

        if (elementFilter != 0) {
            add(if (elementFilter and ContentRequest.TYPE_ELEMENT_HIDE != 0) ABP_PREFIX_DISABLE_ELEMENT_PAGE
                else ABP_PREFIX_DISABLE_GENERIC_ELEMENT_PAGE, contentType)
            return
        }
        // Pop-ups and whole pages are only matched by rules that name them, as in uBlock Origin; such rules get lists
        // of their own, and whatever other types they name go on to the usual lists.
        if (explicitPopup) add(if (blocking) ABP_PREFIX_POPUP_DENY else ABP_PREFIX_POPUP_ALLOW, ContentRequest.TYPE_POPUP)
        if (explicitDocument) add(if (blocking) ABP_PREFIX_DOCUMENT_DENY else ABP_PREFIX_DOCUMENT_ALLOW, ContentRequest.TYPE_DOCUMENT)
        val named = (if (explicitPopup) ContentRequest.TYPE_POPUP else 0) or (if (explicitDocument) ContentRequest.TYPE_DOCUMENT else 0)
        val rest = contentType and named.inv()
        if (named != 0 && (rest and ContentRequest.INVERSE.inv()) == 0) return
        when {
            important && blocking -> add(ABP_PREFIX_IMPORTANT, rest)
            blocking -> add(ABP_PREFIX_DENY, rest)
            important -> add(ABP_PREFIX_IMPORTANT_ALLOW, rest)
            else -> add(ABP_PREFIX_ALLOW, rest)
        }
    }

    private fun String.mayContainRegexChars(): Boolean {
        forEach {
            when (it.lowercaseChar()) {
                in 'a'..'z', in '0'..'9', '%', '/', '_', '-' -> Unit
                else -> return true
            }
        }
        return false
    }

    // removes the option (must be full match) and any useless $ or ,
    private fun String.removeOption(toRemove: String): String {
        val options = this.substringAfterLast('$').split(',').filter { it != toRemove }
        return if (options.isEmpty())
            this.substringBeforeLast('$') // no further filter options, remove $
        else
            this.substringBeforeLast('$') + "$" + options.joinToString(",")
    }

    private fun String.domainsToDomainMap(delimiter: Char): DomainMap? {
        val items = split(delimiter).filter { it.isNotEmpty() && it != "~" }
        if (items.isEmpty()) return null
        return if (items.size == 1) {
            if (items[0][0] == '~') {
                SingleDomainMap(false, items[0].substring(1))
            } else {
                SingleDomainMap(true, items[0])
            }
        } else {
            val domains = ArrayDomainMap(items.size, items.any { it.endsWith(".*") })
            items.forEach { domain ->
                if (domain[0] == '~') {
                    domains[domain.substring(1)] = false
                } else {
                    domains[domain] = true
                    domains.include = true
                }
            }
            domains
        }
    }

    private fun String.isHostname() = contains('.') && first().isLetterOrDigit() && last().isLetterOrDigit() &&
        all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }

    private fun String.isLiteralFilter(): Boolean {
        forEach {
            when (it) {
                '*', '^', '|' -> return false
            }
        }
        return true
    }

    private fun String.getOptionBit(): Int {
        return when (this) {
            "other", "xbl", "dtd" -> ContentRequest.TYPE_OTHER
            "script" -> ContentRequest.TYPE_SCRIPT
            "image", "background" -> ContentRequest.TYPE_IMAGE
            "stylesheet", "css" -> ContentRequest.TYPE_STYLE_SHEET
            "subdocument", "frame" -> ContentRequest.TYPE_SUB_DOCUMENT
            "document", "doc" -> ContentRequest.TYPE_DOCUMENT
            "websocket" -> ContentRequest.TYPE_WEB_SOCKET
            "media" -> ContentRequest.TYPE_MEDIA
            "font" -> ContentRequest.TYPE_FONT
            "popup", "popunder" -> ContentRequest.TYPE_POPUP
            "object", "webrtc", "ping", "object-subrequest" -> ContentRequest.TYPE_UNSUPPORTED
            "xmlhttprequest", "xhr" -> ContentRequest.TYPE_XHR
            "genericblock" -> -1
            "elemhide", "ehide" -> ContentRequest.TYPE_ELEMENT_HIDE
            "generichide", "ghide" -> ContentRequest.TYPE_ELEMENT_GENERIC_HIDE
            else -> 0
        }
    }

    /** Reads a "! Title: …" or "! Expires: …" comment into [info]. */
    fun decodeComment(line: String, info: UnifiedFilterInfo) = line.decodeCommentInto(info)

    private fun String.decodeCommentInto(info: UnifiedFilterInfo) {
        // comment format:
        // ! <title>: <content>
        if (!contains(':')) return
        val title = substringBefore(':').drop(1).trim().lowercase()
        val content = substringAfter(':').trim()

        when (title) {
            "title" -> info.title = content
            "homepage" -> info.homePage = content
            "last updated", "last modified" -> info.lastUpdate = content
            "expires" -> info.expires = content.decodeExpires()
            "version" -> info.version = content
        }
    }

    private fun String.decodeExpires(): Int {
        val hours = indexOf("hour")
        if (hours > 0) {
            return try {
                substring(0, hours).trim().toInt()
            } catch (e: NumberFormatException) {
                -1
            }
        }
        val days = indexOf("day")
        if (days > 0) {
            return try {
                substring(0, days).trim().toInt() * 24
            } catch (e: NumberFormatException) {
                -1
            }
        }
        return -1
    }
}
