package app.sofabrowser.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import app.sofabrowser.R
import app.sofabrowser.downloads.Download
import app.sofabrowser.downloads.DownloadEngine
import app.sofabrowser.downloads.DownloadStorage
import app.sofabrowser.downloads.Http
import app.sofabrowser.downloads.Status

/** The download manager: live progress, pause/resume, retry, open, delete. */
class DownloadsActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: View
    private val rows = LinkedHashMap<Long, Row>()
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        DownloadEngine.init(this)
        val p = Ui.p
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.background(false)
            setPadding(116.px, 72.px, 116.px, 0)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(this, 56, p.text, bold = true).apply { text = getString(R.string.downloads) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.button(this, getString(R.string.clear_finished)) { DownloadEngine.clearFinished() })
        root.addView(header)
        root.addView(Ui.text(this, 24, p.textWeak).apply {
            text = getString(R.string.downloads_location, DownloadStorage.locationLabel(this@DownloadsActivity))
            setPadding(0, 12.px, 0, 0)
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 32.px, 0, 72.px); clipChildren = false }
        val frame = FrameLayout(this)
        frame.addView(FocusScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(list) })
        empty = Ui.text(this, 32, p.textWeak).apply { text = getString(R.string.downloads_empty); gravity = Gravity.CENTER }
        frame.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
        list.getChildAt(0)?.let { (it as? Row)?.body?.requestFocus() }
    }

    override fun onStart() {
        super.onStart()
        DownloadEngine.addListener(listener)
        refresh()
    }

    override fun onStop() {
        DownloadEngine.removeListener(listener)
        super.onStop()
    }

    /** Updates rows in place; rebuilds only when entries come or go, keeping focus on the same download. */
    private fun refresh() {
        val items = DownloadEngine.all
        if (items.map { it.id } != rows.keys.toList()) {
            val focusedId = rows.entries.firstOrNull { it.value.hasFocus() }?.key
            val focusedIndex = rows.keys.indexOf(focusedId)
            list.removeAllViews()
            rows.clear()
            items.forEach { d -> Row(d).also { rows[d.id] = it; list.addView(it) } }
            val target = rows[focusedId] ?: if (focusedIndex >= 0) rows.values.elementAtOrNull(focusedIndex.coerceAtMost(rows.size - 1)) else null
            target?.body?.requestFocus()
        }
        items.forEach { rows[it.id]?.bind(it) }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(d: Download) {
        val intent = DownloadEngine.openIntent(d)
        if (intent == null) { toast(R.string.download_missing); return }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.download_no_app)
        } catch (e: SecurityException) {
            toast(R.string.download_no_app)
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private inner class Row(initial: Download) : LinearLayout(this@DownloadsActivity) {
        val body = LinearLayout(context)
        private val icon = ImageView(context)
        private val name = Ui.text(context, 32, Ui.p.text)
        private val meta = Ui.text(context, 24, Ui.p.textWeak)
        private val bar = ProgressLine()
        private val actionA = action()
        private val actionB = action()
        private var id = initial.id

        init {
            val p = Ui.p
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            body.apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                background = Ui.fillSelector(p.fill, p.fillFocused, 16)
                foreground = Ui.focusBorder(16)
                setPadding(36.px, 20.px, 36.px, 20.px)
                setOnClickListener { primary() }
                setOnFocusChangeListener { v, has -> Ui.animateScale(v, if (has) 1.02f else 1f, has) }
            }
            icon.setColorFilter(p.textMid)
            body.addView(icon, LayoutParams(56.px, 56.px).apply { marginEnd = 32.px })
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.MIDDLE
            meta.maxLines = 1
            meta.ellipsize = TextUtils.TruncateAt.END
            texts.addView(name)
            texts.addView(meta, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 10.px })
            texts.addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, 8.px).apply { topMargin = 14.px })
            body.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(actionA, LayoutParams(80.px, 80.px).apply { marginStart = 24.px })
            addView(actionB, LayoutParams(80.px, 80.px).apply { marginStart = 16.px })
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.px }
            bind(initial)
        }

        private fun current() = DownloadEngine.all.firstOrNull { it.id == id }

        /** OK on the row: open when finished, otherwise pause or resume. */
        private fun primary() {
            val d = current() ?: return
            when (d.status) {
                Status.DONE -> open(d)
                Status.RUNNING, Status.QUEUED -> DownloadEngine.pause(d.id)
                Status.PAUSED, Status.FAILED -> DownloadEngine.resume(d.id)
            }
        }

        fun bind(d: Download) {
            id = d.id
            val p = Ui.p
            name.text = d.name
            icon.setImageResource(iconFor(d))
            val host = Uri.parse(d.url).host
            val size = when {
                d.status == Status.DONE -> Http.formatBytes(d.done)
                d.total > 0 -> getString(R.string.download_progress, Http.formatBytes(d.done), Http.formatBytes(d.total))
                d.done > 0 -> Http.formatBytes(d.done)
                else -> null
            }
            val state = when (d.status) {
                Status.QUEUED -> getString(R.string.download_queued)
                Status.RUNNING -> when {
                    d.error == "retry" -> getString(R.string.download_retrying)
                    d.speed > 0 -> Http.formatBytes(d.speed.toLong()) + "/s" +
                        if (d.total > 0) " · " + getString(R.string.download_left, Http.formatDuration(((d.total - d.done) / d.speed).toLong())) else ""
                    else -> getString(R.string.download_starting)
                }
                Status.PAUSED -> getString(R.string.download_paused)
                Status.FAILED -> getString(R.string.download_failed_status, d.error ?: "")
                Status.DONE -> if (DownloadEngine.fileExists(d)) null else getString(R.string.download_missing)
            }
            meta.text = listOfNotNull(host, size, state).joinToString("  ·  ")
            meta.setTextColor(if (d.status == Status.FAILED) Ui.BRAND else p.textWeak)
            bar.visibility = if (d.status == Status.DONE) View.GONE else View.VISIBLE
            bar.fraction = d.fraction.toFloat()
            bar.indeterminate = d.status == Status.RUNNING && d.total <= 0
            bar.paused = d.status != Status.RUNNING
            when (d.status) {
                Status.RUNNING, Status.QUEUED -> {
                    actionA.set(R.drawable.ic_pause, R.string.pause) { DownloadEngine.pause(id) }
                    actionB.set(R.drawable.ic_close, R.string.cancel_download) { DownloadEngine.delete(id) }
                }
                Status.PAUSED -> {
                    actionA.set(R.drawable.ic_play_arrow, R.string.resume) { DownloadEngine.resume(id) }
                    actionB.set(R.drawable.ic_close, R.string.cancel_download) { DownloadEngine.delete(id) }
                }
                Status.FAILED -> {
                    actionA.set(R.drawable.ic_refresh, R.string.retry) { DownloadEngine.resume(id) }
                    actionB.set(R.drawable.ic_close, R.string.remove) { DownloadEngine.removeFromList(id) }
                }
                Status.DONE -> {
                    actionA.set(R.drawable.ic_delete, R.string.delete_file) { confirmDelete() }
                    actionB.set(R.drawable.ic_close, R.string.remove_from_list) { DownloadEngine.removeFromList(id) }
                }
            }
        }

        private fun confirmDelete() {
            val d = current() ?: return
            Ui.dialog(context).setMessage(getString(R.string.delete_file_confirm, d.name))
                .setPositiveButton(R.string.delete) { _, _ -> DownloadEngine.delete(id) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }

        private fun iconFor(d: Download): Int {
            val mime = d.mime.orEmpty()
            val ext = d.name.substringAfterLast('.', "").lowercase()
            return when {
                mime == "application/vnd.android.package-archive" || ext == "apk" -> R.drawable.ic_android
                mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts") -> R.drawable.ic_movie
                mime.startsWith("audio/") || ext in setOf("mp3", "flac", "ogg", "opus", "m4a", "wav") -> R.drawable.ic_music_note
                mime.startsWith("image/") -> R.drawable.ic_image
                ext in setOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst") -> R.drawable.ic_folder_zip
                mime.startsWith("text/") || ext in setOf("pdf", "txt", "epub", "doc", "docx", "odt") -> R.drawable.ic_description
                else -> R.drawable.ic_draft
            }
        }
    }

    /** Round icon button beside a row. */
    private fun action() = ActionButton()

    private inner class ActionButton : FrameLayout(this@DownloadsActivity) {
        private val img = ImageView(context)
        private var onClick: () -> Unit = {}

        init {
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(Ui.p.fillFocused, Ui.p.focus, 40)
            addView(img, LayoutParams(40.px, 40.px, Gravity.CENTER))
            img.setColorFilter(Ui.p.textMid)
            setOnFocusChangeListener { _, has -> img.setColorFilter(if (has) Ui.p.onFocus else Ui.p.textMid) }
            setOnClickListener { onClick() }
        }

        fun set(icon: Int, label: Int, action: () -> Unit) {
            img.setImageResource(icon)
            contentDescription = getString(label)
            onClick = action
        }
    }

    /** Thin progress bar; sweeps when the size is unknown, dims when not running. */
    private inner class ProgressLine : View(this@DownloadsActivity) {
        var fraction = 0f
            set(v) { field = v; invalidate() }
        var indeterminate = false
            set(v) { field = v; invalidate() }
        var paused = false
            set(v) { field = v; invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            track.color = Ui.p.fillFocused
            fill.color = if (paused) Ui.p.textWeak else Ui.BRAND
            c.drawRoundRect(0f, 0f, width.toFloat(), h, h, h, track)
            if (indeterminate) {
                val t = (System.currentTimeMillis() % 1500) / 1500f
                val x = (width + width / 3f) * t - width / 3f
                c.drawRoundRect(x, 0f, x + width / 3f, h, h, h, fill)
                postInvalidateOnAnimation()
            } else if (fraction > 0) {
                c.drawRoundRect(0f, 0f, width * fraction, h, h, h, fill)
            }
        }
    }
}
