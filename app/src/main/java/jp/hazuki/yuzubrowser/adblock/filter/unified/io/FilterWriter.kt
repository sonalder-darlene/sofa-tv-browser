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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: DataOutputStream with length-prefixed UTF-8 strings, no 255-domain cap, no modify filters, filters written with the tags they were indexed under.

package jp.hazuki.yuzubrowser.adblock.filter.unified.io

import jp.hazuki.yuzubrowser.adblock.filter.unified.*
import java.io.DataOutputStream

class FilterWriter(private val os: DataOutputStream) {

    fun write(filters: List<UnifiedFilter>) {
        writeWithTag(filters.map { Pair(Tag.createBest(it), it) })
    }

    fun writeWithTag(filters: List<Pair<String, UnifiedFilter>>) {
        writeHeader()
        os.writeInt(filters.size)
        filters.forEach { writeFilter(it) }
        writeHeader()
    }

    private fun writeHeader() {
        os.write(FILTER_CACHE_HEADER.toByteArray())
    }

    private fun writeString(s: String) {
        val bytes = s.toByteArray()
        os.writeInt(bytes.size)
        os.write(bytes)
    }

    private fun writeFilter(pair: Pair<String, UnifiedFilter>) {
        val filter = pair.second
        // write simplified for simple domain filters
        //  increases write and read speeds
        // tag should be equal to pattern, otherwise it can't contain '.'
        if (filter.filterType == FILTER_TYPE_START_END && pair.first == filter.pattern && pair.first.contains('.')
            && filter.domains == null && filter.ignoreCase) {
            os.writeByte(FILTER_TYPE_START_END_DOMAIN)
            os.writeShort(filter.contentType)
            os.writeByte(filter.thirdParty)
            writeString(pair.first)
            return
        }

        os.writeByte(filter.filterType)
        os.writeShort(filter.contentType)
        os.writeByte(filter.thirdParty)
        writeString(filter.pattern)
        os.writeBoolean(filter.ignoreCase)

        // write tag, so it's not created again when loading -> 20-50% faster loading
        writeString(pair.first)

        val map = filter.domains
        os.writeInt(map?.size ?: 0)
        if (map != null) {
            os.writeBoolean(map.include)
            for (i in 0 until map.size) {
                writeString(map.getKey(i))
                os.writeBoolean(map.getValue(i))
            }
        }
    }
}
