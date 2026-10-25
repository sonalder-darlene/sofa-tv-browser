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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: DataInputStream with readFully (partial reads used to cut lists short), checks on every size, no modify filters.

package jp.hazuki.yuzubrowser.adblock.filter.unified.io

import jp.hazuki.yuzubrowser.adblock.filter.unified.*
import java.io.DataInputStream
import java.io.IOException

class FilterReader(private val input: DataInputStream) {

    fun checkHeader(): Boolean {
        val header = FILTER_CACHE_HEADER.toByteArray()
        val data = ByteArray(header.size)
        input.readFully(data)
        return header contentEquals data
    }

    /** Reads one list written by [FilterWriter.writeWithTag], trailing header included; throws on damage. */
    fun readAll(): List<Pair<String, UnifiedFilter>> {
        if (!checkHeader()) throw IOException("not a filter list")
        val size = input.readInt()
        if (size < 0) throw IOException("bad size")
        val list = ArrayList<Pair<String, UnifiedFilter>>(minOf(size, 1 shl 16))
        repeat(size) { list += readFilter() }
        if (!checkHeader()) throw IOException("truncated filter list")
        return list
    }

    private fun readString(): String {
        val size = input.readInt()
        if (size < 0 || size > MAX_STRING) throw IOException("bad string size")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes)
    }

    private fun readFilter(): Pair<String, UnifiedFilter> {
        val type = input.readUnsignedByte()
        val contentType = input.readUnsignedShort()
        val thirdParty = when (val b = input.readUnsignedByte()) {
            0, 1, 2, 3 -> b
            0xff -> -1
            else -> throw IOException("bad party")
        }
        val pattern = readString()

        // startEndFilter with domain as pattern, gets special treatment for accelerated read/write
        if (type == FILTER_TYPE_START_END_DOMAIN)
            return Pair(pattern, StartEndFilter(pattern, contentType, true, null, thirdParty))

        val ignoreCase = input.readBoolean()
        val tag = readString()

        val domainsSize = input.readInt()
        val domains = when {
            domainsSize < 0 -> throw IOException("bad domain count")
            domainsSize == 0 -> null
            domainsSize == 1 -> {
                val containerInclude = input.readBoolean()
                val domain = readString()
                val include = input.readBoolean()
                if (containerInclude != include) throw IOException("bad domain")
                SingleDomainMap(include, domain)
            }
            else -> {
                val map = ArrayDomainMap(domainsSize, false)
                map.include = input.readBoolean()
                repeat(domainsSize) {
                    val domain = readString()
                    if (domain.endsWith(".*"))
                        map.wildcard = true
                    map[domain] = input.readBoolean()
                }
                map
            }
        }

        val filter = when (type) {
            FILTER_TYPE_CONTAINS -> ContainsFilter(pattern, contentType, ignoreCase, domains, thirdParty)
            FILTER_TYPE_START -> StartsWithFilter(pattern, contentType, ignoreCase, domains, thirdParty)
            FILTER_TYPE_END -> EndWithFilter(pattern, contentType, ignoreCase, domains, thirdParty)
            FILTER_TYPE_START_END -> StartEndFilter(pattern, contentType, ignoreCase, domains, thirdParty)
            FILTER_TYPE_JVM_REGEX -> RegexFilter(pattern, contentType, ignoreCase, domains, thirdParty)
            FILTER_TYPE_PATTERN -> PatternMatchFilter(pattern, contentType, ignoreCase, domains, thirdParty)
            else -> throw IOException("bad filter type $type")
        }
        return Pair(tag, filter)
    }

    private companion object {
        const val MAX_STRING = 1 shl 20
    }
}
