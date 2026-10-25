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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: test-only helpers removed; a filter's tag is passed in.

package jp.hazuki.yuzubrowser.adblock.core

import jp.hazuki.yuzubrowser.adblock.filter.ContentFilter

class FilterContainer {
    private val filters = hashMapOf<String, ContentFilter>()
    // set to false if there is at least one pattern that is not a domain
    //  of true, checking is much faster
    private var domainOnly = true

    /** Number of filters added. */
    var size = 0
        private set

    // not having to create best tag accelerates loading lists and filling FilterContainer by 20-50%
    fun addWithTag(tag: String, filter: ContentFilter) {
        // normally, created tags will only consist of %, numbers and letters
        //  only tag specifically created for patterns that are a domain will contain '.'
        if (domainOnly && !tag.contains('.'))
            domainOnly = false
        filter.next = filters[tag]
        filters[tag] = filter
        size++
    }

    // check whether any filters with the domain as pattern match
    //  having this separate gives no clear advantage or disadvantage when using easylist
    //  but for domain-only lists it helps a lot
    //  plus, it allows for faster loading from files
    private fun getDomain(request: ContentRequest): ContentFilter? {
        var domain = request.url.host ?: return null
        var filter: ContentFilter?
        while (domain.contains('.')) {
            filter = filters[domain]
            while (filter != null) {
                if (filter.isMatch(request))
                    return filter
                filter = filter.next
            }
            domain = domain.substringAfter('.')
        }
        return null
    }

    operator fun get(request: ContentRequest): ContentFilter? {
        if (size == 0) return null
        getDomain(request)?.let { return it }
        // no need for further checks if list only contains domain filters
        if (domainOnly) return null

        request.tags.forEach {
            var filter = filters[it]
            while (filter != null) {
                if (filter.isMatch(request)) {
                    return filter
                }
                filter = filter.next
            }
        }
        return null
    }
}
