package app.sofabrowser.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.InputDevice
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.sofabrowser.R
import app.sofabrowser.browser.GamePad

/** What each game pad button does, with the button names printed on the pad in hand (GamePad has the layout). */
object PadHelp {

    private class Labels(val a: String, val b: String, val x: String, val y: String, val menu: String, val view: String,
                         val l1: String, val r1: String, val l2: String, val r2: String)

    private fun labels(family: GamePad.Family) = when (family) {
        GamePad.Family.XBOX -> Labels("A", "B", "X", "Y", "Menu", "View", "LB", "RB", "LT", "RT")
        GamePad.Family.PLAYSTATION -> Labels("✕", "○", "□", "△", "Options", "Share", "L1", "R1", "L2", "R2")
    }

    private fun browsing(c: Context, l: Labels) = listOf(
        l.a to c.getString(R.string.pad_select),
        l.b to c.getString(R.string.pad_back),
        l.x to c.getString(R.string.pad_watch),
        l.y to c.getString(R.string.pad_search),
        l.menu to c.getString(R.string.pad_menu),
        l.view to c.getString(R.string.pad_home),
        "${l.l1}  ${l.r1}" to c.getString(R.string.pad_history),
        "${l.l2}  ${l.r2}" to c.getString(R.string.pad_page),
        c.getString(R.string.pad_left_stick) to c.getString(R.string.pad_pointer),
        c.getString(R.string.pad_right_stick) to c.getString(R.string.pad_scroll),
    )

    private fun video(c: Context, l: Labels) = listOf(
        l.a to c.getString(R.string.pad_controls),
        l.b to c.getString(R.string.pad_back),
        l.x to c.getString(R.string.pad_play_pause),
        l.y to c.getString(R.string.subtitles),
        "${l.l1}  ${l.r1}" to c.getString(R.string.pad_seek_short),
        "${l.l2}  ${l.r2}" to c.getString(R.string.pad_seek_long),
        l.view to c.getString(R.string.video_info),
    )

    private fun table(c: Context, rows: List<Pair<String, String>>, size: Int, keyColor: Int, textColor: Int) =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            for ((key, meaning) in rows) {
                val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(Ui.text(c, size, keyColor, bold = true).apply { text = key }, LinearLayout.LayoutParams(220.px, LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(Ui.text(c, size, textColor).apply { text = meaning })
                addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.px })
            }
        }

    /** A card with the browsing buttons, bottom left over everything, for a few seconds. */
    fun showHint(c: Context, parent: FrameLayout, family: GamePad.Family) {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xE6000000.toInt(), 24)
            setPadding(40.px, 32.px, 48.px, 36.px)
            addView(Ui.text(c, 30, Color.WHITE, bold = true).apply { text = c.getString(R.string.game_controller) })
            addView(table(c, browsing(c, labels(family)), 24, Color.WHITE, Ui.DARK.textMid))
            alpha = 0f
        }
        parent.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(64.px, 0, 0, 64.px) })
        card.animate().alpha(1f).setDuration(200).start()
        card.postDelayed({ card.animate().alpha(0f).setDuration(300).withEndAction { parent.removeView(card) }.start() }, HINT_MS)
    }

    /** Every button, browsing and in fullscreen video, for the pad connected now (Xbox names when none is). */
    fun dialog(c: Context) {
        val l = labels(connectedFamily())
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64.px, 8.px, 64.px, 0)
            addView(table(c, browsing(c, l), 28, Ui.p.text, Ui.p.textMid))
            addView(Ui.text(c, 28, Ui.p.textWeak).apply { text = c.getString(R.string.pad_in_video); setPadding(0, 40.px, 0, 0) })
            addView(table(c, video(c, l), 28, Ui.p.text, Ui.p.textMid))
            addView(Ui.text(c, 24, Ui.p.textHint).apply { text = c.getString(R.string.pad_note); setPadding(0, 32.px, 0, 0) })
        }
        Ui.dialog(c).setTitle(R.string.game_controller).setView(ScrollView(c).apply { addView(body) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun connectedFamily(): GamePad.Family =
        InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice)
            .firstOrNull { it.supportsSource(InputDevice.SOURCE_GAMEPAD) }
            ?.let { GamePad.family(it.vendorId) } ?: GamePad.Family.XBOX

    private const val HINT_MS = 9000L
}
