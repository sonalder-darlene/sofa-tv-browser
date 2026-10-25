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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: Sofa's Sites instead of OkHttp's PublicSuffix; a domain no longer matches look-alikes ("badexample.com" for "example.com").

package jp.hazuki.yuzubrowser.adblock.filter.unified

import app.sofabrowser.adblock.Sites

interface DomainMap {
    val size: Int

    val include: Boolean

    val wildcard: Boolean

    operator fun get(domain: String): Boolean?

    fun getKey(index: Int): String

    fun getValue(index: Int): Boolean

    fun matchWildcard(filterDomain: String, domain: String): Boolean {
        // for arrayDomainMaps some domains may have wildcard, but then not all need to have
        if (!filterDomain.endsWith(".*"))
            return filterDomain == domain || domain.endsWith(".$filterDomain")

        // "example.*" matches example.com and www.example.co.uk, but not example.evil.com
        val name = filterDomain.dropLast(2)
        val rest = Sites.withoutSuffix(domain) ?: return false
        return rest == name || rest.endsWith(".$name")
    }
}
