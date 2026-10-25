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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: a plain HashMap with insertion order kept, instead of androidx.collection.SimpleArrayMap.

package jp.hazuki.yuzubrowser.adblock.filter.unified

class ArrayDomainMap(size: Int, override var wildcard: Boolean) : DomainMap {
    private val keys = ArrayList<String>(size)
    private val map = HashMap<String, Boolean>(size * 2)

    override val size: Int
        get() = keys.size

    // see https://adblockplus.org/en/filter-cheatsheet: also matches subdomains
    override fun get(domain: String): Boolean? {
        if (wildcard) {
            // check all entries separately, return null if none matches
            //  the most specific entry wins, so "~sub.example.*" can carve out of "example.*"
            var best: Boolean? = null
            var bestLength = -1
            for (key in keys)
                if (key.length > bestLength && matchWildcard(key, domain)) { best = map[key]; bestLength = key.length }
            return best
        }
        var d = domain
        while (true) {
            map[d]?.let { return it }
            val dot = d.indexOf('.')
            if (dot < 0) return null
            d = d.substring(dot + 1)
        }
    }

    operator fun set(domain: String, value: Boolean) {
        if (map.put(domain, value) == null) keys += domain
    }

    override var include: Boolean = false

    override fun getKey(index: Int): String {
        return keys[index]
    }

    override fun getValue(index: Int): Boolean {
        return map.getValue(keys[index])
    }

    override fun hashCode(): Int {
        var result = map.hashCode()
        result = 31 * result + wildcard.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ArrayDomainMap
        if (size != other.size) return false
        if (wildcard != other.wildcard) return false
        return map == other.map
    }
}
