# Changelog

## 1.2.2 (2026-10-30)

**Sofa Player**

- **4K and 60 fps on more TVs:** Auto now plays the stream's best picture, such as 4K or 1440p at 60 fps, when the
  TV's hardware decoder says it plays it. Before, many TVs got a smaller picture, often at 30 fps, because of the
  frame rates their maker listed. If such a picture drops frames, Auto moves to a smaller one and says so.
- **Steps down instead of giving up:** when the TV can't decode one of the stream's pictures, Sofa Player goes on
  from the same point with another one (smaller, or another codec), and says which over the video and in Video info.
- **One decoder per video:** the page's own video now rests while Sofa Player plays, which frees its hardware
  decoder and some memory, and helps a second 4K video in a row find one. When Sofa Player only gets a software
  decoder, it keeps to 1080p at 30 fps, or hands the video back to the site's player.
- **Open videos in Sofa Player straight from the page:** a video you start on the page yourself, with sound and a
  minute or longer, goes fullscreen in Sofa Player at once, without Back and Watch. Muted previews and short ads
  stay on the page. On by default; Settings → "Open videos in Sofa Player" turns it off.
- Fixed: on Back from Sofa Player, a muted preview elsewhere on the page could get the position instead of the video
  you watched.

**Watch**

- **Watch by itself** (Settings, off by default): runs Watch on its own once a page has loaded, on every site or only
  on the sites you choose in the menu. It runs once per page and stops as soon as you press a key.
- Watch now reaches a video under an invisible ad layer, and when a layer you can see covers the video, it says so
  instead of "No video found".

**Play in another app**

- Players that can't open Sofa's copy of a stream, such as C Media Player, get the site's address instead.

**Ad blocking**

- Sofa offers Stronger ad blocking (EasyList and EasyPrivacy) once at start, with what it costs. Settings changes it
  any time.
- The bundled lists are refreshed: about 77,000 StevenBlack and 51,000 HaGeZi pop-up domains.

**Other**

- A link sent from another app or a phone remote while Sofa's menu is open now closes the menu and loads.
- The old-WebView warning now starts below version 143.
- `SECURITY.md` says how to report a security problem privately.

## 1.2.1 (2026-10-27)

**Sofa Player**

- **Your choices stay:** the quality, sound and subtitles you pick on the bar now stay for the next videos, on every
  site: the same quality or the nearest lower one, and the same language for sound and subtitles. Private browsing
  uses them but saves nothing new. Settings → Sofa Player → "Forget my player choices" goes back to Auto and the
  TV's own settings.
- **Starts sharp:** with HLS streams, Sofa Player starts at the quality the site's player was showing, instead of a
  lower one for the first seconds.
- **Says why:** when Watch leaves a video with the site's player (protected, no stream Sofa Player can open, or one
  it can't read or play), a short note in fullscreen says why, and Video info keeps the reason.
- Fixed: after a video Sofa Player couldn't take over, later videos in the same session could stay with the site's
  player, because Sofa looked for their stream only once.

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
