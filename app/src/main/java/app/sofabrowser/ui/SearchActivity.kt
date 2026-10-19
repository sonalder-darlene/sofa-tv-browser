package app.sofabrowser.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.Db
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Address and search entry. Returns the URL to open in [EXTRA_URL]. Suggestions come from local bookmarks and
 * history; remote suggestions are opt-in and go straight to the chosen engine.
 */
class SearchActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var field: EditText
    private lateinit var engineLabel: TextView
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var remoteRequest = 0
    private lateinit var micButton: FrameLayout
    private lateinit var micIcon: ImageView
    private lateinit var halo: View
    private val voiceInput by lazy { VoiceInput(this, voiceListener) }

    // IME_FLAG_NO_PERSONALIZED_LEARNING is a compile-time constant; keyboards before API 26 just ignore it.
    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.background(intent.getBooleanExtra(EXTRA_PRIVATE, false))
            setPadding(88.px, 80.px, 88.px, 0)
            // The mic halo may grow beyond the button row and into the padding.
            clipToPadding = false
            clipChildren = false
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.p.fillFocused, 12)
        }
        engineLabel = Ui.text(this, 26, Ui.p.textMid).apply {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            background = Ui.fillSelector(0, Ui.p.focus, 12)
            setPadding(24.px, 0, 24.px, 0)
            setOnClickListener { pickEngine() }
            setOnFocusChangeListener { v, has -> (v as TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid) }
        }
        box.addView(engineLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 96.px))
        field = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 32.px.toFloat())
            setTextColor(Ui.p.text)
            setHintTextColor(Ui.p.textHint)
            hint = getString(R.string.search_hint)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setPadding(24.px, 0, 24.px, 0)
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(text.toString()); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = suggest(s.toString())
            })
        }
        box.addView(field, LinearLayout.LayoutParams(0, 96.px, 1f))
        bar.addView(box, LinearLayout.LayoutParams(0, 96.px, 1f))
        bar.addView(squareButton(R.drawable.ic_search, R.string.search) { submit(field.text.toString()) })
        // The mic sits on a halo that grows with the voice level. The holder and the bar let the halo draw
        // beyond their bounds, so it is never cut square.
        bar.clipChildren = false
        val micHolder = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        halo = View(this).apply { background = Ui.rounded(0x59FF8A3D, 48); visibility = View.INVISIBLE }
        micHolder.addView(halo, FrameLayout.LayoutParams(96.px, 96.px))
        micButton = squareButton(R.drawable.ic_mic, R.string.voice_search) { voice() }
        micHolder.addView(micButton, FrameLayout.LayoutParams(96.px, 96.px))
        micIcon = micButton.getChildAt(0) as ImageView
        micHolder.visibility = if (prefs.voiceSearch) View.VISIBLE else View.GONE
        bar.addView(micHolder, LinearLayout.LayoutParams(96.px, 96.px).apply { marginStart = 36.px })
        root.addView(bar)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 24.px, 0, 48.px) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        updateEngineLabel()
        field.setText(prefill(intent.getStringExtra(EXTRA_CURRENT_URL)))
        field.setSelection(field.text.length)
        if (intent.getBooleanExtra(EXTRA_VOICE, false) && prefs.voiceSearch) voice() else showKeyboard()
    }

    private fun squareButton(icon: Int, label: Int, onClick: () -> Unit) = FrameLayout(this).apply {
        isFocusable = true
        isClickable = true
        contentDescription = getString(label)
        background = Ui.fillSelector(Ui.p.fill, Ui.p.focus, 12)
        val img = ImageView(context).apply { setImageResource(icon); setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
        addView(img, FrameLayout.LayoutParams(48.px, 48.px, Gravity.CENTER))
        setOnFocusChangeListener { _, has -> img.setColorFilter(if (has) Ui.p.onFocus else Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(96.px, 96.px).apply { marginStart = 12.px }
    }

    private fun showKeyboard() {
        field.requestFocus()
        field.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    /** On a results page of the current engine, edit the query rather than the long URL. */
    private fun prefill(current: String?): String {
        if (current.isNullOrEmpty()) return ""
        val template = Uri.parse(prefs.searchEngine.template.replace("%s", "QUERY"))
        val param = template.queryParameterNames.firstOrNull { template.getQueryParameter(it) == "QUERY" }
        val uri = Uri.parse(current)
        if (param != null && uri.host == template.host) uri.getQueryParameter(param)?.let { return it }
        return current
    }

    private fun updateEngineLabel() {
        engineLabel.text = prefs.searchEngine.name
        val icon = EngineIcon.drawable(this, prefs.searchEngine).mutate().apply { setBounds(0, 0, 40.px, 40.px) }
        engineLabel.setCompoundDrawablesRelative(icon, null, null, null)
        engineLabel.compoundDrawablePadding = 16.px
    }

    private fun pickEngine() {
        val engines = Prefs.ENGINES
        val current = engines.indexOfFirst { it.id == prefs.searchEngineId }
        Ui.dialog(this)
            .setTitle(R.string.search_engine)
            .setSingleChoiceItems(engines.map { it.name }.toTypedArray(), current) { d, which ->
                prefs.searchEngineId = engines[which].id
                updateEngineLabel()
                d.dismiss()
                field.requestFocus()
            }.show()
    }

    private fun submit(text: String) {
        val url = UrlInput.resolve(text, prefs.searchEngine)
        if (url.isEmpty()) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, url))
        finish()
    }

    private fun suggest(text: String) {
        list.removeAllViews()
        val q = text.trim()
        if (q.isEmpty() || q == intent.getStringExtra(EXTRA_CURRENT_URL)) return
        val local = Db.get(this).suggest(q, 4)
        local.forEach { addRow(R.drawable.ic_history, it.title.ifBlank { it.url }, UrlInput.display(it.url)) { open(it.url) } }
        val engine = prefs.searchEngine
        val suggestUrl = engine.suggestTemplate
        if (!prefs.searchSuggestions || suggestUrl == null) return
        val request = ++remoteRequest
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            thread(name = "suggest") {
                val words = fetchSuggestions(suggestUrl.replace("%s", Uri.encode(q)))
                runOnUiThread {
                    if (request != remoteRequest || isFinishing) return@runOnUiThread
                    words.take(6).forEach { w -> addRow(R.drawable.ic_search, w, null) { submit(w) } }
                }
            }
        }, 250)
    }

    /** Both supported endpoints answer in the OpenSearch suggestion format: ["query", ["s1", "s2", ...]]. */
    private fun fetchSuggestions(url: String): List<String> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.useCaches = false
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().readText()).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(emptyList())

    private fun open(url: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, url))
        finish()
    }

    private fun addRow(icon: Int, title: String, subtitle: String?, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(0, Ui.p.focus, 16)
            setPadding(44.px, 0, 44.px, 0)
            setOnClickListener { onClick() }
        }
        val img = ImageView(this).apply { setImageResource(icon); setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
        row.addView(img, LinearLayout.LayoutParams(40.px, 40.px).apply { marginEnd = 32.px })
        val t = Ui.text(this, 30, Ui.p.textMid).apply { text = title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(t) }
        val s = subtitle?.let { Ui.text(this, 22, Ui.p.textWeak).apply { text = it; maxLines = 1; ellipsize = TextUtils.TruncateAt.END } }
        s?.let(texts::addView)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnFocusChangeListener { _, has ->
            val c = if (has) Ui.p.onFocus else Ui.p.textMid
            t.setTextColor(c)
            s?.setTextColor(if (has) Ui.p.onFocusWeak else Ui.p.textWeak)
            img.setColorFilter(c, PorterDuff.Mode.SRC_IN)
        }
        list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 94.px))
    }

    /** The mic: listens right here in the search field; pressing it again while listening stops. */
    private fun voice() {
        when {
            voiceInput.listening -> voiceInput.cancel()
            !voiceInput.available -> systemVoice()
            !voiceInput.hasPermission -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            else -> {
                hideKeyboard()
                field.setText("")
                voiceInput.start()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        // Without the microphone, the system's own voice screen still works (it records, not Sofa).
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voice() else systemVoice()
    }

    private val voiceListener = object : VoiceInput.Listener {
        override fun onListening(active: Boolean) {
            field.hint = getString(if (active) R.string.voice_listening else R.string.search_hint)
            // Focus first: the button's focus listener recolours its icon.
            if (active) micButton.requestFocus()
            micButton.background = if (active) Ui.rounded(Ui.BRAND, 48) else Ui.fillSelector(Ui.p.fill, Ui.p.focus, 12)
            micIcon.setColorFilter(if (active) Color.WHITE else if (micButton.hasFocus()) Ui.p.onFocus else Ui.p.textMid, PorterDuff.Mode.SRC_IN)
            halo.animate().cancel()
            if (active) {
                // A resting ring shows that Sofa is listening even before anything is said.
                halo.scaleX = HALO_REST
                halo.scaleY = HALO_REST
                halo.alpha = 0.6f
                halo.visibility = View.VISIBLE
            } else {
                halo.visibility = View.INVISIBLE
            }
        }

        override fun onLevel(level: Float) {
            val s = HALO_REST + level * (HALO_MAX - HALO_REST)
            halo.animate().scaleX(s).scaleY(s).alpha(0.6f + 0.4f * level).setDuration(90).start()
        }

        override fun onPartial(text: String) = field.setText(text)

        override fun onResult(text: String) {
            field.setText(text)
            field.setSelection(text.length)
            // A moment to see what was heard before it opens.
            handler.postDelayed({ if (!isFinishing) submit(text) }, 400)
        }

        override fun onFailed(message: Int) {
            field.hint = getString(message)
            micButton.requestFocus()
        }
    }

    /** Fallback: the system voice screen (used when Sofa can't run recognition itself). */
    private fun systemVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
            showKeyboard()
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
    }

    @Deprecated("Framework activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE) return
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && !spoken.isNullOrBlank()) submit(spoken) else showKeyboard()
    }

    override fun onStop() {
        voiceInput.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        voiceInput.destroy()
        super.onDestroy()
    }

    // BACK while listening only stops listening (TV remotes send BACK as a key; see the manifest).
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && voiceInput.listening) {
            voiceInput.cancel()
            return true
        }
        // Down from the field goes to the suggestions even when the IME is closed.
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && field.hasFocus() && list.childCount > 0) {
            list.getChildAt(0).requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_CURRENT_URL = "current"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_PRIVATE = "private"
        private const val REQ_VOICE = 1
        private const val REQ_MIC = 2
        private const val HALO_REST = 1.15f
        private const val HALO_MAX = 1.5f
    }
}
