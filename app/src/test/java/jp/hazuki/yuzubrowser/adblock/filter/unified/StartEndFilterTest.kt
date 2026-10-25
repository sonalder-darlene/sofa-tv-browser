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

class StartEndFilterTest {

    @Test
    fun check() {
        assertTrue(StartEndFilter("ad-stir.com", 0, false, null, -1).check(RequestUrl("https://js.ad-stir.com/js/nativeapi.js")))
        assertTrue(StartEndFilter("ad-stir.com", 0, false, null, -1).check(RequestUrl("https://js.ad-stir.com")))
        // Added for Sofa: look-alike hosts and the pattern elsewhere in the address don't match.
        assertFalse(StartEndFilter("ad-stir.com", 0, false, null, -1).check(RequestUrl("https://bad-stir.com/")))
        assertFalse(StartEndFilter("ad-stir.com", 0, false, null, -1).check(RequestUrl("https://ad-stir.com.example/")))
    }
}
