package app.sofabrowser.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Hosts the WebView and turns the remote's D-pad into a mouse-like pointer.
 *
 * Holding a direction accelerates the pointer (0.05 px/frame gained per ms held, scaled to a 1920 px wide
 * screen, capped at width/25 px per 60 Hz frame); releasing stops it at once. Near an edge, the page is scrolled
 * at the pointer's speed through a synthetic finger drag, which also moves inner scrollers and iframes. OK taps at
 * the pointer, OK held while moving drags, and OK held still for a second switches to scroll mode. The pointer
 * hides after five seconds without use; the first OK after that only reveals it.
 */
class CursorLayout(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onScrollModeChanged(active: Boolean)
    }

    var listener: Listener? = null

    /** 1 = slow, 2 = normal, 3 = fast. */
    var speedLevel = 2

    /** When false, every key goes to the page as-is (for sites built for D-pad navigation). */
    var cursorEnabled = true
        set(value) {
            field = value
            if (!value) { cancelGestures(); invalidate() }
        }

    var scrollMode = false
        private set

    private val pos = PointF()
    private val vel = PointF()
    private var dirX = 0
    private var dirY = 0
    private var lastActivity = 0L
    private var lastFrameNanos = 0L
    private var running = false

    private var okHeld = false
    private var okRevealOnly = false
    private var okStart = 0L
    private var touchDown = false
    private var touchDownTime = 0L

    private var dragging = false
    private val dragPos = PointF()
    private var dragDownTime = 0L
    private val dragRect = RectF()

    /** Remaining distance of a page scroll (CH+/CH-) still to be animated, in px. */
    private var pendingScrollY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF577FFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
    // A faint dark outline keeps the white ring visible on white pages.
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }

    private val w get() = width.toFloat()
    private val h get() = height.toFloat()
    private val unit get() = max(w, 1f) / 1920f
    private val radius get() = max(w / 132f, 6f)
    private val pressedRadius get() = radius + 4 * resources.displayMetrics.density
    private val speedFactor get() = when (speedLevel) { 1 -> 0.6f; 3 -> 1.5f; else -> 1f }
    private val maxSpeed get() = w / 25f * speedFactor
    private val edge get() = w / 15f

    init {
        setWillNotDraw(false)
        lastActivity = SystemClock.uptimeMillis() - HIDE_AFTER_MS
    }

    val visible get() = cursorEnabled && SystemClock.uptimeMillis() - lastActivity < HIDE_AFTER_MS

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        if (ow == 0 || oh == 0) pos.set(nw / 2f, nh / 2f) else pos.set(pos.x * nw / ow, pos.y * nh / oh)
        val inset = min(nw, nh) * 0.25f
        dragRect.set(inset, inset, nw - inset, nh - inset)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!cursorEnabled) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                direction(event.keyCode, down, event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down) okDown(event.repeatCount) else okUp()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun direction(code: Int, down: Boolean, repeat: Int) {
        lastActivity = SystemClock.uptimeMillis()
        val (dx, dy) = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP_LEFT -> -1 to -1
            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> 1 to -1
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> -1 to 1
            else -> 1 to 1
        }
        if (down) {
            if (repeat > 0) return
            if (dx != 0) dirX = dx
            if (dy != 0) dirY = dy
            // A quick tap still moves a few pixels, for fine positioning.
            if (!scrollMode) {
                pos.x = (pos.x + dx * NUDGE * unit).coerceIn(0f, w - 1)
                pos.y = (pos.y + dy * NUDGE * unit).coerceIn(0f, h - 1)
                if (okHeld && touchDown) touch(MotionEvent.ACTION_MOVE) else if (!okHeld) hover()
            }
            start()
        } else {
            if (dx != 0) dirX = 0
            if (dy != 0) dirY = 0
            // Stop dead on release; no glide.
            vel.set(0f, 0f)
            endDrag()
            invalidate()
        }
    }

    private fun okDown(repeat: Int) {
        if (repeat > 0) return
        if (scrollMode) { setScrollMode(false); okRevealOnly = true; return }
        if (!visible) {
            okRevealOnly = true
            lastActivity = SystemClock.uptimeMillis()
            invalidate()
            return
        }
        okHeld = true
        okStart = SystemClock.uptimeMillis()
        lastActivity = okStart
        postDelayed(holdCheck, HOLD_FOR_SCROLL_MS)
        invalidate()
    }

    private fun okUp() {
        removeCallbacks(holdCheck)
        lastActivity = SystemClock.uptimeMillis()
        if (okRevealOnly) { okRevealOnly = false; return }
        if (!okHeld) return
        okHeld = false
        if (!touchDown) touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
        invalidate()
    }

    private val holdCheck = Runnable {
        if (okHeld && !touchDown) {
            okHeld = false
            okRevealOnly = true // swallow the key-up
            setScrollMode(true)
        }
    }

    fun setScrollMode(active: Boolean) {
        if (scrollMode == active) return
        scrollMode = active
        cancelGestures()
        listener?.onScrollModeChanged(active)
        invalidate()
    }

    /** Smoothly scrolls the content under the pointer by [pages] screen heights (negative = up). */
    fun scrollPages(pages: Float) {
        // A full screen height of drag; touch slop and re-grabs eat ~15%, leaving a page with some overlap.
        pendingScrollY += pages * h
        start()
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val dt = if (lastFrameNanos == 0L) 16.67f else ((now - lastFrameNanos) / 1_000_000f).coerceIn(1f, 50f)
            lastFrameNanos = now
            val frames = dt / 16.67f
            val accel = 0.05f * unit * speedFactor * dt

            vel.x = if (dirX == 0) 0f else (vel.x + dirX * accel).coerceIn(-maxSpeed, maxSpeed)
            vel.y = if (dirY == 0) 0f else (vel.y + dirY * accel).coerceIn(-maxSpeed, maxSpeed)
            val stepX = vel.x * frames
            val stepY = vel.y * frames

            if (scrollMode) {
                // Directions scroll the page; the pointer stays put.
                if (stepX != 0f || stepY != 0f) dragScroll(stepX, stepY)
            } else if (stepX != 0f || stepY != 0f) {
                lastActivity = SystemClock.uptimeMillis()
                val oldX = pos.x
                val oldY = pos.y
                pos.x = (pos.x + stepX).coerceIn(0f, w - 1)
                pos.y = (pos.y + stepY).coerceIn(0f, h - 1)
                if (pos.x != oldX || pos.y != oldY) {
                    if (okHeld) {
                        if (!touchDown) { removeCallbacks(holdCheck); touch(MotionEvent.ACTION_DOWN, PointF(oldX, oldY)) }
                        touch(MotionEvent.ACTION_MOVE)
                    } else {
                        hover()
                    }
                }
                val sx = if ((pos.x > w - edge && vel.x > 0) || (pos.x < edge && vel.x < 0)) stepX else 0f
                val sy = if ((pos.y > h - edge && vel.y > 0) || (pos.y < edge && vel.y < 0)) stepY else 0f
                if ((sx != 0f || sy != 0f) && !okHeld) dragScroll(sx, sy)
            }

            if (pendingScrollY != 0f) {
                val step = if (abs(pendingScrollY) < 8 * unit) pendingScrollY else pendingScrollY * 0.22f
                pendingScrollY -= step
                dragScroll(0f, step)
                if (pendingScrollY == 0f) endDrag()
            }

            invalidate()
            if (dirX != 0 || dirY != 0 || pendingScrollY != 0f) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                postDelayed({ invalidate() }, HIDE_AFTER_MS + 50)
            }
        }
    }

    /**
     * Scrolls whatever is under the drag point by ([dx], [dy]) using a synthetic finger drag. The finger moves
     * against the scroll direction and is re-grabbed (with CANCEL, so there is no fling) when it would leave the
     * inner rectangle.
     */
    private fun dragScroll(dx: Float, dy: Float) {
        val now = SystemClock.uptimeMillis()
        if (!dragging) {
            dragPos.set(pos.x.coerceIn(dragRect.left + 1, dragRect.right - 1), pos.y.coerceIn(dragRect.top + 1, dragRect.bottom - 1))
            dragDownTime = now
            sendTouch(MotionEvent.ACTION_DOWN, dragDownTime, dragPos.x, dragPos.y)
            dragging = true
        }
        val nx = dragPos.x - dx
        val ny = dragPos.y - dy
        if (dragRect.contains(nx, ny)) {
            dragPos.set(nx, ny)
            sendTouch(MotionEvent.ACTION_MOVE, dragDownTime, nx, ny)
        } else {
            sendTouch(MotionEvent.ACTION_CANCEL, dragDownTime, dragPos.x, dragPos.y)
            dragging = false
        }
    }

    private fun endDrag() {
        if (!dragging) return
        sendTouch(MotionEvent.ACTION_CANCEL, dragDownTime, dragPos.x, dragPos.y)
        dragging = false
    }

    private fun cancelGestures() {
        removeCallbacks(holdCheck)
        endDrag()
        if (touchDown) touch(MotionEvent.ACTION_CANCEL)
        okHeld = false
        dirX = 0; dirY = 0
        vel.set(0f, 0f)
        pendingScrollY = 0f
    }

    /** A real tap at ([x], [y]) in this view, for actions that need a user gesture in the page (such as "Watch"). */
    fun tapAt(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        sendTouch(MotionEvent.ACTION_DOWN, down, x, y)
        sendTouch(MotionEvent.ACTION_UP, down, x, y)
    }

    private fun touch(action: Int, at: PointF = pos) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) { touchDownTime = now; touchDown = true }
        sendTouch(action, touchDownTime, at.x, at.y)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchDown = false
    }

    private fun sendTouch(action: Int, downTime: Long, x: Float, y: Float) {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        super.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Mouse hover at the pointer, so CSS :hover and hover menus react as they would on a desktop. */
    private fun hover() {
        val child = getChildAt(0) ?: return
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { x = pos.x - child.left; y = pos.y - child.top }
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
        child.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!visible || scrollMode) return
        val r = if (okHeld || touchDown) pressedRadius else radius
        canvas.drawCircle(pos.x, pos.y, r, fill)
        canvas.drawCircle(pos.x, pos.y, r, ring)
        canvas.drawCircle(pos.x, pos.y, r + 3f, outline)
    }

    /** Hides the pointer until the next key, e.g. while an overlay is open. */
    fun hide() {
        cancelGestures()
        lastActivity = SystemClock.uptimeMillis() - HIDE_AFTER_MS
        invalidate()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        running = false
        super.onDetachedFromWindow()
    }

    companion object {
        const val HIDE_AFTER_MS = 5000L
        const val HOLD_FOR_SCROLL_MS = 1000L
        private const val NUDGE = 6f
    }
}
