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
// Modified for Sofa TV Browser (2026), from Fulguris commit 66d56d45: plain JUnit and RequestUrl instead of Mockito and AssertJ.

package jp.hazuki.yuzubrowser.adblock.filter.unified

import app.sofabrowser.adblock.RequestUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartsWithFilterTest {

    @Test
    fun check() {
        assertTrue(StartsWithFilter("livedoor.jp/js/c2.js", 0, false, null, -1).check(RequestUrl("http://parts.blog.livedoor.jp/js/c2.js")))

        val uri = RequestUrl("http://livedoor.jp/?a=google.com")
        assertTrue(StartsWithFilter("livedoor.jp", 0, false, null, -1).check(uri))
        assertFalse(StartsWithFilter("google.com", 0, false, null, -1).check(uri))
    }
}
