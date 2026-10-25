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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: plain JUnit instead of AssertJ.

package jp.hazuki.yuzubrowser.adblock.filter.unified

import jp.hazuki.yuzubrowser.adblock.core.ContentRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class PatternMatchFilterTest {

    @Test
    fun testCheck() {
        /** wildcards test */
        val wildcards = PatternMatchFilter("example.com/ads/banner*.gif", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, wildcards.check("http://example.com/ads/banner123.gif"))

        val lastWildcard = PatternMatchFilter("test.*", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, lastWildcard.check("test.com"))

        val firstWildcard = PatternMatchFilter("*.test.com", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, firstWildcard.check("browser.test.com"))

        val wildcard = PatternMatchFilter("*.test.*", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, wildcard.check("browser.test.com"))
        assertEquals(false, wildcard.check("test.com"))

        /** Matching at beginning test */
        val start = PatternMatchFilter("|http://baddomain.example/", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, start.check("http://baddomain.example/banner.gif"))
        assertEquals(false, start.check("http://gooddomain.example/analyze?http://baddomain.example"))

        /** Matching at end test */
        val endWith = PatternMatchFilter("swf|", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, endWith.check("http://example.com/annoyingflash.swf"))
        assertEquals(false, endWith.check("http://example.com/swf/index.html"))

        /** beginning of the domain name test */
        val domainName = PatternMatchFilter("||example.com/banner.gif", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, domainName.check("http://example.com/banner.gif"))
        assertEquals(true, domainName.check("https://example.com/banner.gif"))
        assertEquals(true, domainName.check("http://www.example.com/banner.gif"))
        assertEquals(false, domainName.check("http://badexample.com/banner.gif"))
        assertEquals(false, domainName.check(" http://gooddomain.example/analyze?http://example.com/banner.gif"))

        val domainWildcard = PatternMatchFilter("||example.com/*/banner.gif", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, domainWildcard.check("http://example.com/test/banner.gif"))
        assertEquals(false, domainWildcard.check("http://example.com/banner.gif"))
        assertEquals(true, domainWildcard.check("http://www.example.com/test/banner.gif"))

        /** Marking separator characters test */
        val separator = PatternMatchFilter("http://example.com^", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, separator.check("http://example.com/"))
        assertEquals(true, separator.check("http://example.com:8000/"))
        assertEquals(false, separator.check("http://example.com.ar/"))

        val firstSeparator = PatternMatchFilter("^adsite", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, firstSeparator.check("browser.test.com/adsite"))
        assertEquals(false, firstSeparator.check("browser.test.com/ads"))

        val secondSeparator = PatternMatchFilter("p^adsite", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(false, secondSeparator.check("browser.test.jp.adsite"))
        assertEquals(true, secondSeparator.check("browser.test.jp/adsite"))

        val endSeparator = PatternMatchFilter("adsite^", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, endSeparator.check("browser.test.com/adsite/ad"))
        assertEquals(false, endSeparator.check("ads.test.com/adsite.ad/"))

        val midSeparator = PatternMatchFilter("adsite^ad", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(true, midSeparator.check("browser.test.com/adsite/ad"))

        val fieldTest1 = PatternMatchFilter("/www/delivery/*", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(false, fieldTest1.check("https://www.amazon.co.jp/"))

        val fT2 = PatternMatchFilter("||ad-stir.com^", 0, false, null, -1)
        assertEquals(true, fT2.check("http://js.ad-stir.com/js/nativeapi.js"))

        /** real pattern test */
        val realJandan = PatternMatchFilter("||jandan.net^*/moyu.png", ContentRequest.TYPE_ALL, false, null, -1)
        assertEquals(false, realJandan.check("https://i.jandan.net/"))
    }
}
