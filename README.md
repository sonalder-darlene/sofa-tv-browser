# Sofa TV Browser

A small, private web browser for Android TV and Google TV, made to be used with the remote. On the TV it is
called Sofa.

![Sofa's home screen: search, top-bar buttons, quick-access tiles and most visited sites](fastlane/metadata/android/en-US/images/tvScreenshots/1.png)

- **A mouse pointer on the D-pad**, with hover, drag, and scrolling by pushing against the screen edge.
- **A menu on the Back key**: address, back and forward, bookmarks, history, zoom, ad blocking per site, and
  "Browse as" desktop, mobile or TV.
- **A home screen** with your own tiles, your most visited sites and your bookmarks.
- **Controls for fullscreen video**: seek, pause and playback speed.
- **Search** with the engine you choose at first start, and voice search.
- **Downloads** that pause, resume and carry on in the background.
- **Dark and Light themes.**

The app is under 1 MB.

## Install

Download `sofa-<version>.apk` from the [latest release](https://github.com/sonalder-darlene/sofa-tv-browser/releases/latest)
and install it on the TV, for example with a file manager app or `adb install`. Sofa needs Android 7.0 or newer.

If Play Protect says "App blocked to protect your device", choose **More details → Install anyway**. It says this
about apps from developers it hasn't seen before.

## Updates

Sofa can check GitHub for new versions. It asks at first start, and you can change your answer in Settings →
Updates. It checks at most once a day, and nothing installs without your confirmation.

Copies installed by an app store or by Obtainium never offer this; the store keeps them up to date.

## Privacy

- **Nothing of its own:** no accounts, analytics, ads, crash reports or servers. Apart from the sites you visit,
  Sofa only contacts your search engine (for suggestions, if you turn them on), GitHub (for update checks, if
  you turn them on) and the address you choose for blocklist updates. Voice search uses the TV's speech service.
- **Blocking:** ads and trackers are blocked on the TV against the StevenBlack hosts list (about 71,000 domains),
  and third-party cookies are blocked.
- **Blending in:** few browsers send Do Not Track and desktop Chrome is the most common browser, so Sofa sends no
  Do Not Track and uses a desktop Chrome user agent by default. Global Privacy Control can be turned on in Settings.
- **Pages get nothing extra:** no camera, microphone, location or files.
- **Private browsing** uses a separate WebView profile that is deleted afterwards. On TVs whose WebView is too old
  for that, it keeps no history and erases site data when it ends.
- **WebView reporting:** usage reports to Google are off, and so are Safe Browsing lookups unless you turn them on.

## Not there yet

Tabs, find in page, reader mode, typing from a phone, and translations.

## For developers

**Build**

```sh
nix develop -c ./gradlew assembleStandardRelease   # app/build/outputs/apk/standard/release/
nix develop -c ./gradlew testStandardDebugUnitTest testPlayDebugUnitTest lintStandardDebug lintPlayDebug
```

Without Nix, use JDK 17 or newer and an Android SDK with platform 36 and build-tools 36.1.0 in `ANDROID_HOME`.
The project uses AGP 9.4.1, Kotlin 2.4.21 and Gradle 9.8.1. The UI is plain framework views, and
`androidx.webkit` is the only dependency.

**Two builds**

- `standard` is for GitHub releases, F-Droid and other stores. Its opt-in updater (`app/src/standard/`) stays
  hidden when an app store installed Sofa or when the APK isn't signed with the release key. A fork can point it at
  its own releases with `-Psofa.updateUrl=https://api.github.com/repos/<owner>/<repo>/releases/latest` and
  `-Psofa.releaseCert=<sha-256>`.
- `play` is for Google Play, which forbids apps that update themselves. It has no updater
  (`./gradlew bundlePlayRelease`).

**Signing**

Official APKs are signed with this certificate (SHA-256):

```
74:E2:3F:09:BB:4B:01:16:00:C3:8E:7D:55:5F:B3:89:26:AC:C3:83:50:29:B7:FC:02:E4:84:66:C3:4C:8A:A9
```

Check a download with `apksigner verify --print-certs`. Release builds are reproducible: building a release tag
gives the same APK contents.

To sign your own builds, put a `keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and
`keyPassword` at the repository root (it is gitignored), or set `SOFA_KEYSTORE_FILE`, `SOFA_KEYSTORE_PASSWORD`,
`SOFA_KEY_ALIAS` and `SOFA_KEY_PASSWORD`. Without them, release builds use the debug key.

**Releasing**

1. Set `versionName` and `versionCode` (MAJOR × 10000 + MINOR × 100 + PATCH) in `app/build.gradle.kts`.
2. Add the changes to `CHANGELOG.md` and to `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
   (500 characters at most).
3. Commit, tag `v<version>`, and build `assembleStandardRelease` from a clean checkout of the tag. The APK records
   its commit, so it must be built from exactly the tagged one.
4. Push, then attach the APK to a GitHub release as `sofa-<version>.apk`. The updater looks for that name and shows
   the first 700 characters of the release notes.

**Testing**

`nix develop .#emulator` provides `adb` and an Android TV 14 emulator:

```sh
avdmanager create avd -n sofatv -k "system-images;android-34;android-tv;x86" -d tv_1080p
"$ANDROID_HOME/emulator/emulator" -avd sofatv
```

On a TV, turn on network debugging, then run `adb connect <tv-ip>:5555` and `adb install -r <apk>`.
`adb install -i <store package>` makes Sofa look as if a store installed it, which hides the updater.

**Layout**

```
app/src/main/java/app/sofabrowser/   browser/ (pointer, WebView), ui/, downloads/, adblock/, data/
app/src/standard/                    the updater
app/src/play/                        an empty stand-in for the updater
fastlane/metadata/android/           store listing: descriptions, changelogs, graphics, screenshots
art/                                 SVG sources of the banner and store graphics
tools/                               update-blocklist.sh, and render-art.sh (needs rsvg-convert, oxipng, Inter)
```

## Licence

Sofa TV Browser is free software under the [GNU General Public License v3.0 only](LICENSE) (`GPL-3.0-only`). The
third-party parts (Material Symbols icons, the StevenBlack hosts list and `androidx.webkit`) are listed in
[NOTICE.md](NOTICE.md).

Sofa follows the remote-control design of BrowsHere, TCL's TV browser, but contains none of its code, assets or
text.
