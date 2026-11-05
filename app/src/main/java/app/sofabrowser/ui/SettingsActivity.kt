package app.sofabrowser.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewFeature
import app.sofabrowser.BuildConfig
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.adblock.AdBlocker
import app.sofabrowser.adblock.FilterList
import app.sofabrowser.adblock.SiteAllowlist
import app.sofabrowser.browser.ExternalPlayer
import app.sofabrowser.browser.ProtectedMedia
import app.sofabrowser.browser.RefreshRateMatch
import app.sofabrowser.browser.StreamCopyProvider
import app.sofabrowser.browser.WatchTarget
import app.sofabrowser.browser.WebViews
import app.sofabrowser.data.Db
import app.sofabrowser.dns.DnsProviders
import app.sofabrowser.dns.DohEndpoint
import app.sofabrowser.dns.SecureDns
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.HistoryRetention
import app.sofabrowser.player.PlayerChoices
import app.sofabrowser.update.Updater

/** One centred list of settings, styled like BrowsHere's: rows turn white with black text when focused. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    /** The page's scrolling list, hidden while the PIN is asked. */
    private lateinit var content: View
    private var unlocking = false
    private val onListsPage get() = intent.getStringExtra(PAGE) == PAGE_LISTS
    private val onSitesPage get() = intent.getStringExtra(PAGE) == PAGE_SITES
    private val onDnsPage get() = intent.getStringExtra(PAGE) == PAGE_DNS
    private val onDnsPickPage get() = intent.getStringExtra(PAGE) == PAGE_DNS_PICK
    private val onMenuPage get() = intent.getStringExtra(PAGE) == PAGE_MENU
    private val onHomePage get() = intent.getStringExtra(PAGE) == PAGE_HOME
    private val onBackgroundPage get() = intent.getStringExtra(PAGE) == PAGE_BACKGROUND
    /** The Filter lists row on the main page, whose rule count is refreshed on return. */
    private var listsRow: View? = null
    /** The Site filters row on the main page, whose value is refreshed on return. */
    private var sitesRow: View? = null
    /** The DNS row on the main page, whose value is refreshed on return. */
    private var dnsRow: View? = null
    /** The Menu buttons row's value, refreshed on return. */
    private var menuRow: TextView? = null
    /** The Start page row's value, refreshed on return. */
    private var homeRow: TextView? = null
    /** The Start page background row's value, refreshed on return. */
    private var backgroundRow: TextView? = null
    /** Most visited's switch, which the Start page page also flips. */
    private var mostVisitedToggle: Toggle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        prefs = Prefs(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 72.px, 0, 96.px)
            clipChildren = false
        }
        content = if (onMenuPage) MenuEditor.create(this, prefs) else if (onHomePage) HomeEditor.create(this, prefs)
        else if (onBackgroundPage) BackgroundPage(this, prefs) else FocusScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(list)
        }
        setContentView(FrameLayout(this).apply { background = Ui.background(false); addView(content) })
        when { onMenuPage || onHomePage || onBackgroundPage -> Unit; onListsPage -> buildLists(); onSitesPage -> buildSites(); onDnsPage -> buildDns(); onDnsPickPage -> buildDnsPick(); else -> build() }
    }

    override fun onStart() {
        super.onStart()
        unlock()
    }

    override fun onRestart() {
        super.onRestart()
        // Back from Filter lists or Site filters: the rule count or the lists on may have changed.
        when {
            onListsPage -> buildLists()
            onSitesPage -> buildSites()
            onDnsPage -> buildDns()
            onDnsPickPage || onMenuPage || onHomePage || onBackgroundPage -> Unit
            else -> {
                (listsRow as? TextView)?.text = rulesText(blocker.rules.rules)
                (sitesRow as? TextView)?.text = sitesValue()
                (dnsRow as? TextView)?.text = dnsValue()
                menuRow?.text = menuValue()
                homeRow?.text = homeValue()
                backgroundRow?.text = backgroundValue()
                mostVisitedToggle?.on = prefs.showMostVisited
            }
        }
    }

    private fun build() {
        // Rebuilt after a choice: focus stays on the row that was changed, not the first one.
        val focused = list.indexOfChild(list.focusedChild)
        list.removeAllViews()
        list.addView(Ui.text(this, 56, Ui.p.textMid, bold = true).apply {
            text = getString(R.string.settings)
        }, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 24.px })

        section(R.string.section_appearance)
        nav(R.string.theme, resources.getStringArray(R.array.themes)[THEMES.indexOf(prefs.theme).coerceAtLeast(0)]) { pickTheme() }
        nav(R.string.text_size, getString(R.string.percent, prefs.textZoom)) { pickTextSize() }
        menuRow = valueText(menuValue())
        row(getString(R.string.menu_buttons), getString(R.string.menu_buttons_sub), menuRow) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE, PAGE_MENU))
        }
        row(getString(R.string.open_at_start), getString(R.string.open_at_start_sub), valueText(openAtStartValue())) { pickOpenAtStart() }
        homeRow = valueText(homeValue())
        row(getString(R.string.home_layout), getString(R.string.home_layout_sub), homeRow) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE, PAGE_HOME))
        }
        backgroundRow = valueText(backgroundValue())
        row(getString(R.string.background_setting_title), getString(R.string.background_setting_sub), backgroundRow) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE, PAGE_BACKGROUND))
        }
        // WebView can only darken pages for a dark app theme.
        if (Ui.p.dark && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            switch(R.string.dark_pages, R.string.dark_pages_sub, prefs::darkPages)
        }
        mostVisitedToggle = switch(R.string.most_visited_setting, R.string.most_visited_setting_sub, prefs::showMostVisited)

        section(R.string.section_browsing)
        nav(R.string.search_engine, prefs.searchEngine.name) { pickEngine() }
        switch(R.string.search_suggestions,
            if (prefs.searchEngine.suggestTemplate == null) R.string.search_suggestions_none else R.string.search_suggestions_sub,
            prefs::searchSuggestions)
        switch(R.string.voice_search, R.string.voice_search_sub, prefs::voiceSearch) { on ->
            // A switched-off feature shouldn't keep the microphone: Android drops it when Sofa next closes.
            if (!on && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                revokeSelfPermissionsOnKill(listOf(Manifest.permission.RECORD_AUDIO))
            }
        }
        nav(R.string.user_agent, resources.getStringArray(R.array.user_agents)[Prefs.UA_MODES.indexOf(prefs.userAgent).coerceAtLeast(0)]) { pickUserAgent() }
        nav(R.string.cursor_speed, resources.getStringArray(R.array.cursor_speeds)[prefs.cursorSpeed - 1]) { pickCursorSpeed() }
        row(getString(R.string.game_controller), getString(R.string.game_controller_sub), arrow()) { PadHelp.dialog(this) }
        switch(R.string.back_goes_back, R.string.back_goes_back_sub, prefs::backGoesBack)
        switch(R.string.javascript, R.string.javascript_sub, prefs::javascript)
        switch(R.string.autoplay, R.string.autoplay_sub, prefs::autoplay)
        switch(R.string.sofa_player, R.string.sofa_player_sub, prefs::sofaPlayer)
        switch(R.string.open_in_player, R.string.open_in_player_sub, prefs::openInPlayer)
        row(getString(R.string.auto_watch), getString(R.string.auto_watch_sub),
            valueText(resources.getStringArray(R.array.auto_watch_modes)[prefs.autoWatch.ordinal])) { pickAutoWatch() }
        prefs.autoWatchSites.size.takeIf { it > 0 }?.let { n ->
            row(getString(R.string.forget_auto_watch_sites), resources.getQuantityString(R.plurals.auto_watch_sites_count, n, n), null) {
                prefs.forgetAutoWatchSites()
                Toast.makeText(this, R.string.auto_watch_sites_forgotten, Toast.LENGTH_SHORT).show()
                build()
            }
        }
        row(getString(R.string.forget_player_choices), getString(R.string.forget_player_choices_sub), null) {
            prefs.playerChoices = PlayerChoices()
            Toast.makeText(this, R.string.player_choices_forgotten, Toast.LENGTH_SHORT).show()
        }
        row(getString(R.string.play_in), getString(R.string.play_in_setting_sub), valueText(playerLabel())) { pickPlayer() }
        switch(R.string.force_external_player, R.string.force_external_player_sub, prefs::forceExternalPlayer)
        // Only on TVs that offer another refresh rate at their resolution; many offer just one.
        if (RefreshRateMatch(this).available()) switch(R.string.match_frame_rate, R.string.match_frame_rate_sub, prefs::matchFrameRate)
        nav(R.string.downloads, null) { startActivity(android.content.Intent(this, DownloadsActivity::class.java)) }

        section(R.string.section_privacy)
        switch(R.string.adblock_setting, R.string.adblock_setting_sub, prefs::adBlock)

        strongerRow()
        switch(R.string.auto_update_lists, R.string.auto_update_lists_sub, prefs::autoUpdateLists) { on -> listUpdatesChanged(on) }
        listsRow = nav(R.string.filter_lists, rulesText(blocker.rules.rules)) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE, PAGE_LISTS))
        }
        sitesRow = nav(R.string.site_filters, sitesValue()) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE, PAGE_SITES))
        }
        row(getString(R.string.pin_setting), getString(R.string.pin_setting_sub),
            valueText(getString(if (pinOf(this).isSet) R.string.pin_value_on else R.string.filter_list_off))) { pinActions() }
        dnsRow = nav(R.string.dns, dnsValue()) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE, PAGE_DNS))
        }
        switch(R.string.cookies, R.string.cookies_sub, prefs::cookies, confirmOff = R.string.cookies_confirm)
        switch(R.string.third_party_cookies, R.string.third_party_cookies_sub, prefs::blockThirdPartyCookies)
        switch(R.string.gpc, R.string.gpc_sub, prefs::sendGpc)
        switch(R.string.safe_browsing, R.string.safe_browsing_sub, prefs::safeBrowsing)
        row(getString(R.string.drm_setting), getString(R.string.drm_setting_sub), valueText(drmLabel())) { pickDrm() }
        row(getString(R.string.keep_history), getString(R.string.keep_history_sub), valueText(historyLabel())) { pickHistory() }
        switch(R.string.clear_on_exit, R.string.clear_on_exit_sub, prefs::clearOnExit) { on -> if (on) prefs.lastPage = "" }
        nav(R.string.clear_now, null) { clearNow() }

        // Only for copies that no app store keeps up to date (never in the Google Play build).
        if (Updater.offered(this)) {
            section(R.string.section_updates)
            switch(R.string.check_updates, R.string.check_updates_sub, prefs::checkUpdates) { prefs.updatesAsked = true }
            nav(R.string.check_updates_now, null) { Updater.checkNow(this) }
        }

        section(R.string.section_about)
        nav(R.string.about, getString(R.string.version, BuildConfig.VERSION_NAME)) { about() }

        (list.getChildAt(focused)?.takeIf { it.isFocusable } ?: list.getChildAt(2))?.requestFocus()
    }

    private fun section(title: Int) {
        list.addView(Ui.text(this, 28, Ui.p.textWeak).apply {
            text = getString(title)
            setPadding(27.px, 40.px, 0, 16.px)
        }, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun row(title: String, subtitle: String?, end: View?, onClick: () -> Unit): LinearLayout {
        val t = Ui.text(this, 34, Ui.p.text).apply { text = title }
        val s = subtitle?.let { Ui.text(this, 24, Ui.p.textHint).apply { text = it; setPadding(0, 8.px, 0, 0) } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(Ui.p.fill, Ui.p.focus, 16)
            setPadding(40.px, 24.px, 33.px, 24.px)
            minimumHeight = 144.px
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(t); s?.let(::addView) }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            end?.let { addView(it) }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                t.setTextColor(if (has) Ui.p.onFocus else Ui.p.text)
                s?.setTextColor(if (has) Ui.p.onFocusWeak else Ui.p.textHint)
                (end as? ImageView)?.setColorFilter(if (has) Ui.p.onFocus else Ui.p.textMid)
                (end as? TextView)?.setTextColor(if (has) Ui.p.onFocusWeak else Ui.p.textMid)
                v.scaleX = if (has) 1.04f else 1f
                v.scaleY = v.scaleX
            }
        }
        list.addView(row, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 2.px })
        return row
    }


    private fun menuValue(): String {
        val l = prefs.menuLayout
        return when {
            l.hiddenCount > 0 -> resources.getQuantityString(R.plurals.menu_buttons_hidden, l.hiddenCount, l.hiddenCount)
            l.isDefault -> getString(R.string.menu_buttons_default)
            else -> getString(R.string.menu_buttons_custom)
        }
    }
    private fun homeValue(): String {
        val l = prefs.homeLayout
        return when {
            l.hiddenCount > 0 -> resources.getQuantityString(R.plurals.menu_buttons_hidden, l.hiddenCount, l.hiddenCount)
            l.isDefault -> getString(R.string.menu_buttons_default)
            else -> getString(R.string.menu_buttons_custom)
        }
    }

    private fun backgroundValue(): String = when (val c = HomeBackground.decode(prefs.homeBackground, HomePicture.exists(this))) {
        HomeBackground.Choice.Theme -> getString(R.string.menu_buttons_default)
        HomeBackground.Choice.Picture -> getString(R.string.background_your_picture)
        is HomeBackground.Choice.Look -> getString(c.swatch.label)
    }

    /** A picture from the TV's document picker (Start page background). */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != BackgroundPage.REQUEST_PICTURE || resultCode != RESULT_OK || uri == null) return
        Toast.makeText(this, R.string.background_setting, Toast.LENGTH_SHORT).show()
        HomePicture.set(this, { contentResolver.openInputStream(uri) ?: throw java.io.IOException() }) { outcome ->
            when (outcome) {
                HomePicture.Outcome.SET -> {
                    prefs.homeBackground = HomeBackground.PICTURE
                    Toast.makeText(this, R.string.background_set, Toast.LENGTH_SHORT).show()
                }
                HomePicture.Outcome.TOO_LARGE -> Toast.makeText(this, R.string.background_too_large, Toast.LENGTH_LONG).show()
                HomePicture.Outcome.UNREADABLE -> Toast.makeText(this, R.string.background_unreadable, Toast.LENGTH_LONG).show()
            }
            (content as? BackgroundPage)?.refresh()
        }
    }

    private fun arrow() = ImageView(this).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(Ui.p.textMid); layoutParams = LinearLayout.LayoutParams(40.px, 40.px) }

    private fun valueText(value: String) = Ui.text(this, 28, Ui.p.textMid).apply { text = value; setPadding(24.px, 0, 0, 0) }

    /** A row that opens something; returns its value text (or arrow), so it can be updated later. */
    private fun nav(title: Int, value: String?, onClick: () -> Unit): View {
        val end = if (value != null) valueText(value)
        else arrow()
        row(getString(title), null, end, onClick)
        return end
    }

    private fun switch(title: Int, subtitle: Int, prop: kotlin.reflect.KMutableProperty0<Boolean>, confirmOff: Int? = null,
                       onChange: ((Boolean) -> Unit)? = null): Toggle {
        val toggle = Toggle(this, prop.get())
        row(getString(title), getString(subtitle), toggle) {
            val turnOn = !prop.get()
            fun apply() { prop.set(turnOn); toggle.on = turnOn; onChange?.invoke(turnOn) }
            if (!turnOn && confirmOff != null) {
                dialog().setMessage(confirmOff)
                    .setPositiveButton(R.string.turn_off) { _, _ -> apply() }
                    .setNegativeButton(R.string.keep_on, null).show()
            } else apply()
        }
        return toggle
    }

    // Pickers and actions

    private fun pickTheme() {
        dialog().setTitle(R.string.theme)
            .setSingleChoiceItems(R.array.themes, THEMES.indexOf(prefs.theme)) { d, which ->
                d.dismiss()
                if (THEMES[which] != prefs.theme) {
                    prefs.theme = THEMES[which]
                    recreate()
                }
            }.show()
    }

    private fun pickEngine() {
        // Name, with the engine's tagline underneath in small type.
        val engines = Prefs.ENGINES.map { e ->
            val note = listOfNotNull(e.tagline.takeIf { it != 0 }?.let(::getString), if (e.paid) getString(R.string.subscription) else null)
                .joinToString(" · ")
            SpannableString(e.name + "\n" + note).apply {
                val from = e.name.length + 1
                setSpan(RelativeSizeSpan(0.7f), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Ui.p.textWeak), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } + getString(R.string.custom)
        val ids = Prefs.ENGINES.map { it.id } + "custom"
        dialog().setTitle(R.string.search_engine)
            .setSingleChoiceItems(engines.toTypedArray(), ids.indexOf(prefs.searchEngineId)) { d, which ->
                d.dismiss()
                if (ids[which] == "custom") askText(R.string.custom_search_engine, prefs.customSearchTemplate) {
                    if ("%s" in it && it.startsWith("https://")) { prefs.customSearchTemplate = it; prefs.searchEngineId = "custom" }
                    else Toast.makeText(this, R.string.custom_search_invalid, Toast.LENGTH_LONG).show()
                    build()
                } else { prefs.searchEngineId = ids[which]; build() }
            }.show()
    }

    private fun pickUserAgent() {
        dialog().setTitle(R.string.user_agent)
            .setSingleChoiceItems(R.array.user_agents, Prefs.UA_MODES.indexOf(prefs.userAgent)) { d, which ->
                d.dismiss()
                if (Prefs.UA_MODES[which] == "custom") askText(R.string.custom_user_agent, prefs.customUserAgent) {
                    prefs.customUserAgent = it; prefs.userAgent = "custom"; build()
                } else { prefs.userAgent = Prefs.UA_MODES[which]; build() }
            }.show()
    }

    private fun historyLabel() =
        resources.getStringArray(R.array.history_periods)[HistoryRetention.CHOICES.indexOf(prefs.historyDays)
            .takeIf { it >= 0 } ?: HistoryRetention.CHOICES.indexOf(HistoryRetention.DEFAULT)]

    /** A shorter period, or Never, removes the older pages right away, so the choice shows at once in History. */
    private fun pickHistory() {
        dialog().setTitle(R.string.keep_history)
            .setSingleChoiceItems(R.array.history_periods, HistoryRetention.CHOICES.indexOf(prefs.historyDays)) { d, which ->
                d.dismiss()
                prefs.historyDays = HistoryRetention.CHOICES[which]
                Db.get(this).pruneHistory(prefs.historyDays)
                build()
            }.show()
    }

    private fun drmLabel() = resources.getStringArray(R.array.drm_modes)[DRM_MODES.indexOf(prefs.drmMode).coerceAtLeast(0)]

    private fun pickDrm() {
        // Each choice with its explanation underneath in small type, as in the search engine list.
        val labels = resources.getStringArray(R.array.drm_modes)
        val notes = resources.getStringArray(R.array.drm_mode_notes)
        val items = labels.indices.map { i ->
            SpannableString(labels[i] + "\n" + notes[i]).apply {
                val from = labels[i].length + 1
                setSpan(RelativeSizeSpan(0.7f), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Ui.p.textWeak), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        dialog().setTitle(R.string.drm_setting)
            .setSingleChoiceItems(items.toTypedArray(), DRM_MODES.indexOf(prefs.drmMode)) { d, which ->
                d.dismiss()
                if (DRM_MODES[which] != prefs.drmMode) {
                    prefs.drmMode = DRM_MODES[which]
                    Toast.makeText(this, R.string.drm_saved, Toast.LENGTH_LONG).show()
                    build()
                }
            }.show()
    }

    /** The "Play in…" app's name, "Ask every time", or that Android's chooser asks the first time. */
    private fun playerLabel(): String = when (val choice = prefs.videoPlayer) {
        Prefs.ASK_EVERY_TIME -> getString(R.string.ask_every_time)
        "" -> getString(R.string.ask_first_time)
        else -> ExternalPlayer.label(this, choice) ?: getString(R.string.ask_first_time)
    }

    private fun pickPlayer() {
        val players = ExternalPlayer.players(this)
        val names = listOf(getString(R.string.ask_every_time)) + players.map { it.second }
        val chosen = players.indexOfFirst { it.first.flattenToString() == prefs.videoPlayer }
        val current = when {
            chosen >= 0 -> chosen + 1
            prefs.videoPlayer == Prefs.ASK_EVERY_TIME -> 0
            else -> -1
        }
        dialog().setTitle(R.string.play_in)
            .setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
                prefs.videoPlayer = if (which == 0) Prefs.ASK_EVERY_TIME else players[which - 1].first.flattenToString()
                d.dismiss(); build()
            }.show()
    }

    private fun pickCursorSpeed() {
        dialog().setTitle(R.string.cursor_speed)
            .setSingleChoiceItems(R.array.cursor_speeds, prefs.cursorSpeed - 1) { d, which ->
                prefs.cursorSpeed = which + 1; d.dismiss(); build()
            }.show()
    }

    private fun pickAutoWatch() {
        dialog().setTitle(R.string.auto_watch)
            .setSingleChoiceItems(R.array.auto_watch_modes, prefs.autoWatch.ordinal) { d, which ->
                prefs.autoWatch = WatchTarget.Auto.entries[which]; d.dismiss(); build()
            }.show()
    }

    private fun openAtStartValue(): String = when (prefs.openAtStart) {
        OpenAtStart.Choice.START_PAGE -> getString(R.string.open_at_start_home)
        OpenAtStart.Choice.WEB_PAGE -> OpenAtStart.label(prefs.startPage)
        OpenAtStart.Choice.LAST_PAGE -> getString(R.string.open_at_start_last)
    }

    /** Each choice with its explanation underneath in small type, as in the DRM list. */
    private fun pickOpenAtStart() {
        val labels = listOf(R.string.open_at_start_home, R.string.open_at_start_page, R.string.open_at_start_last).map(::getString)
        val notes = listOf(
            getString(R.string.open_at_start_home_note),
            prefs.startPage.takeIf { it.isNotEmpty() }?.let(OpenAtStart::label) ?: getString(R.string.open_at_start_page_note),
            getString(if (prefs.clearOnExit) R.string.open_at_start_last_clears else R.string.open_at_start_last_note))
        val items = labels.indices.map { i ->
            SpannableString(labels[i] + "\n" + notes[i]).apply {
                val from = labels[i].length + 1
                setSpan(RelativeSizeSpan(0.7f), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Ui.p.textWeak), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        dialog().setTitle(R.string.open_at_start)
            .setSingleChoiceItems(items.toTypedArray(), prefs.openAtStart.ordinal) { d, which ->
                d.dismiss()
                when (val choice = OpenAtStart.Choice.entries[which]) {
                    OpenAtStart.Choice.WEB_PAGE -> enterStartPage(prefs.startPage.ifEmpty { "https://" })
                    else -> { chooseOpenAtStart(choice); build() }
                }
            }.show()
    }

    /**
     * Asks for the page to open at start, the chosen one (or "https://") already typed with the cursor after it, as
     * for a DNS address. The page open in Sofa is one press away when it isn't the one already chosen.
     */
    private fun enterStartPage(current: String) {
        val field = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val d = dialog().setTitle(R.string.open_at_start_page).setMessage(R.string.open_at_start_page_message).setView(padded(field))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = OpenAtStart.webAddress(field.text.toString())
                if (url == null) {
                    Toast.makeText(this, R.string.open_at_start_invalid, Toast.LENGTH_LONG).show()
                    // Back to the field with what was typed, rather than typing it all again with the remote.
                    enterStartPage(field.text.toString())
                } else saveStartPage(url)
            }
            .setNegativeButton(android.R.string.cancel, null)
        openPage?.takeIf { it != prefs.startPage }?.let { page ->
            d.setNeutralButton(R.string.open_at_start_use_open) { _, _ -> OpenAtStart.webAddress(page)?.let(::saveStartPage) }
        }
        d.show()
    }

    private fun saveStartPage(url: String) {
        prefs.startPage = url
        chooseOpenAtStart(OpenAtStart.Choice.WEB_PAGE)
        build()
    }

    /** Only "Where I left off" keeps a last page, so any other choice forgets it at once. */
    private fun chooseOpenAtStart(choice: OpenAtStart.Choice) {
        prefs.openAtStart = choice
        if (choice != OpenAtStart.Choice.LAST_PAGE) prefs.lastPage = ""
    }

    /** The page open in Sofa when Settings opened (MainActivity leaves it out in private browsing). */
    private val openPage: String? get() = intent.getStringExtra(EXTRA_OPEN_PAGE)

    private fun pickTextSize() {
        val sizes = intArrayOf(80, 90, 100, 115, 130, 150, 175, 200)
        dialog().setTitle(R.string.text_size)
            .setSingleChoiceItems(sizes.map { "$it%" }.toTypedArray(), sizes.indexOf(prefs.textZoom)) { d, which ->
                prefs.textZoom = sizes[which]; d.dismiss(); build()
            }.show()
    }

    // Ad blocking: "Stronger ad blocking", automatic list updates, and Filter lists, a page of its own (PAGE_LISTS)

    /** EasyList and EasyPrivacy in one switch, for TVs with room for them; they are downloaded when switched on. */
    private fun strongerRow() {
        val toggle = Toggle(this, blocker.stronger)
        row(getString(R.string.stronger_adblock), getString(R.string.stronger_adblock_sub), toggle) {
            val on = !toggle.on
            toggle.on = on
            // Chosen here either way: setup never offers it again.
            prefs.strongerAsked = true
            if (on) Toast.makeText(this, R.string.stronger_adblock_downloading, Toast.LENGTH_LONG).show()
            blocker.setStronger(on) {
                if (isFinishing) return@setStronger
                (listsRow as? TextView)?.text = rulesText(blocker.rules.rules)
                blocker.lists.firstOrNull { it.stronger && it.error != null }?.let {
                    Toast.makeText(this, getString(R.string.blocklist_failed, it.error), Toast.LENGTH_LONG).show()
                }
            }
            if (on && !prefs.autoUpdateLists) suggestListUpdates()
        }
    }

    /** These lists change every few days, so turning them on suggests keeping them up to date. */
    private fun suggestListUpdates() {
        dialog().setTitle(R.string.auto_update_suggest_title)
            .setMessage(R.string.auto_update_suggest)
            .setPositiveButton(R.string.turn_on) { _, _ -> prefs.autoUpdateLists = true; listUpdatesChanged(true); build() }
            .setNegativeButton(R.string.list_updates_no, null).show()
    }

    private fun listUpdatesChanged(on: Boolean) {
        prefs.listUpdatesAsked = true
        blocker.refreshIfDue(on)
    }

    private val blocker get() = AdBlocker.get(this)

    private fun rulesText(n: Int) = resources.getQuantityString(R.plurals.rules_count, n, n)

    private fun buildLists() {
        list.removeAllViews()
        list.addView(Ui.text(this, 56, Ui.p.textMid, bold = true).apply {
            text = getString(R.string.filter_lists)
        }, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.px })
        list.addView(Ui.text(this, 26, Ui.p.textWeak).apply {
            text = getString(R.string.filter_lists_intro) + " " + rulesText(blocker.rules.rules) + "."
        }, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 32.px })

        val first = blocker.lists.filter { !it.site }.map { l -> row(l.name, listStatus(l), Toggle(this, l.enabled)) { listActions(l) } }.firstOrNull()
        switch(R.string.auto_update_lists, R.string.auto_update_lists_sub, prefs::autoUpdateLists) { on ->
            listUpdatesChanged(on)
            buildLists()
        }
        row(getString(R.string.add_filter_list), getString(R.string.add_filter_list_sub), arrow()) { enterListAddress(site = false) }
        row(getString(R.string.update_all_lists), getString(R.string.update_all_lists_sub), arrow()) {
            Toast.makeText(this, R.string.lists_updating, Toast.LENGTH_SHORT).show()
            blocker.updateAll { failed ->
                Toast.makeText(this, if (failed == 0) getString(R.string.lists_updated)
                    else resources.getQuantityString(R.plurals.lists_update_failed, failed, failed), Toast.LENGTH_LONG).show()
                if (!isFinishing) buildLists()
            }
        }
        first?.requestFocus()
    }

    private fun listStatus(l: FilterList): String {
        val state = when {
            l.error != null -> getString(R.string.filter_list_failed, l.error)
            l.updated > System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS -> getString(R.string.filter_list_updated_now)
            l.updated > 0 -> getString(R.string.filter_list_updated, DateUtils.getRelativeTimeSpanString(l.updated))
            l.bundled -> getString(R.string.filter_list_builtin)
            l.rules == 0 -> getString(R.string.filter_list_not_downloaded)
            else -> null
        }
        val count = if (l.enabled) rulesText(l.rules) else getString(R.string.filter_list_off)
        val line = if (state == null) count else getString(R.string.filter_list_sub, count, state)
        // EasyList and EasyPrivacy go stale within days.
        return if (l.stronger && !prefs.autoUpdateLists) getString(R.string.filter_list_sub, line, getString(R.string.best_with_auto_updates)) else line
    }

    /** What can be done with one list: switch it, download it again, go back to the built-in copy, remove it. */
    private fun listActions(l: FilterList) {
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        actions += (if (l.enabled) R.string.turn_off else R.string.turn_on) to {
            if (l.stronger) prefs.strongerAsked = true
            blocker.setEnabled(l.id, !l.enabled) { if (!isFinishing) rebuild() }
        }
        if (l.url != null) actions += R.string.update to {
            Toast.makeText(this, R.string.list_adding, Toast.LENGTH_SHORT).show()
            blocker.update(l.id) { result ->
                result.onSuccess { Toast.makeText(this, resources.getQuantityString(R.plurals.list_updated, it.rules, it.name, it.rules), Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, getString(R.string.blocklist_failed, it.message), Toast.LENGTH_LONG).show() }
                if (!isFinishing) rebuild()
            }
        }
        if (l.bundled && l.updated > 0) actions += R.string.reset_bundled to { blocker.resetToBundled(l.id) { if (!isFinishing) rebuild() } }
        if (!l.bundled) actions += R.string.remove to { blocker.remove(l.id) { if (!isFinishing) rebuild() } }
        dialog().setTitle(l.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Asks for a list's address, "https://" already typed; [site] adds it to Site filters rather than ad blocking. */
    private fun enterListAddress(site: Boolean) {
        val field = EditText(this).apply {
            setText("https://")
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        dialog().setTitle(R.string.add_filter_list)
            .setMessage(if (site) R.string.site_list_address_message else R.string.filter_list_address_message)
            .setView(padded(field))
            .setPositiveButton(R.string.add) { _, _ -> addListAt(field.text.toString().trim(), site) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun addListAt(typed: String, site: Boolean) {
        // "lists.example/sites.txt" typed over the prefilled "https://", or without it, means https.
        val url = typed.removePrefix("https://https://").let { if ("://" in it) it else "https://$it" }
        // Debug builds also take http://, so tests can serve a list from the development machine.
        if (!url.startsWith("https://") && !(BuildConfig.DEBUG && url.startsWith("http://"))) { Toast.makeText(this, R.string.https_only, Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, R.string.list_adding, Toast.LENGTH_SHORT).show()
        blocker.add(url, site) { result ->
            result.onSuccess {
                Toast.makeText(this, resources.getQuantityString(if (site) R.plurals.site_list_added else R.plurals.list_added, it.rules, it.name, it.rules),
                    Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this, getString(R.string.list_add_failed, it.message), Toast.LENGTH_LONG).show() }
            if (!isFinishing) rebuild()
        }
    }

    private fun rebuild() = if (onSitesPage) buildSites() else buildLists()

    // Site filters: whole sites blocked by category, a page of its own (PAGE_SITES)

    /** Site filters lists being switched on or changed, by id, with the address they change to (null: the same). */
    private val pending = HashMap<String, String?>()

    /** The Site filters row's value on the main page: Off, the lists on, or how many. */
    private fun sitesValue(): String {
        val on = blocker.lists.filter { it.site && it.enabled }
        return when {
            on.isEmpty() -> getString(R.string.filter_list_off)
            on.size <= 2 -> on.joinToString(", ") { siteListName(this, it) }
            else -> getString(R.string.site_filters_on, on.size)
        }
    }

    private fun buildSites() {
        val focused = list.indexOfChild(list.focusedChild)
        list.removeAllViews()
        list.addView(Ui.text(this, 56, Ui.p.textMid, bold = true).apply {
            text = getString(R.string.site_filters)
        }, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.px })
        list.addView(Ui.text(this, 26, Ui.p.textWeak).apply {
            text = getString(R.string.site_filters_intro)
        }, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 32.px })

        val sites = blocker.lists.filter { it.site }
        fun category(id: String) = sites.firstOrNull { it.id == id }
        // Drugs last: its list over-blocks, so it comes after the others, with its warning.
        val first = category(FilterList.ADULT)?.let { siteSwitch(it) }
        category(FilterList.GAMBLING)?.let { gamblingRow(it) }
        category(FilterList.UNSAFE)?.let { siteSwitch(it, getString(R.string.site_unsafe_what)) }
        category(FilterList.DRUGS)?.let { siteSwitch(it, getString(R.string.site_drugs_warning), confirm = R.string.site_drugs_confirm) }

        section(R.string.your_lists)
        sites.filter { !it.category }.forEach { l -> row(l.name, siteStatus(l), Toggle(this, l.enabled)) { listActions(l) } }
        row(getString(R.string.add_filter_list), getString(R.string.add_site_list_sub), arrow()) { enterListAddress(site = true) }
        val allowed = SiteAllowlist.all(this)
        if (allowed.isNotEmpty()) {
            row(getString(R.string.allowed_sites), resources.getQuantityString(R.plurals.allowed_sites_count, allowed.size, allowed.size), arrow()) {
                pickAllowed(allowed)
            }
        }
        (list.getChildAt(focused)?.takeIf { it.isFocusable } ?: first)?.requestFocus()
    }

    /** The line under a Site filters list: where it comes from, [note], and its state (downloading, sites, updated, failed). */
    private fun siteStatus(l: FilterList, note: String? = null): String {
        val state = when {
            l.id in pending -> getString(R.string.site_list_downloading)
            !l.enabled -> l.error?.let { getString(if (l.updated == 0L) R.string.site_list_download_failed else R.string.filter_list_failed, it) }
            else -> listOfNotNull(
                resources.getQuantityString(R.plurals.sites_count, l.rules, l.rules),
                l.error?.let { getString(R.string.filter_list_failed, it) } ?: when {
                    l.updated > System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS -> getString(R.string.filter_list_updated_now)
                    l.updated > 0 -> getString(R.string.filter_list_updated, DateUtils.getRelativeTimeSpanString(l.updated))
                    else -> null
                },
            ).reduce { a, b -> getString(R.string.filter_list_sub, a, b) }
        }
        return listOfNotNull(if (l.category) l.name else null, note, state ?: if (l.category) null else getString(R.string.filter_list_off))
            .reduce { a, b -> getString(R.string.filter_list_sub, a, b) }
    }

    /** A category with a switch: one press turns it on or off; with [confirm], turning it on asks first. */
    private fun siteSwitch(l: FilterList, note: String? = null, confirm: Int? = null): View =
        row(siteListName(this, l), siteStatus(l, note), Toggle(this, l.enabled || l.id in pending)) {
            if (l.id in pending) return@row
            if (!l.enabled && confirm != null) {
                dialog().setTitle(siteListName(this, l)).setMessage(confirm)
                    .setPositiveButton(R.string.turn_on) { _, _ -> setSite(l, true) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } else setSite(l, !l.enabled)
        }

    private fun setSite(l: FilterList, on: Boolean, url: String? = null) {
        if (on) pending[l.id] = url
        blocker.setSite(l.id, on, url) { failure ->
            pending -= l.id
            failure?.let { Toast.makeText(this, getString(R.string.blocklist_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show() }
            if (!isFinishing) buildSites()
        }
        buildSites()
    }

    /** Gambling comes in three sizes; its row shows the one in use and OK picks another. */
    private fun gamblingRow(l: FilterList) {
        val url = if (l.id in pending) pending[l.id] ?: l.url else if (l.enabled) l.url else null
        val value = when (FilterList.GAMBLING_URLS.indexOf(url)) {
            0 -> R.string.gambling_mini
            1 -> R.string.gambling_medium
            2 -> R.string.gambling_full
            else -> R.string.filter_list_off
        }
        row(siteListName(this, l), siteStatus(l), valueText(getString(value))) { if (l.id !in pending) pickGambling(l) }
    }

    private fun pickGambling(l: FilterList) {
        val items = listOf(
            getString(R.string.filter_list_off) to null,
            getString(R.string.gambling_mini) to getString(R.string.gambling_mini_note),
            getString(R.string.gambling_medium_choice) to getString(R.string.gambling_medium_note),
            getString(R.string.gambling_full) to getString(R.string.gambling_full_note),
        ).map { (label, note) -> withNote(label, note) }
        val current = if (l.enabled) FilterList.GAMBLING_URLS.indexOf(l.url) + 1 else 0
        dialog().setTitle(R.string.site_gambling)
            .setSingleChoiceItems(items.toTypedArray(), current) { d, which ->
                d.dismiss()
                if (which == current) return@setSingleChoiceItems
                if (which == 0) setSite(l, false) else setSite(l, true, FilterList.GAMBLING_URLS[which - 1])
            }.show()
    }

    /** A choice with its explanation underneath in small type, as in the search engine list. */
    private fun withNote(label: String, note: String?): CharSequence {
        if (note == null) return label
        return SpannableString(label + "\n" + note).apply {
            val from = label.length + 1
            setSpan(RelativeSizeSpan(0.7f), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Ui.p.textWeak), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Sites let through from the block screen; choosing one blocks it again. */
    private fun pickAllowed(sites: List<String>) {
        dialog().setTitle(R.string.allowed_sites_pick)
            .setItems(sites.toTypedArray()) { _, which ->
                SiteAllowlist.remove(this, sites[which])
                Toast.makeText(this, getString(R.string.site_blocked_again, sites[which]), Toast.LENGTH_SHORT).show()
                buildSites()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // Custom DNS: main and fallback on a page of their own (PAGE_DNS), each chosen on another (PAGE_DNS_PICK)

    /** A page's big title and the explanation under it. */
    private fun pageTitle(title: Int, intro: Int) {
        list.addView(Ui.text(this, 56, Ui.p.textMid, bold = true).apply {
            text = getString(title)
        }, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.px })
        note(getString(intro))
    }

    /** A line of small text between rows. */
    private fun note(text: String, color: Int = Ui.p.textWeak) {
        list.addView(Ui.text(this, 26, color).apply {
            this.text = text
            setPadding(27.px, 0, 27.px, 0)
        }, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16.px; bottomMargin = 16.px })
    }

    /** A choice's short name: the provider, or the custom address's host; null for the device DNS. */
    private fun dnsName(choice: String): String? = when {
        choice.isEmpty() -> null
        DnsProviders.isCustom(choice) -> android.net.Uri.parse(choice).host ?: choice
        else -> DnsProviders.byId(choice)?.name
    }

    /** What a choice does and its privacy tier, for the line under it. */
    private fun dnsDetail(choice: String): String? = when {
        choice.isEmpty() -> null
        DnsProviders.isCustom(choice) -> getString(R.string.dns_custom)
        else -> DnsProviders.byId(choice)?.let { getString(R.string.filter_list_sub, getString(it.variant), getString(it.tier.label)) }
    }

    private fun dnsValue() = dnsName(prefs.dnsMain) ?: getString(R.string.dns_device)

    private fun buildDns() {
        val focused = list.indexOfChild(list.focusedChild)
        list.removeAllViews()
        pageTitle(R.string.dns, R.string.dns_intro)
        val main = prefs.dnsMain
        val first = row(getString(R.string.dns_main), dnsDetail(main) ?: getString(R.string.dns_device_sub), valueText(dnsValue())) { pickDns(fallback = false) }
        if (main.isNotEmpty()) {
            val fallback = prefs.dnsFallback
            row(getString(R.string.dns_fallback), dnsDetail(fallback) ?: getString(R.string.dns_fallback_sub),
                valueText(dnsName(fallback) ?: getString(R.string.dns_none))) { pickDns(fallback = true) }
            row(getString(R.string.dns_test), getString(R.string.dns_test_sub), null) { testDns() }
            note(getString(R.string.dns_last_step))
            when (SecureDns.unused) {
                SecureDns.Unused.NETWORK_PROXY -> note(getString(R.string.dns_network_proxy), Ui.p.text)
                SecureDns.Unused.NO_WEBVIEW_SUPPORT -> note(getString(R.string.dns_no_webview), Ui.p.text)
                null -> Unit
            }
        }
        (list.getChildAt(focused)?.takeIf { it.isFocusable } ?: first).requestFocus()
    }

    private fun pickDns(fallback: Boolean) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(PAGE, PAGE_DNS_PICK).putExtra(SLOT, if (fallback) FALLBACK else ""))
    }

    /** Every choice for one slot: the device DNS (or none), the four tiers in rank order, then a custom address. */
    private fun buildDnsPick() {
        val fallback = intent.getStringExtra(SLOT) == FALLBACK
        val current = if (fallback) prefs.dnsFallback else prefs.dnsMain
        list.removeAllViews()
        pageTitle(if (fallback) R.string.dns_fallback else R.string.dns_main, R.string.dns_pick_intro)
        var selected: View? = null
        fun choice(title: String, sub: String, id: String, isCurrent: Boolean = id == current, onClick: () -> Unit = { chooseDns(fallback, id) }) =
            row(title, sub, if (isCurrent) check() else null, onClick).also { if (isCurrent) selected = it }
        val first = if (fallback) choice(getString(R.string.dns_none), getString(R.string.dns_none_sub), "")
        else choice(getString(R.string.dns_device), getString(R.string.dns_device_sub), "")
        DnsProviders.Tier.entries.forEach { tier ->
            list.addView(Ui.text(this, 28, tier.color, bold = true).apply {
                text = getString(R.string.dns_tier_heading, getString(tier.label))
                setPadding(27.px, 40.px, 0, 16.px)
            }, LinearLayout.LayoutParams(ROW_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT))
            DnsProviders.ALL.filter { it.tier == tier }.forEach { p ->
                choice(p.name, getString(R.string.filter_list_sub, getString(p.variant), getString(p.about)), p.id)
            }
        }
        section(R.string.dns_custom)
        val custom = DnsProviders.isCustom(current)
        choice(getString(R.string.dns_custom), if (custom) current else getString(R.string.dns_custom_sub), current, isCurrent = custom) {
            enterDnsAddress(fallback, if (custom) current else "https://")
        }
        (selected ?: first).requestFocus()
    }

    private fun check() = ImageView(this).apply { setImageResource(R.drawable.ic_check); setColorFilter(Ui.p.textMid); layoutParams = LinearLayout.LayoutParams(40.px, 40.px) }

    private fun enterDnsAddress(fallback: Boolean, current: String) {
        val field = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        dialog().setTitle(R.string.dns_custom).setMessage(R.string.dns_custom_message).setView(padded(field))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = field.text.toString().trim().removePrefix("https://https://").let { if ("://" in it) it else "https://$it" }
                if (runCatching { DohEndpoint(typed) }.isSuccess) chooseDns(fallback, typed)
                else Toast.makeText(this, R.string.dns_custom_invalid, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Saves the choice and applies it at once; the device DNS as main also clears the fallback. */
    private fun chooseDns(fallback: Boolean, choice: String) {
        if (fallback) prefs.dnsFallback = choice
        else {
            prefs.dnsMain = choice
            if (choice.isEmpty()) prefs.dnsFallback = ""
        }
        SecureDns.apply(applicationContext)
        finish()
    }

    /** Looks one name up with the main and the fallback DNS, each on its own, and shows how each did. */
    private fun testDns() {
        val choices = listOf(prefs.dnsMain, prefs.dnsFallback).filter { it.isNotEmpty() }.distinct()
        val lines = arrayOfNulls<String>(choices.size)
        var left = choices.size
        val shown = dialog().setTitle(R.string.dns_test).setMessage(R.string.dns_testing).setPositiveButton(android.R.string.ok, null).show()
        choices.forEachIndexed { i, c ->
            val label = DnsProviders.byId(c)?.let { getString(R.string.filter_list_sub, it.name, getString(it.variant)) } ?: dnsName(c)
            SecureDns.test(c) { result ->
                lines[i] = getString(R.string.dns_test_line, label, result.fold(
                    { getString(R.string.dns_test_ms, it) },
                    { getString(R.string.dns_test_failed, it.message ?: it.javaClass.simpleName) }))
                if (--left == 0 && shown.isShowing) shown.setMessage(lines.joinToString("\n"))
            }
        }
    }

    // Parental PIN

    /** Off: explains what the PIN guards, then asks for one. On: change it or turn it off (Settings is already unlocked). */
    private fun pinActions() {
        val pin = pinOf(this)
        if (!pin.isSet) {
            dialog().setTitle(R.string.pin_setting).setMessage(R.string.pin_intro)
                .setPositiveButton(R.string.pin_choose) { _, _ ->
                    PinPad.choose(this) { ok ->
                        if (!ok) return@choose
                        PinGate.open()
                        Toast.makeText(this, R.string.pin_turned_on, Toast.LENGTH_SHORT).show()
                        build()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        dialog().setTitle(R.string.pin_setting)
            .setItems(arrayOf(getString(R.string.pin_change), getString(R.string.pin_turn_off))) { _, which ->
                if (which == 0) PinPad.choose(this) { ok -> if (ok) Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show() }
                else {
                    pin.remove()
                    Toast.makeText(this, R.string.pin_turned_off, Toast.LENGTH_SHORT).show()
                    build()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Asks for the PIN before showing any Settings page, unless it was entered for this visit; not entered goes back to the browser. */
    private fun unlock() {
        if (unlocking || !pinOf(this).isSet || PinGate.isOpen) return
        unlocking = true
        // Hidden, not removed, so the row in focus keeps its focus for when the page shows.
        content.alpha = 0f
        PinPad.ask(this, R.string.pin_title_settings) { ok ->
            unlocking = false
            if (ok) {
                PinGate.open()
                content.animate().alpha(1f).setDuration(180).start()
            } else {
                // MainActivity is singleTask: starting it closes every Settings page above it.
                startActivity(Intent(this, app.sofabrowser.MainActivity::class.java))
                finish()
            }
        }
    }

    private fun clearNow() {
        dialog().setMessage(R.string.clear_now_confirm)
            .setPositiveButton(R.string.clear) { _, _ ->
                WebViews.clearBrowsingData(this, null)
                Db.get(this).clearHistory()
                Favicons.get(this).clear()
                prefs.clearDrmAnswers()
                app.sofabrowser.resumeStore(this).clear()
                StreamCopyProvider.clear(this)
                prefs.lastPage = ""
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun about() {
        dialog().setTitle(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME)
            .setMessage(getString(R.string.about_text) +
                (if (Updater.offered(this)) " " + getString(R.string.about_updates) else "") +
                "\n\n" + getString(R.string.about_engine, WebViews.engine(this).label) + "\n" + getString(R.string.about_video) +
                "\n\n" + getString(R.string.about_license))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.license) { _, _ -> showAsset(R.string.license, "LICENSE") }
            .setNegativeButton(R.string.third_party) { _, _ -> showAsset(R.string.third_party, "NOTICE.md") }
            .show()
    }

    private fun showAsset(title: Int, name: String) {
        val text = runCatching { assets.open(name).bufferedReader().readText() }.getOrDefault("")
        dialog().setTitle(title).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun askText(title: Int, current: String, done: (String) -> Unit) {
        val field = EditText(this).apply { setText(current); isSingleLine = true }
        dialog().setTitle(title).setView(padded(field))
            .setPositiveButton(android.R.string.ok) { _, _ -> done(field.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun padded(v: View) = FrameLayout(this).apply { setPadding(48.px, 16.px, 48.px, 0); addView(v) }

    private fun dialog() = Ui.dialog(this)

    companion object {
        private const val ROW_WIDTH = 1400
        private val THEMES = listOf("dark", "light", "system")
        private val DRM_MODES = listOf(ProtectedMedia.ASK, ProtectedMedia.ALLOW, ProtectedMedia.BLOCK)
        private const val PAGE = "page"
        /** The address of the page open in Sofa, offered as the page to open at start. */
        const val EXTRA_OPEN_PAGE = "open_page"
        private const val PAGE_LISTS = "lists"
        private const val PAGE_SITES = "sites"
        private const val PAGE_DNS = "dns"
        private const val PAGE_DNS_PICK = "dns_pick"
        private const val PAGE_MENU = "menu"
        private const val PAGE_HOME = "home"
        private const val PAGE_BACKGROUND = "background"
        /** Which DNS the pick page chooses: [FALLBACK], or the main one. */
        private const val SLOT = "slot"
        private const val FALLBACK = "fallback"

        /** What Site filters calls [list]: a category's name, or the name of a list the user added. */
        fun siteListName(context: Context, list: FilterList): String = when (list.id) {
            FilterList.ADULT -> R.string.site_adult
            FilterList.GAMBLING -> R.string.site_gambling
            FilterList.UNSAFE -> R.string.site_unsafe
            FilterList.DRUGS -> R.string.site_drugs
            else -> null
        }?.let(context::getString) ?: list.name
    }
}
