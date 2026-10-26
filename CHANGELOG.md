# Changelog

## 1.2.0 (2026-10-26)

**Sofa Player**

- **Sofa's own video player:** in fullscreen, Sofa now plays the site's stream in its own player when it can: 4K at
  full resolution on 4K TVs, HDR when the stream and the TV have it, and a choice of quality, sound and subtitles on
  the bar. It takes over at the same moment, without a jump, and "Site's player" on the bar goes back. Protected
  videos and videos under a minute stay with the site's player. Settings → Sofa Player turns it off.
- At the end of a video, the site's player plays the last moment, so the site moves on as usual (its next episode
  or end screen).
- **HDR stays HDR:** when a stream offers HDR and SDR versions, Auto keeps to the HDR ones on HDR TVs (and to SDR on
  others), so a film doesn't change look halfway.
- **Quality:** pick one by hand and it stays for that site until Sofa closes.
- **Subtitles** follow the TV's caption setting. When the stream has none of its own, the page's subtitle files are
  used, and the ones the site was showing stay on.
- If Sofa Player only gets a software decoder while the TV has a hardware one, it pauses the site's video and tries
  once more.
- Match frame rate uses the stream's own frame rate, and Video info shows the stream's format, the decoder and the
  frames shown and dropped.

**Play in another app**

- **Works on many more sites:** Sofa now hands over the whole stream the site's player uses, not just one part of
  it, with the page's subtitle files and the position, and with what the site expects from a player. Sites that
  only let the browser read their stream get a private copy that VLC and Just Player can open.
- **Settings → Play in…** chooses the app, or "Ask every time" to pick on each press.
- **Always play in another app (experimental, off by default)** sends fullscreen videos straight to that app.
- When a video can't be handed over (protected, only inside the page, or no player installed), fullscreen says why.

**Game pads**

- Xbox, PlayStation, 8BitDo and other pads work like in a console browser: the left stick moves the pointer, the
  right stick scrolls, A selects, B goes back, X watches the page's video, Y searches, and the bumpers and triggers
  go back, forward and page up and down. In fullscreen video they play, seek and switch subtitles. Settings → Game
  controller shows every button.

**Links and images**

- **Hold OK on a link or an image** for its menu: open it in another app, copy or share its address, or download
  it (links to files and pictures). Anywhere else, holding OK still switches to scroll mode.

**Better ad blocking**

- **Filter lists** in the formats of uBlock Origin, Adblock Plus and hosts files: Sofa now understands rules for
  parts of a site (such as `/ads/banner`), rule options, exceptions, and rules that hide the empty boxes ads leave
  behind, including ads a page adds after it has loaded.
- **Stronger ad blocking:** one switch in Settings adds EasyList and EasyPrivacy.
- **Settings → Filter lists:** add lists by address, switch each one on or off, see how many rules each brings,
  update them all at once, or go back to the copy that came with Sofa.
- **Automatic list updates,** every few days, if you want them: Sofa asks once.
- The two lists Sofa came with stay on. A blocklist address you set yourself is kept as a list of its own.
- Blocking still happens entirely on the TV, and lists are downloaded without cookies.
- **Faster start:** lists are stored ready to use, so they load in a moment instead of being read again each time.

**Far fewer pop-ups**

- A press anywhere on a page no longer opens an ad in place of the page: windows that a script opens to another
  site are blocked unless you pressed a link to it, and so are the invisible links that pop-under networks lay over
  a page. Your press then usually reaches what you were pressing.
- A press on a link can't also open an ad, and a page can't send itself to an ad site as it opens a window.
- The pop-up rules of filter lists now count too.
- Ads with a broken security certificate no longer interrupt the page with a warning; only the page itself can.
- Buttons that open another site's window on purpose (some "Sign in with…" or payment buttons) are blocked like
  pop-ups too: "Allow once" in the menu opens them.

**Smaller things**

- **0.75x** playback speed.
- "Continue from" no longer turns up on another video or page.
- **Fixed:** after changing a setting, the focus went back to the top of Settings; it now stays on that setting.

## 1.1.0 (2026-10-20)

**Watching video**

- **Watch:** press Play on the remote, or Watch in the menu, and Sofa finds the video on the page and plays it
  fullscreen, even when it comes from another site.
- **New buttons in fullscreen:**
  - subtitles on and off, when the video has them;
  - picture-in-picture, on TVs that support it;
  - Video info: the video's quality and frame rate, and whether the TV decodes it in hardware;
  - Play in another app: open the video in VLC, MX Player or another player. Android asks the first time and Sofa
    remembers; hold OK on the button to pick another one.
- **Continue where you stopped** in long films.
- **Sharper video on 4K TVs:** in fullscreen, sites can now send their 4K stream.
- **Smoother films (experimental):** turn on "Match frame rate" in Settings, and the TV switches to the film's
  frame rate, such as 24 per second, while it plays fullscreen. Only on TVs that support it; the screen goes dark
  for a moment when it switches.
- **The screen stays on** while a video plays, and can go to sleep again when you pause.

**Fewer ads and pop-ups**

- Pop-ups and pages that jump to ad sites on their own are blocked, now with a second list made for pop-up ads.
  Links you press still work, and "Allow once" in the menu opens something that was blocked.
- Fewer "please turn off your ad blocker" messages.
- The Ad block button in the menu shows how many things were blocked on the page.

**Browsing and settings**

- **Find in page,** typed or spoken.
- **Keep history for** 7, 30 or 90 days, always or never. Your choice from 1.0.0 carries over.
- **Protected video** (the kind streaming services lock): ask for each site, always allow, or always block.
- Sofa tells you when your TV's web engine is very old and needs an update, and About shows its version.
- **Fixed:** ad blocking and its count now work from the moment a page starts loading.

## 1.0.0 (2026-10-19)

First public release.

- **Remote-control browsing:** a D-pad mouse pointer that accelerates while held, with hover, drag, edge
  scrolling, a scroll mode, and a D-pad mode for sites built for keyboards.
- **Back-key menu:** address, navigation, bookmarks, history, per-site ad blocking, "Browse as" user agents and
  zoom.
- **Home screen:** your own quick-access tiles, most visited sites and recent bookmarks.
- **Video:** a fullscreen control bar with seeking, play/pause, scrubbing and playback speed.
- **Privacy:**
  - ads and trackers blocked on the device, and third-party cookies blocked;
  - no Do Not Track header, and a desktop user agent by default, so Sofa blends in;
  - private browsing in a separate WebView profile where the WebView supports it;
  - no analytics, accounts or servers of its own.
- **Search:** you choose the engine at first start, from twelve. Suggestions come from your bookmarks and history,
  and voice search runs in Sofa's own screen.
- **Downloads:** pause and resume, automatic retries, and downloads that keep going in the background.
- **Themes:** Dark and Light, or the same as the TV.
- **Updates:** opt-in update checks for copies installed from GitHub.
