# Sofa in detail

Everything Sofa does, what it sends where, and how its blocking and video work. For the short version, see the
[README](../README.md).

## Features

- **A mouse pointer on the D-pad**, with hover, drag, and scrolling by pushing against the screen edge. Hold OK
  on a link or image to open it in a new tab or another app, copy or share its address, download it, or set a
  picture as the start page background.
- **Tabs**, in a strip at the top of the menu: up to 10, with private ones kept apart. Only the tab you look at
  runs; the others wait, and a page left out of sight for 10 minutes, or on a TV short of memory, loads again when
  you come back to it. Tabs with the same title show their addresses.
- **Game pads** (Xbox, PlayStation, 8BitDo and others): the left stick moves the pointer, the right stick scrolls,
  and the buttons work as in a console browser. Settings → Game controller shows them all.
- **TalkBack**: Sofa's own screens work with the TV's screen reader. Buttons, tiles and switches say what they are
  and whether they are on, the PIN and a video's position say their value as it changes, and with TalkBack on,
  pages open without the pointer, so the arrows move between links.
- **A menu on the Back key**: address, Watch, back and forward, find in page, bookmarks, history, zoom, ad
  blocking per site, and "Browse as" desktop, mobile or TV. Settings → Menu buttons puts its buttons in your order
  and hides the ones you don't use.
- **A home screen** with your own tiles, your most visited sites and your bookmarks. Settings → Start page
  reorders and hides its rows and buttons, and shows up to 48 bookmarks; Settings → Start page background gives it
  a colour, a gradient or your own picture (up to 4K). Settings → Open at start has Sofa start on a web page of
  your choice, or where you left off, instead.
- **Bookmarks from your other browsers**: import the bookmarks file a desktop browser exports, from the TV's file
  picker, a web address or a file Sofa saved, and export yours the same way.
- **Settings backup**: your settings, lists, allowed sites, Quick access and bookmarks in one password-protected
  file, to set up another TV or to come back after a reset.
- **Watch**: one press of Play finds the page's main video and plays it fullscreen.
- **Sofa Player**: in fullscreen, Sofa plays the site's stream in its own player when it can, with 4K at full
  resolution, HDR, and a choice of quality, sound and subtitles that it keeps for the next videos. Otherwise the
  site's player carries on.
- **Controls for fullscreen video**: seek, pause, playback speed, subtitles, Video info, picture-in-picture, and
  Play in another app (Android asks once, Sofa remembers; hold OK on the button, or use Settings → Play in…, to
  choose again or to be asked every time).
- **Match frame rate** (experimental), on TVs that offer it: films play at 24 Hz instead of juddering at 60.
- **Ad blocking with filter lists** in the formats of uBlock Origin, Adblock Plus and hosts files, including
  hiding the empty boxes ads leave behind. One switch adds EasyList and EasyPrivacy.
- **Pop-ups blocked**, even from ad networks no list knows yet, with "Allow once" in the menu when you need one.
- **Site filters** (off until you choose): block whole sites by category, such as adult, gambling or unsafe sites,
  or with a list of your own.
- **Parental PIN** (off until you set one): a PIN before Settings open or a blocker is turned off.
- **Custom DNS** (off until you choose): look sites up over DNS over HTTPS with a main and a fallback service,
  picked from a list ranked from most to least private, or your own.
- **Search** with the engine you choose at first start, and voice search.
- **Downloads** that pause, resume and carry on in the background.
- **Dark and Light themes.**

## Privacy

- **Nothing of its own:** no accounts, analytics, ads, crash reports or servers. Apart from the sites you visit,
  Sofa only contacts your search engine (for suggestions, if you turn them on), GitHub (for update checks, if
  you turn them on), the addresses of your filter lists and Site filters lists (when you add, turn on or update
  them, or every few days if you turn on automatic updates), a web address you import bookmarks or settings from,
  and the DNS service you choose, if you choose one ([Custom DNS](#custom-dns)). Voice search uses the TV's speech
  service.
- **Blocking:** ads and trackers are blocked on the TV (see [Ad blocking](#ad-blocking)), and third-party cookies
  are blocked too.
- **Blending in:** few browsers send Do Not Track and desktop Chrome is the most common browser, so Sofa sends no
  Do Not Track and uses a desktop Chrome user agent by default. Global Privacy Control can be turned on in Settings.
- **Pages get nothing extra:** no camera, microphone, location or files.
- **Pages you leave stay quiet:** behind the start page, in a tab out of sight and while Sofa is in the background,
  a page plays no sound, its videos pause, and it can't go to another site on its own. When you come back, a notice
  names the site it tried, and Allow once in the menu opens it.
- **Private browsing** has its own tabs, in a separate WebView profile whose cookies, site storage and cache are
  erased as soon as it ends. On TVs whose
  WebView is too old for that, it keeps no history and erases site data when it ends.
- **Tabs** come back after a restart as addresses only: never private ones, and none with "Clear browsing data on
  exit".
  A video handed to another app (Play in…) may stay in that app's own history, which Sofa can't erase.
- **History** is kept for as long as you choose: 7, 30 or 90 days (the default), always or never.
- **Settings backups** (Settings → Backup) are protected with a password you choose unless you pick "No password":
  PBKDF2-HMAC-SHA512 (210,000 rounds) makes a key from it and AES-256-GCM encrypts the file, both built into Android.
  The file never holds your PIN, history, cookies, open tabs or start page picture.
- **WebView reporting:** usage reports to Google are off, and so are Safe Browsing lookups unless you turn them on.

### Custom DNS

Sofa looks sites up with the TV's own DNS until you choose a **main DNS** in Settings → DNS. From then on, every
site name is looked up over DNS over HTTPS: encrypted, so the network can't read or change the lookups. That covers
pages, Sofa Player, downloads and list updates. A **fallback DNS** is asked when the main one doesn't answer, and the
TV's own DNS when neither does, so pages keep loading. A name that doesn't exist isn't asked again elsewhere. Names
on the home network (no dot, or `.local`, `.lan`, `.home.arpa` and the like) always use the TV's DNS. **Test** times
a lookup with each one.

The built-in services are ranked from most to least private, from each one's own privacy policy (November 2026).
Each row says what it blocks, and services that offer a filtered and an unfiltered version have both:

| Group | Services |
|---|---|
| Private | Quad9, UncensoredDNS, HaGeZi DNS, dnsforge.de |
| Private with trade-offs | Cloudflare 1.1.1.1, DNS.SB, AdGuard DNS, CleanBrowsing |
| Limited privacy | Bancuh, Tiarap, Control D |
| Utility-first | Google Public DNS, DNS4EU, OpenDNS, DNS for Family |

Any other service can be added by its DNS over HTTPS address (`https://…`). The service you choose sees the name of
every site Sofa opens; that is what a DNS does. A service that blocks sites stops them on top of Sofa's own blocking.

WebView has no DNS setting, so Sofa runs a small proxy inside the app, reachable only from the TV itself
(127.0.0.1), that looks each site up and passes the connection through. HTTPS stays encrypted end to end between
Sofa and the site. On a network that has its own proxy, or with a WebView too old for it, the chosen DNS isn't used,
and Settings → DNS says so.

## Ad blocking

Everything is matched on the TV; nothing about your browsing leaves it.

- **Filter lists:** the StevenBlack hosts list (about 77,000 domains) and HaGeZi's Pop-Up Ads list (about 51,000) come
  with Sofa. **Stronger ad blocking** in Settings adds EasyList and EasyPrivacy, about 145,000 more rules, for TVs
  with a few MB of memory to spare; Sofa offers it once at start, never again once answered. Settings → Filter
  lists takes any other list by its address, in hosts, Adblock Plus or uBlock Origin format, and switches each list
  on or off.
- **Element hiding** removes the empty boxes and banners that blocked ads leave, also when a page adds them later.
- **Pop-ups:** a window a page opens replaces the page in its tab (only you open new tabs). Sofa blocks windows a
  page opens without a press, windows to listed ad sites, windows a script opens to another site when you didn't
  press a link to it, and the invisible links pop-under networks lay over a page. Pages that send you to a listed ad
  site on their own are stopped too. The menu's "Allow once" opens the last thing blocked. This also stops buttons
  (not links) that open another site's window on purpose, such as some "Sign in with…", payment or "Watch on
  YouTube" buttons: press the button, then "Allow once".
- **Certificate warnings:** only the page you are on, or the one it is taking you to, can ask whether to go on
  despite a broken security certificate. Anything else on the page with one, often an ad in a frame, is refused
  without a warning, as Chrome does.
- **Updates:** lists are downloaded when you add or update them, without cookies. Sofa can also update them every
  few days by itself; it asks at first start, and EasyList and EasyPrivacy, which change every few days, are best
  kept that way.
- **Per site:** the Ad block button in the menu switches all of this off for one site and shows how much was
  blocked on the page. In private browsing that switch lasts only until private browsing ends and is not kept.

### Site filters

Settings → Site filters blocks whole sites, not just their ads, whichever way you reach them: a typed address, a
bookmark, a link or a redirect. Anything a page loads from such a site is blocked too, and the filters keep working
when ad blocking is off. Each category is off until you turn it on, and its list is downloaded then:

- **Adult sites:** HaGeZi's NSFW list (about 92,000 domains).
- **Gambling**, in three sizes, one at a time: popular sites (about 110,000 domains), more sites (about 180,000, the
  recommended one) or all known sites (about 630,000; about 5 MB of memory, and slow to update on cheap TVs).
- **Unsafe sites:** FMHY's Basic list of fake download sites and malware.
- **Drugs:** the Block List Project's list. It is updated rarely and also blocks some sites about medicines.
- **Your lists:** any list of domains or hosts file, by its address.

A blocked site shows which list blocked it, with **Go back** (to the last page that isn't blocked) and **Allow this
site**; Settings → Site filters → Allowed sites takes an allowed site back. In private browsing an allowed site stays
allowed only until private browsing ends, and it is not kept or listed. The lists are written by their authors and
miss sites: a filter, not a guarantee.

### Parental PIN

Settings → Parental PIN (off until you set one) asks for a 4-digit PIN before anyone opens Settings, turns ad
blocking off (for every site or for one), lets a site through Site filters, turns private browsing on, deletes
History, imports bookmarks or sets a picture from a page as the start page background. Turning things back on never
asks. In Settings and History the PIN counts until you go back to browsing or Sofa leaves the screen; anywhere else it
counts for one action.

The PIN is entered with the remote's arrows, one digit at a time, or with its number keys. Sofa keeps only a salted
hash of it, on the TV. After five wrong tries it waits 30 seconds before the next, then twice as long after each
wrong try, up to 30 minutes, also across restarts. Forgot the PIN? Clearing Sofa's data in the TV's settings removes
it, along with your bookmarks, lists and settings; there is no other way back.

The PIN guards Sofa's own settings and blockers, not the TV: it can't stop anyone from using another app, changing
the TV's settings or removing Sofa.

## Video

- **Sofa Player:** once a video plays fullscreen, Sofa looks for the stream behind it: a video file, or an HLS or
  DASH stream the page loaded. If it can play it, Sofa Player (AndroidX Media3) gets ready out of sight and takes
  over at the same position. It draws on a video layer of its own, so a 4K stream reaches a 4K panel at full
  resolution, in HDR when the stream and the TV have it. "Quality, sound and subtitles" on the bar lists what the
  stream offers, and Video info shows the decoder and the frames dropped. When the stream has no subtitles of its
  own, the page's subtitle files are offered instead, and the one the site was showing stays on. If Sofa Player only
  gets a software decoder while the TV has a hardware one for the video (the site's player may still hold it), it
  pauses the site's video and tries once more before taking over. If it gets a software decoder again, it keeps to
  pictures up to 1080p at 30 fps, which the TV's processor keeps up with, or leaves the video to the site's player.
  Its requests are the ones the page's player would make, with the same headers and cookies, and it keeps no cache.
  Protected (DRM) video, videos under a minute and streams a page builds itself (YouTube's, for one) stay with the
  site's player.
- **Steps down:** when the TV can't decode one of the stream's pictures, though it lists the codec or size, Sofa
  Player goes on from the same point with another one: the same codec smaller, or another codec. A quality picked by
  hand steps to the closest one below. A note over the video and Video info say which picture didn't play and which
  one plays now.
- **4K and 60 fps:** Auto plays the stream's 4K and 1440p pictures at 60 fps when the TV's hardware decoder says it
  plays them, also on TVs that list low frame rates for it. If such a picture then drops frames over its first
  seconds, Auto takes a smaller one and says so over the video and in Video info. A quality picked by hand is left
  alone.
- **Your choices stay:** the quality, sound and subtitles you pick on the bar stay for the next videos, on every
  site: the same quality or the nearest lower one, and the same language for sound and subtitles. Private browsing
  uses them but saves nothing new. Settings → Sofa Player → "Forget my player choices" goes back to Auto and the
  TV's own settings.
- **Starts sharp:** with an HLS stream, Sofa Player starts at the quality the site's player was showing, then
  adapts to the connection as usual.
- **Straight from the page:** a video you start on the page yourself (with the pointer, on the site's play button)
  goes fullscreen at once, so Sofa Player takes over without Watch. Only videos you start, with sound, a minute or
  longer and about the size of the page's player: muted previews, videos that start on their own and short ads stay
  on the page. Settings → "Open videos in Sofa Player" turns it off.
- **Watch by itself:** Settings → "Watch by itself" (off by default) runs Watch on its own once a page has loaded,
  on every site or only on the sites chosen with "Watch by itself" in the menu. It runs once per page, looks again
  for about ten seconds while the site builds its player, says nothing when it finds no video, and stops as soon as
  you press a key. On every site, muted previews, looping videos and videos under a minute are left alone. Private
  browsing never adds a site.
- **Says why:** when Watch leaves a video with the site's player (protected, no stream Sofa Player can open, or one
  it can't read or play), a short note in fullscreen says why, and Video info keeps the reason.
- **Back to the site's player:** the bar's "Site's player" button switches back at the same position, and so does
  Sofa if its player can't go on. Settings → Sofa Player turns it off.
- **Another app instead:** Settings → "Always play in another app" (experimental, off by default) sends fullscreen
  videos to the "Play in…" app instead of Sofa Player. Protected videos and videos under a minute stay in Sofa.
  How well a video plays there depends on the app and the site, which is why Sofa never does this unless asked.
- **Decoding:** with the site's player, the TV's web engine (Android System WebView) decodes the video, with the
  TV's video hardware where the format allows; Video info shows what the TV reports. Sofa's fullscreen controls are
  drawn on top of the picture.
- **4K with the site's player:** in fullscreen, sites see the TV's real resolution, so on a 4K TV they can send
  their 4K stream. The web engine then shows it through Sofa's HD window, as it does for every app, so the panel
  gets an upscaled HD picture; it is still sharper than the site's HD stream.
- **Frame rate:** with Settings → Match frame rate (experimental, off by default), the TV switches to the video's
  frame rate in fullscreen, such as 24 or 50 per second, and back afterwards. Sofa measures the rate from the frames
  the page shows (Sofa Player reads it from the stream), and only switches between refresh rates the TV offers at
  its current resolution. The screen goes dark for a moment while the TV switches, so videos under 5 minutes are left
  alone. The setting only appears on TVs that offer more than one refresh rate, and Android 12's "Match content
  frame rate: Never" turns it off.
- **Resolution:** Sofa never changes the TV's resolution. Web video always reaches the screen through Sofa's
  window, so switching the output to the video's resolution would only move the scaling elsewhere.
- **Protected (DRM) video** plays in standard definition on most TVs: the web engine only offers software
  protection, and streaming services keep HD for hardware protection. Sofa asks each site first, and Settings →
  Protected content can allow or block it everywhere.
- **The screen stays on** while a video plays, and long films offer to continue where you stopped.
