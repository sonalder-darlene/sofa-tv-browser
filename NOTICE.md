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
