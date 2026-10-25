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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: Sofa's list names: page-wide exceptions, pop-up and page lists added, modify and redirect lists removed.

package jp.hazuki.yuzubrowser.adblock.filter.abp

// names of the lists a filter goes to
internal const val ABP_PREFIX_DENY = "b_"
internal const val ABP_PREFIX_ALLOW = "w_"
internal const val ABP_PREFIX_IMPORTANT = "i_"
internal const val ABP_PREFIX_IMPORTANT_ALLOW = "ia_"
// page-wide exceptions, matched against the page's address: @@…$document, @@…$elemhide, @@…$generichide
internal const val ABP_PREFIX_DOCUMENT_ALLOW = "wd_"
internal const val ABP_PREFIX_DISABLE_ELEMENT_PAGE = "wp_"
internal const val ABP_PREFIX_DISABLE_GENERIC_ELEMENT_PAGE = "wg_"
// rules for windows a page opens ($popup) and for whole pages the browser goes to (blocking $document)
internal const val ABP_PREFIX_POPUP_DENY = "pb_"
internal const val ABP_PREFIX_POPUP_ALLOW = "pw_"
internal const val ABP_PREFIX_DOCUMENT_DENY = "bd_"
// badfilter is an additional prefix for lists
internal const val ABP_PREFIX_BADFILTER = "bf_"

internal val ABP_LISTS = listOf(
    ABP_PREFIX_DENY, ABP_PREFIX_ALLOW, ABP_PREFIX_IMPORTANT, ABP_PREFIX_IMPORTANT_ALLOW,
    ABP_PREFIX_DOCUMENT_ALLOW, ABP_PREFIX_DISABLE_ELEMENT_PAGE, ABP_PREFIX_DISABLE_GENERIC_ELEMENT_PAGE,
    ABP_PREFIX_POPUP_DENY, ABP_PREFIX_POPUP_ALLOW, ABP_PREFIX_DOCUMENT_DENY,
)
