package app.sofabrowser.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
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
import app.sofabrowser.browser.ExternalPlayer
import app.sofabrowser.browser.ProtectedMedia
import app.sofabrowser.browser.RefreshRateMatch
import app.sofabrowser.browser.StreamCopyProvider
import app.sofabrowser.browser.WebViews
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.HistoryRetention
import app.sofabrowser.update.Updater

/** One centred list of settings, styled like BrowsHere's: rows turn white with black text when focused. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private val onListsPage get() = intent.getStringExtra(PAGE) == PAGE_LISTS
    /** The Filter lists row on the main page, whose rule count is refreshed on return. */
    private var listsRow: View? = null

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
        setContentView(FocusScrollView(this).apply {
            background = Ui.background(false)
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(list)
        })
        if (onListsPage) buildLists() else build()
    }

    override fun onRestart() {
        super.onRestart()
        // Back from Filter lists: the rule count may have changed.
        if (onListsPage) buildLists() else (listsRow as? TextView)?.text = rulesText(blocker.rules.rules)
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
        // WebView can only darken pages for a dark app theme.
        if (Ui.p.dark && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            switch(R.string.dark_pages, R.string.dark_pages_sub, prefs::darkPages)
        }
        switch(R.string.most_visited_setting, R.string.most_visited_setting_sub, prefs::showMostVisited)

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
        switch(R.string.cookies, R.string.cookies_sub, prefs::cookies, confirmOff = R.string.cookies_confirm)
        switch(R.string.third_party_cookies, R.string.third_party_cookies_sub, prefs::blockThirdPartyCookies)
        switch(R.string.gpc, R.string.gpc_sub, prefs::sendGpc)
        switch(R.string.safe_browsing, R.string.safe_browsing_sub, prefs::safeBrowsing)
        row(getString(R.string.drm_setting), getString(R.string.drm_setting_sub), valueText(drmLabel())) { pickDrm() }
        row(getString(R.string.keep_history), getString(R.string.keep_history_sub), valueText(historyLabel())) { pickHistory() }
        switch(R.string.clear_on_exit, R.string.clear_on_exit_sub, prefs::clearOnExit)
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
                       onChange: ((Boolean) -> Unit)? = null) {
        val toggle = Toggle(prop.get())
        row(getString(title), getString(subtitle), toggle) {
            val turnOn = !prop.get()
            fun apply() { prop.set(turnOn); toggle.on = turnOn; onChange?.invoke(turnOn) }
            if (!turnOn && confirmOff != null) {
                dialog().setMessage(confirmOff)
                    .setPositiveButton(R.string.turn_off) { _, _ -> apply() }
                    .setNegativeButton(R.string.keep_on, null).show()
            } else apply()
        }
    }

    /** 80x44 pill switch: blue when on. */
    private inner class Toggle(initial: Boolean) : View(this@SettingsActivity) {
        var on = initial
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init { layoutParams = LinearLayout.LayoutParams(80.px, 44.px).apply { marginStart = 24.px } }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            paint.color = if (on) Ui.SWITCH_ON else Ui.p.toggleOff
            c.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, paint)
            paint.color = Ui.p.knob
            val r = h / 2 - 5.px
            c.drawCircle(if (on) width - h / 2 else h / 2, h / 2, r, paint)
        }
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
        val toggle = Toggle(blocker.stronger)
        row(getString(R.string.stronger_adblock), getString(R.string.stronger_adblock_sub), toggle) {
            val on = !toggle.on
            toggle.on = on
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

        val first = blocker.lists.map { l -> row(l.name, listStatus(l), Toggle(l.enabled)) { listActions(l) } }.firstOrNull()
        switch(R.string.auto_update_lists, R.string.auto_update_lists_sub, prefs::autoUpdateLists) { on ->
            listUpdatesChanged(on)
            buildLists()
        }
        row(getString(R.string.add_filter_list), getString(R.string.add_filter_list_sub), arrow()) { enterListAddress() }
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
        actions += (if (l.enabled) R.string.turn_off else R.string.turn_on) to { blocker.setEnabled(l.id, !l.enabled) { if (!isFinishing) buildLists() } }
        if (l.url != null) actions += R.string.update to {
            Toast.makeText(this, R.string.list_adding, Toast.LENGTH_SHORT).show()
            blocker.update(l.id) { result ->
                result.onSuccess { Toast.makeText(this, resources.getQuantityString(R.plurals.list_updated, it.rules, it.name, it.rules), Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, getString(R.string.blocklist_failed, it.message), Toast.LENGTH_LONG).show() }
                if (!isFinishing) buildLists()
            }
        }
        if (l.bundled && l.updated > 0) actions += R.string.reset_bundled to { blocker.resetToBundled(l.id) { if (!isFinishing) buildLists() } }
        if (!l.bundled) actions += R.string.remove to { blocker.remove(l.id) { if (!isFinishing) buildLists() } }
        dialog().setTitle(l.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun enterListAddress() {
        val field = EditText(this).apply {
            setText("https://")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        dialog().setTitle(R.string.add_filter_list)
            .setMessage(R.string.filter_list_address_message)
            .setView(padded(field))
            .setPositiveButton(R.string.add) { _, _ -> addListAt(field.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun addListAt(url: String) {
        // Debug builds also take http://, so tests can serve a list from the development machine.
        if (!url.startsWith("https://") && !(BuildConfig.DEBUG && url.startsWith("http://"))) { Toast.makeText(this, R.string.https_only, Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, R.string.list_adding, Toast.LENGTH_SHORT).show()
        blocker.add(url) { result ->
            result.onSuccess { Toast.makeText(this, resources.getQuantityString(R.plurals.list_added, it.rules, it.name, it.rules), Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this, getString(R.string.list_add_failed, it.message), Toast.LENGTH_LONG).show() }
            if (!isFinishing) buildLists()
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
        private const val PAGE_LISTS = "lists"
    }
}
