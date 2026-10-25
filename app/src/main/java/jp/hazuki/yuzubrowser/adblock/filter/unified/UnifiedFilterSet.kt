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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: Sofa's own list names; element filters are kept apart by Sofa's cosmetic parser.

package jp.hazuki.yuzubrowser.adblock.filter.unified

import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_BADFILTER
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_LISTS
import java.security.InvalidParameterException

class UnifiedFilterSet(
    val filterInfo: UnifiedFilterInfo,
    val filters: FilterMap
)

// this is simply a Map<String, List<UnifiedFilter>> where i don't need to care about null
// does NOT contains element hide filters
class FilterMap {
    private val map = (ABP_LISTS + ABP_LISTS.map { ABP_PREFIX_BADFILTER + it }).associateWith { mutableListOf<UnifiedFilter>() }
    operator fun get(list: String) = map[list] ?: throw(InvalidParameterException("list $list does not exist"))

    val size: Int get() = map.values.sumOf { it.size }
}
