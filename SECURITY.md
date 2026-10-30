# Security

## Reporting a problem

Please report security problems privately, not in a public issue: on GitHub, open the repository's **Security**
tab and choose **Report a vulnerability**
([direct link](https://github.com/sonalder-darlene/sofa-tv-browser/security/advisories/new)). Only the maintainer
sees the report.

Say which Sofa version you use (Settings → About Sofa), the TV or device and its Android version, and how to
reproduce the problem: a test page, a stream address or steps with the remote are enough.

Sofa is a small project: there is no bounty and no fixed reply time. The aim is to answer within a week, and to
ship a fix as a patch release as soon as it is ready. Reports are credited in the release notes unless you would
rather not be named.

## Supported versions

Only the latest release gets fixes. Copies from GitHub offer it by themselves if update checks are on (Settings →
Updates); app stores and Obtainium bring it as usual.

## What counts

Sofa's own code, for example:

- the scripts Sofa adds to pages and the messages pages can send back to it (Watch, video controls, ad blocking,
  downloads);
- links to other apps (`intent:` addresses), pop-up and redirect blocking, certificate warnings and site
  permissions;
- downloads: file names, where files go, opening them;
- the updater: anything that would make Sofa install an APK that isn't a genuine newer Sofa;
- Sofa Player and "Play in…": stream addresses, playlists and subtitles from pages, and what Sofa hands to other
  players;
- filter lists: a list that could do more than hide or block things;
- private browsing: anything that stays on the TV after it.

## What doesn't

- **Bugs in the web engine.** Sofa shows pages with the TV's Android System WebView, which Google updates.
  Report Chromium and WebView bugs to Google at https://issues.chromium.org; their fixes reach Sofa without a Sofa
  release. Sofa warns when a TV's WebView is very old.
- **Bugs in Android or the TV's firmware**, which go to the TV maker.
- Ads or trackers that the filter lists miss, and sites that detect the ad blocking: those are ordinary
  [issues](https://github.com/sonalder-darlene/sofa-tv-browser/issues).
- Problems that need someone who already controls the TV, for example through `adb`.
