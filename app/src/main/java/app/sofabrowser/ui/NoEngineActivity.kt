package app.sofabrowser.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import app.sofabrowser.R

/**
 * Shown instead of the browser when the TV has no web engine Sofa can use: Android System WebView missing, turned
 * off, or one Android won't load. Every page needs it, so this says what to look for, and OK or Back closes Sofa.
 */
class NoEngineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val p = Ui.p
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.background(false)
            setPadding(96.px, 80.px, 96.px, 48.px)
        }
        root.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(88.px, 88.px))
        root.addView(Ui.text(this, 48, p.text, bold = true).apply {
            text = getString(R.string.no_engine_title)
            gravity = Gravity.CENTER
            setPadding(0, 28.px, 0, 0)
        })
        root.addView(Ui.text(this, 28, p.textMid).apply {
            text = getString(R.string.no_engine_text)
            gravity = Gravity.CENTER
            setPadding(0, 16.px, 0, 48.px)
        }, LinearLayout.LayoutParams(TEXT_WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT))
        val close = Ui.button(this, getString(R.string.no_engine_close), 32) { finishAffinity() }
        close.minWidth = 380.px
        root.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        close.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finishAffinity(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val TEXT_WIDTH = 1200
    }
}
