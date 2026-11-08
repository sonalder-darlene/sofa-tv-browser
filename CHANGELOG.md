# Changelog

## 1.3.0 (2026-11-08)

**Site filters**

- **Block whole sites by category** (Settings → Site filters, each off until turned on): adult sites, gambling (in
  three sizes, "More sites" recommended), unsafe sites (fake downloads and malware) and drugs, or any list of
  domains by its address. A blocked site is stopped however you reach it, a typed address, a bookmark, a link or a
  redirect, and so is anything a page loads from it, with ad blocking on or off. So is what Sofa fetches itself,
  which never contacts a blocked site, not even when an allowed one redirects there: a video from a blocked site
  doesn't play in Sofa Player, isn't handed to another app with "Play in…" (redirects and a stream's playlists
  followed), and isn't downloaded, nor is a start page picture or a file imported from a web address; a notice says
  why. The block screen names the list
  and offers Go back or Allow this site; Allowed sites in Settings takes a site back. Go back returns to the last
  page that isn't blocked, and a blocked link from another app, or opened in a new tab, shows the block screen over
  the page you're on without making a tab for it; a blocked site is never kept as a tab or as where you left off. A
  link that comes while a block screen shows opens in front of it, and a tab still on a blocked site shows its block
  screen again when you come back to it. In private browsing an allow lasts only until private browsing ends and is
  never stored.
- Reading a large list takes far less memory than before, which also helps the ad lists.

**Custom DNS**

- **DNS over HTTPS** (Settings → DNS, off until you choose): a main and a fallback DNS from services ranked in four
  groups, from most to least private (Private, Private with trade-offs, Limited privacy, Utility-first), each with
  what it blocks; filtered and unfiltered versions where a service has both; or any other service by its address.
  It covers pages, Sofa Player, downloads and list updates. When neither service can be reached, the TV's own DNS
  takes over so pages keep loading, and a notice says so; with **Never use your TV's DNS** on, sites stop loading
  instead. A site a service refuses or can't look up stays unresolved and never goes to the TV's DNS. Home-network
  names always use the TV's DNS. Test shows how fast each one answers.

**Parental PIN**

- **A PIN for Settings and the blockers** (Settings → Parental PIN, off until you set one): once set, Sofa asks for
  it before Settings open, ad blocking is turned off, a site is let through Site filters, private browsing starts,
  History is deleted, bookmarks are imported or a picture from a page becomes the start page background. It is
  entered with the remote's arrows or number keys, kept only as a salted hash, and wrong tries lead to a growing
  pause.

**Menu buttons**

- **Your own menu** (Settings → Menu buttons): put the menu's buttons in the order you like and hide the ones you
  don't use. Pick a button up with OK, move it with the arrows and put it down with OK; the menu as it will look is
  shown below the list. Home and Settings always stay in the menu. Clearing browsing data keeps your menu, and Reset
  brings back the default one.

**Bookmarks**

- **Import and export bookmarks** (Bookmarks → Import or export) as the bookmarks file every desktop browser
  reads and writes. Export saves it in Download; import takes it from the TV's file picker where there is one, from
  a web address, or from a file Sofa saved. Bookmarks you have stay as they are, and copies are skipped. With a
  parental PIN, an import asks for it.
- **Long lists stay quick:** Bookmarks and History make only the rows on screen, so thousands of imported
  bookmarks open at once and take little memory, and Delete all clears them in one go. The start page reads only the
  bookmarks it shows.

**Settings backup**

- **Export and import settings** (Settings → Backup): one file with your settings, filter lists, allowed sites,
  Quick access and bookmarks, to set up another TV or to come back after a reset. It is protected with a password
  you choose, with the encryption built into Android (PBKDF2-HMAC-SHA512 and AES-256-GCM), unless you choose "No
  password". Import takes the file from the TV's file picker, a web address or a file Sofa saved: your settings
  become the file's, and its lists, sites, Quick access and bookmarks are added to yours. Your PIN, history,
  cookies, open tabs and start page picture are never in the file.

**Start page**

- **Arrange the start page** (Settings → Start page): reorder and hide its rows and buttons, and show up to 48
  bookmarks, in one to four rows of 12. It is edited like the menu, with the start page as it will look shown
  below the list. Settings and the search bar always stay, and a hidden Private button comes back while private
  browsing is on. Clearing browsing data keeps your start page, and Reset brings back the default one.
- **A background of your own** (Settings → Start page background): choose a colour, gradient or your own picture
  (up to 4K) for the start page. Hold OK on a picture on any page and choose "Set as start page background", or,
  on TVs that have one, pick a picture with the file picker. Sofa keeps its own copy on the TV, only the part the
  start page shows and without the photo's details (EXIF), and Dim keeps the titles readable over it. The
  picture's memory is freed while a page is in front. Private browsing keeps its own look.
- **Open at start** (Settings → Open at start): Sofa can start on the start page (as before), on a web page you
  choose (typed once, or the page that is open), or where you left off, which also brings your page back when the
  TV closes Sofa in the background. A link sent from another app still opens first, and Home in the menu always
  opens the start page. Where you left off never keeps a private page, and clearing browsing data, or "Clear
  browsing data on exit", forgets it.
- **A page you leave stays quiet**: behind the start page, and while Sofa is in the background, a page no longer
  plays sound through Web Audio, its videos pause in every frame, and its scripts stop. If it tries to go to another
  site meanwhile, it stays where it was; when you come back, a notice names the site and Allow once in the menu
  opens it. Back to the page resumes its sound; videos stay paused where they were, also after the remote's Home key.

**Tabs**

- **Tabs in the menu**: a strip next to the address, at the top of the menu. New tab opens the start page in a tab
  of its own; OK on a tab brings it up; OK on the tab you are on, or holding OK on any tab, closes it, the others
  or all of them. Hold OK on a link for "Open in new tab". Up to 10 tabs.
- **Light on memory**: only the tab you look at runs. The others rest like a page behind the start page: no sound,
  videos paused, nothing opened on their own. Past what the TV has room for, after 10 minutes out of sight, or when
  Android runs short of memory, a tab's page is unloaded (so its scripts stop too), and loads again with its back
  and forward history when you come back to it.
- Tabs with the same title, such as two pages of one site, show their addresses in the strip, shortened in the
  middle so the part where they differ shows.
- **Private tabs kept apart**: private browsing has its own tabs, and your normal tabs wait until it ends.
- **Tabs come back after a restart**, as addresses only: never private tabs, and none with "Clear browsing data on
  exit". Each page loads when you choose its tab. Open at start still decides what shows first: the start page,
  your web page (in a tab of its own, the same one each time), or the tab you were on.
- A link sent from another app opens in a new tab, so the page you had stays. Back on the start page of a new tab
  you didn't use closes it again.
- A window a page opens still replaces the page in its tab, so the pop-up blocker and "Allow once" work as before.
- Fixed: private browsing's cookies, site storage and cache are erased as soon as it ends, not at the next start,
  and a second private session in the same run no longer finds the first one's cookies.

**TalkBack**

- **Sofa's own screens work with a screen reader**: every button, tile and row says what it is, switches say on or
  off, the tab in sight and the chosen colour say they are selected, the parental PIN's digits and a video's position
  say their value as it changes, the Menu buttons and Start page editors say where an item is as you move it, and
  notices over a video are read out. With TalkBack on, pages open without the pointer, so the arrows move between
  links and fields and TalkBack reads them; Pointer in the menu still brings it back.

**Support Sofa**

- **Donation addresses** (Settings → About → Support Sofa, and the README): Bitcoin (silent payment), Lightning,
  Monero, Zcash (shielded) and Ethereum. Each shows its QR code, large enough to scan with a phone's wallet app from
  the sofa, with the full address and a Copy button. Nothing is sent anywhere. The Google Play build doesn't have it.

**Smaller things**

- Fixed: a pop-under that rides on a press on a picture link (a series card, a thumbnail) is blocked, and the link
  opens. Sofa now reads where a picture link goes; before, a press on one let any window through.
- "Continue from" goes as soon as the video reaches that place, whether it played there or you skipped ahead.
- Video info ends with the TV's own decoders: for AV1, HEVC, VP9 and H.264, the largest picture its hardware and
  software decoders take, as the TV reports it. A codec the TV has no decoder for but WebView decodes itself says so.
- When the TV's decoder can't play a picture, the notice adds that another app may play it with "Play in…".
- Back on the start page from a page, the card you opened it with has focus again, not the first one.
- A page that didn't load keeps its address as its name on its tab, in a new bookmark and in History, not the
  error page's title.
- Update all lists shows "Updating…" on the lists until they are done.
- Dialog buttons, "Updated …" times and dates are in English like the rest of Sofa, on a TV set to another
  language too.
- A filter list or bookmarks file that couldn't be downloaded says why in plain words ("example.com wasn't found.
  Check the address.") instead of the network's technical message.
- Private browsing no longer leaves an empty settings file behind for each session.
- A TV without a working Android System WebView now gets a screen that says so and what to look for, instead of
  Sofa closing at once.
- Turning ad blocking off for one site in private browsing lasts only until private browsing ends and is never
  stored, like a site let through Site filters there.
- Notices over the video stay up longer when they say more (5 seconds, and a second more for every ten words), and
  the notice that a page tried to open a site while you were away stays up longer too.
- The old-WebView warning now starts below version 144.
- The bundled lists are refreshed: about 78,000 StevenBlack and 80,000 HaGeZi pop-up domains.

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
