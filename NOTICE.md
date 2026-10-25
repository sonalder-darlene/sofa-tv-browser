# Third-party notices

Sofa TV Browser bundles the following third-party material. Sofa TV Browser itself is licensed under the GNU
General Public License, version 3 only (see LICENSE).

## Material Symbols (icons)

Icons in `app/src/main/res/drawable/ic_*.xml` and the sofa glyph used in the launcher icon and banner come
from [Material Symbols](https://github.com/google/material-design-icons) by Google, licensed under the
Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).

## StevenBlack/hosts (ad and tracker domains)

`app/src/main/assets/blocklist-hosts.txt.gz` is derived from the unified hosts file at
https://github.com/StevenBlack/hosts.

> The MIT License (MIT)
>
> Copyright © 2026 Steven Black
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
> documentation files (the “Software”), to deal in the Software without restriction, including without
> limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
> the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
> conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial
> portions of the Software.
>
> THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
> LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
> EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
> AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
> OR OTHER DEALINGS IN THE SOFTWARE.

## HaGeZi's Pop-Up Ads list (pop-up and redirect domains)

`app/src/main/assets/blocklist-popups.txt.gz` is the "Pop-Up Ads" list of HaGeZi's DNS blocklists
(https://github.com/hagezi/dns-blocklists), copyright HaGeZi, licensed under the GNU General Public License,
version 3, the same licence as Sofa TV Browser (see LICENSE). It is refreshed with `tools/update-popup-list.sh`.
The list's own disclaimer (no warranty; a listed domain is a filtering decision, not an accusation) is at
https://github.com/hagezi/dns-blocklists#disclaimer.

## Yuzu Browser ad-block engine (filter rules)

The filter-rule engine in `app/src/main/java/jp/hazuki/yuzubrowser/adblock/` (parsing Adblock Plus and uBlock Origin
network rules, matching them, and storing them compiled) comes from Yuzu Browser by Hazuki
(https://github.com/hazuki0x0/YuzuBrowser), as adapted in Fulguris (https://github.com/Slion/Fulguris, commit
66d56d4501575324f09621a5babcbf4bc7641118). The four tests in `app/src/test/java/jp/hazuki/yuzubrowser/` come from the
same place. Only files whose own header carries the Apache License are used, and each keeps that header:

> Copyright (C) 2017-2019 Hazuki, Copyright 2020 Hazuki
>
> Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
> with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
>
> Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
> on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
> the specific language governing permissions and limitations under the License.

The full licence text is in `LICENSE-APACHE-2.0.txt`, also shipped in the app. Sofa TV Browser changed every one of
these files, and each says how in a line under its header: plain Kotlin instead of Android types, no rules that change
headers, parameters or redirects, page-wide exceptions, safer cache reading, and fixes. Sofa's own code around it
(lists, element hiding, settings) is new and under Sofa's licence. No Fulguris code under its CPAL-1.0 or MPL-2.0
licences is included.

## AndroidX WebKit (`androidx.webkit`)

Compiled into the app from the Android Open Source Project
(https://developer.android.com/jetpack/androidx/releases/webkit), Copyright The Android Open Source Project,
licensed under the Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).

## Search engine icons

`app/src/main/res/drawable-nodpi/engine_*.webp` are the icons the search engines publish on their own sites,
resized onto a common rounded tile. They are trademarks of their respective owners and are shown only to identify
each service. They are bundled so that the first-start screen contacts no one.

## Screenshots

The store screenshots in `fastlane/metadata/android/en-US/images/tvScreenshots/` show web pages as Sofa displays
them:

- `2.png` and `3.png` show the Wikipedia article [Couch](https://en.wikipedia.org/wiki/Couch), whose text is by
  Wikipedia contributors under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Its photo,
  [2009-05-16 Main office lobby at Hampton Forest Apartments](https://commons.wikimedia.org/wiki/File:2009-05-16_Main_office_lobby_at_Hampton_Forest_Apartments.jpg),
  is by Ildar Sagdejev (Specious), also under CC BY-SA 4.0. Wikipedia and its logo are trademarks of the
  Wikimedia Foundation.
- Site icons and names on the home screen (`1.png`) and the search engine icons (`4.png`) are trademarks of their
  respective owners.
