package app.sofabrowser.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.text.InputType
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
import app.sofabrowser.browser.ProtectedMedia
import app.sofabrowser.browser.WebViews
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.update.Updater

/** One centred list of settings, styled like BrowsHere's: rows turn white with black text when focused. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

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
        build()
    }

    private fun build() {
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
        switch(R.string.back_goes_back, R.string.back_goes_back_sub, prefs::backGoesBack)
        switch(R.string.javascript, R.string.javascript_sub, prefs::javascript)
        switch(R.string.autoplay, R.string.autoplay_sub, prefs::autoplay)
        nav(R.string.downloads, null) { startActivity(android.content.Intent(this, DownloadsActivity::class.java)) }

        section(R.string.section_privacy)
        switch(R.string.adblock_setting, R.string.adblock_setting_sub, prefs::adBlock)
        val blocker = AdBlocker.get(this)
        nav(R.string.blocklist, resources.getQuantityString(R.plurals.blocklist_sub, blocker.list.size, blocker.list.size)) { updateBlocklist() }
        switch(R.string.cookies, R.string.cookies_sub, prefs::cookies, confirmOff = R.string.cookies_confirm)
        switch(R.string.third_party_cookies, R.string.third_party_cookies_sub, prefs::blockThirdPartyCookies)
        switch(R.string.gpc, R.string.gpc_sub, prefs::sendGpc)
        switch(R.string.safe_browsing, R.string.safe_browsing_sub, prefs::safeBrowsing)
        row(getString(R.string.drm_setting), getString(R.string.drm_setting_sub), valueText(drmLabel())) { pickDrm() }
        switch(R.string.remember_history, R.string.remember_history_sub, prefs::rememberHistory)
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

        list.getChildAt(2)?.requestFocus()
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

    private fun valueText(value: String) = Ui.text(this, 28, Ui.p.textMid).apply { text = value; setPadding(24.px, 0, 0, 0) }

    private fun nav(title: Int, value: String?, onClick: () -> Unit) {
        val end = if (value != null) valueText(value)
        else ImageView(this).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(Ui.p.textMid); layoutParams = LinearLayout.LayoutParams(40.px, 40.px) }
        row(getString(title), null, end, onClick)
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

    private fun updateBlocklist() {
        val blocker = AdBlocker.get(this)
        val field = EditText(this).apply {
            setText(prefs.blocklistUrl)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        dialog().setTitle(R.string.blocklist)
            .setMessage(resources.getQuantityString(R.plurals.blocklist_update_message, blocker.list.size, blocker.list.size))
            .setView(padded(field))
            .setPositiveButton(R.string.update) { _, _ ->
                val url = field.text.toString().trim()
                // Debug builds also take http://, so tests can serve a list from the development machine.
                if (!url.startsWith("https://") && !(BuildConfig.DEBUG && url.startsWith("http://"))) { Toast.makeText(this, R.string.https_only, Toast.LENGTH_LONG).show(); return@setPositiveButton }
                prefs.blocklistUrl = url
                Toast.makeText(this, R.string.blocklist_updating, Toast.LENGTH_SHORT).show()
                blocker.update(url) { result ->
                    runOnUiThread {
                        result.onSuccess { Toast.makeText(this, resources.getQuantityString(R.plurals.blocklist_updated, it, it), Toast.LENGTH_LONG).show() }
                            .onFailure { Toast.makeText(this, getString(R.string.blocklist_failed, it.message), Toast.LENGTH_LONG).show() }
                        if (!isFinishing) build()
                    }
                }
            }
            .setNeutralButton(R.string.reset_bundled) { _, _ -> blocker.resetToBundled(); list.postDelayed({ build() }, 1500) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun clearNow() {
        dialog().setMessage(R.string.clear_now_confirm)
            .setPositiveButton(R.string.clear) { _, _ ->
                WebViews.clearBrowsingData(this, null)
                Db.get(this).clearHistory()
                Favicons.get(this).clear()
                prefs.clearDrmAnswers()
                app.sofabrowser.resumeStore(this).clear()
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
    }
}
