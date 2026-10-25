# User scripts (planned)

People ask for Firefox-style extensions in Sofa. Sofa is built on Android System WebView, which can't run browser
extensions: there is no extension API, no background pages and no isolated worlds. What WebView can do is run a script
in every page Sofa loads. That is what Greasemonkey-style user scripts are: a single `.user.js` file with a short
header that says which sites it runs on. Thousands exist, for example on Greasy Fork, to remove clutter, add a
download button, skip an intro or fix a site that works badly on a TV.

This document is the plan. Nothing here is built yet.

## What scripts can do

The first version runs ordinary user scripts, those written with `@grant none`, which is most of them.

- **The header** (`// ==UserScript==` … `// ==/UserScript==`) is read for `@name`, `@namespace`, `@version`,
  `@description`, `@match`, `@include`, `@exclude`, `@exclude-match`, `@run-at` (`document-start`,
  `document-end`, `document-idle`) and `@noframes`. A script without `@match` or `@include` doesn't run anywhere.
- **Where it runs:** only on `http` and `https` pages whose address matches. WebView can only choose sites by origin,
  so Sofa installs each script for the origins it needs and checks the full address in a small wrapper before the
  script itself runs.
- **When it runs:** at document start, or after the page is parsed or loaded, as `@run-at` asks.
- **A little help:** `GM_addStyle`, and `GM.getValue` / `GM.setValue` with storage of its own for each script, kept by
  Sofa on the TV.

Left out at first, on purpose:

- **`GM_xmlhttpRequest` and `@connect`.** They let a script fetch from any site with the user's cookies, past the
  rules that keep sites apart. That is the most dangerous thing a user script can do.
- **`@require` and `@resource`.** They load more code from elsewhere at run time, so what runs could differ from what
  the user installed. Every line a script runs has to be in the file the user saw.
- **Automatic updates.** Updates are checked only when the user asks, like filter lists.
- **Menu commands, notifications, downloads, opening tabs and the clipboard** (`GM_registerMenuCommand`,
  `GM_notification`, `GM_download`, `GM_openInTab`, `GM_setClipboard`). Each would need its own TV design.
- **`unsafeWindow` and sandboxing.** WebView has no isolated world, so scripts run in the page's own context, as with
  `@grant none` elsewhere. Pages can see and interfere with them. This is a limit of WebView, and the install screen
  says so in plain words.

A script that asks for something left out is refused at install, with the reason shown. It isn't installed half
working.

## Finding, installing, managing and removing scripts with a remote

Nothing on the TV should need typing.

- **Finding:** browse a script site such as Greasy Fork in Sofa, with the pointer. Sofa doesn't ship or promote a
  catalogue of its own.
- **Installing:** pressing a script's install link opens Sofa's install screen instead of the file. It shows the name,
  description and version, the sites it runs on in plain words ("all sites" stands out), the help it asks for, and
  the source in a scrollable view. Install is not the button that has focus; the user has to move to it. Pages can't
  start an install by themselves: it needs a press on a link, like a download.
- **By address:** Settings → User scripts → "Add from address", for a script found on a phone. Typing from a phone
  (on the roadmap) makes this easier.
- **Managing:** Settings → User scripts lists each script with its sites and an on/off switch. Pressing OK on a script
  opens: Turn off / on, Check for an update, View source, Remove.
- **On a page:** the Back-key menu shows "Scripts here (2)" when scripts ran on the page, with a switch for each, so a
  script that breaks a site can be stopped where the problem is.
- **Updates:** "Check for an update" fetches the script's `@updateURL` or `@downloadURL`. The new version is shown like
  a new install, and it must be confirmed again if it asks for more sites or more help.
- **Removing** deletes the script and its stored values.

## Privacy and security

A user script can read everything on the pages it runs on, passwords included, and send it anywhere a page can. The
design keeps that power in the user's hands.

- **Only what the user installed runs.** Nothing comes with Sofa, nothing installs or updates on its own, and all of
  a script's code is in the file shown at install.
- **No extra reach.** Scripts get no more than a page has: no cross-site requests with cookies, no files, no access
  to Sofa's settings, history or bookmarks.
- **Nothing leaves the TV** apart from what the user asks for: downloading a script or checking it for an update,
  without cookies or identifiers, as for filter lists.
- **Private browsing:** scripts don't run in private browsing unless the user allows it for that script ("Also in
  private browsing", off by default, as in Firefox). When allowed, values a script stores in private browsing are kept
  apart and deleted when private browsing ends.
- **Sofa's own pages are off limits.** Scripts never run on Sofa's home screen or other built-in pages.
- **Limits:** scripts of at most 1 MB, and stored values of at most 1 MB per script, so a script can't fill the TV.
- **Honest wording.** The install screen says, in plain words, that the script can see and change the sites it runs on.

## Licence note

Fulguris has a user-script implementation, but it is under CPAL-1.0, which is incompatible with Sofa's GPL-3.0-only
licence. Sofa's version must be written independently of that code: no copying, no translating, and no reading it
while writing Sofa's version. The sources to work from are the public descriptions of the format: the Greasemonkey
and Tampermonkey documentation of the header and the GM functions, and Chrome's documentation of match patterns.
Sofa's ad blocking took Apache-2.0 files from Fulguris (see NOTICE.md). That doesn't extend to the user scripts.

## Open questions

- Is `@grant none` plus `GM_addStyle` and storage enough for the scripts people actually want on a TV? A survey of the
  most installed scripts on Greasy Fork would tell.
- Should `@require` be allowed later when it names a fixed version with a hash (subresource integrity), so the code
  still can't change?
- Should Sofa offer a short, reviewed list of TV-friendly scripts, or stay out of choosing?
- How should the install screen show "the sites it runs on" for long `@match` lists, on a screen read from the sofa?
- Should a script that fails often (errors in the console) be switched off for that site automatically?
- How do user scripts and ad blocking meet: should the per-site ad-block switch also stop scripts?
- Where do stored values live: in Sofa's database, or in a file per script that is easy to delete?
- Do old WebViews without document-start scripts get the feature at all, or does Settings hide it as it hides other
  features they lack?
