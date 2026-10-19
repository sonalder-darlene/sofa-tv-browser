package app.sofabrowser.ui

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.SearchEngine
import app.sofabrowser.update.Updater

/**
 * First start: the user picks a search engine; Sofa doesn't pick one for them. Back leaves without choosing,
 * and the question comes back next time. Copies that no app store updates then ask whether to check for updates.
 */
class SetupActivity : Activity() {

    private lateinit var prefs: Prefs
    private var askingUpdates = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        prefs = Prefs(this)
        when {
            !prefs.setupDone -> askEngine()
            Updater.offered(this) && !prefs.updatesAsked -> askUpdates()
            else -> finish()
        }
    }

    private fun page(title: Int, subtitle: Int) = LinearLayout(this).apply {
        val p = Ui.p
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = Ui.background(false)
        setPadding(96.px, 80.px, 96.px, 48.px)
        clipChildren = false
        addView(ImageView(context).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(88.px, 88.px))
        addView(Ui.text(context, 56, p.text, bold = true).apply {
            text = getString(title)
            gravity = Gravity.CENTER
            setPadding(0, 28.px, 0, 0)
        })
        addView(Ui.text(context, 28, p.textMid).apply {
            text = getString(subtitle)
            gravity = Gravity.CENTER
            setPadding(0, 16.px, 0, 48.px)
        }, LinearLayout.LayoutParams(TEXT_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun askEngine() {
        val root = page(R.string.setup_title, R.string.setup_subtitle)
        val engines = Prefs.ENGINES
        engines.chunked(COLUMNS).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            row.forEach { line.addView(card(it)) }
            root.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 28.px
            })
        }
        setContentView(root)
        // Start on the first card: the order is alphabetical, so focus favours no engine.
        root.findViewWithTag<LinearLayout>(engines.first().id)?.requestFocus()
    }

    /** Opt-in update checks. Saying no is the first, focused choice, and Back means no as well. */
    private fun askUpdates() {
        askingUpdates = true
        val root = page(R.string.updates_title, R.string.updates_text)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val no = Ui.button(this, getString(R.string.updates_no), 32) { answerUpdates(false) }
        val yes = Ui.button(this, getString(R.string.updates_yes), 32) { answerUpdates(true) }
        listOf(no, yes).forEach {
            it.minWidth = 380.px
            buttons.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16.px
                marginEnd = 16.px
            })
        }
        root.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        no.requestFocus()
    }

    private fun answerUpdates(check: Boolean) {
        prefs.checkUpdates = check
        prefs.updatesAsked = true
        finish()
    }

    private fun card(engine: SearchEngine) = LinearLayout(this).apply {
        val p = Ui.p
        tag = engine.id
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        background = Ui.fillSelector(p.card, p.cardFocused, 24)
        foreground = Ui.focusBorder(24)
        setPadding(28.px, 20.px, 24.px, 20.px)
        addView(EngineIcon.view(context, engine),
            LinearLayout.LayoutParams(64.px, 64.px).apply { marginEnd = 24.px })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val nameLine = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameLine.addView(Ui.text(context, 32, p.text, bold = true).apply { text = engine.name; maxLines = 1 })
        if (engine.paid) {
            nameLine.addView(Ui.text(context, 18, Ui.BRAND).apply {
                text = getString(R.string.subscription)
                background = Ui.rounded(0x26FF8A3D, 8)
                setPadding(10.px, 4.px, 10.px, 4.px)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 14.px })
        }
        texts.addView(nameLine)
        if (engine.tagline != 0) {
            texts.addView(Ui.text(context, 22, p.textWeak).apply {
                text = getString(engine.tagline)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 8.px, 0, 0)
            })
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = LinearLayout.LayoutParams(CARD_WIDTH.px, 150.px).apply {
            marginStart = 14.px
            marginEnd = 14.px
        }
        Ui.focusScale(this)
        setOnClickListener { choose(engine) }
    }

    private fun choose(engine: SearchEngine) {
        if (!engine.paid) { save(engine); return }
        Ui.dialog(this).setTitle(engine.name)
            .setMessage(getString(R.string.setup_paid, engine.name))
            .setPositiveButton(getString(R.string.setup_use, engine.name)) { _, _ -> save(engine) }
            .setNegativeButton(R.string.setup_other, null)
            .show()
    }

    private fun save(engine: SearchEngine) {
        prefs.searchEngineId = engine.id
        prefs.setupDone = true
        if (Updater.offered(this) && !prefs.updatesAsked) askUpdates() else finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Leaving without an engine closes Sofa; the question is asked again on the next start.
            if (askingUpdates) answerUpdates(false) else finishAffinity()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val COLUMNS = 4
        private const val CARD_WIDTH = 400
        private const val TEXT_WIDTH = 1300
    }
}
