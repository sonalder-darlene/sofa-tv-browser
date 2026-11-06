package app.sofabrowser.screen

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import app.sofabrowser.BuildConfig
import app.sofabrowser.Prefs
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.TabList
import java.io.File

/**
 * One tab: its page ([browser]) and what Sofa keeps of it while its WebView is dropped (unloaded to save memory, or
 * not yet loaded since a restart): the address, the title and, in memory only, WebView's own back and forward list.
 */
class Tab(private: Boolean, newBrowser: (Tab, Boolean) -> Browser) {

    val browser = newBrowser(this, private)
    val private get() = browser.privateMode
    var loading = false
    /** The page's zoom, in steps of 25 % from where it loaded. */
    var zoomStep = 0

    private var keptAddress: String? = null
    private var keptTitle: String? = null
    private var keptHistory: Bundle? = null

    /** The page's WebView exists. */
    val live get() = browser.web != null
    /** The page's address, loaded or not; null for a tab that shows only the start page. */
    val address: String? get() = browser.web?.url?.takeIf { it.isNotEmpty() } ?: keptAddress
    val title: String? get() = (if (live) browser.web?.title else keptTitle)?.takeIf { it.isNotBlank() }

    /** A tab that comes back from the saved list: its address only, loaded when chosen. */
    fun keep(address: String) {
        keptAddress = address
    }

    /**
     * Unloads the page, keeping its address, title and history (never written anywhere). [history]: false after the
     * page's renderer went away, when there's nothing sound to keep but the address.
     */
    fun drop(history: Boolean = true) {
        val w = browser.web ?: return
        keptAddress = w.url?.takeIf { it.isNotEmpty() } ?: keptAddress
        keptTitle = w.title
        keptHistory = if (history) Bundle().takeIf { w.saveState(it) != null } else null
        browser.destroy()
        loading = false
    }

    /** Loads the kept page into [view], a WebView just made for this tab: false when its history couldn't come back. */
    fun bringBack(view: WebView): Boolean {
        val history = keptHistory
        forget()
        return history != null && view.restoreState(history) != null
    }

    /** The kept page goes: another address takes its place, or the tab closes. */
    fun forget() {
        keptAddress = null
        keptTitle = null
        keptHistory = null
    }

    fun close() {
        browser.destroy()
        forget()
    }
}

/**
 * The tabs of both sides: normal browsing, and private browsing, kept apart. Only one side shows at a time; while the
 * private side does, the normal tabs are unloaded and wait. Only the tab in sight runs: the others rest (see
 * MainActivity), and past [liveBackground] of them, or when Android runs short of memory, their pages are unloaded.
 */
class Tabs(private val context: Context, private val prefs: Prefs, private val newBrowser: (Tab, Boolean) -> Browser) {

    private val normal = TabList<Tab>(MAX)
    private var privateSide: TabList<Tab>? = null

    /** The side in sight is private browsing. */
    val privateShown get() = privateSide != null
    private val side get() = privateSide ?: normal

    /** The tabs of the side in sight, in the strip's order. */
    val list: List<Tab> get() = side.items
    val full get() = side.full
    /** The tab in sight, or behind the start page. A side is never empty. */
    val current: Tab get() = side.current ?: newTab().also { side.add(it) }

    /** Pages out of sight that stay loaded: from the TV's memory, or a test build's `files/live-tabs`. */
    val liveBackground: Int by lazy {
        if (BuildConfig.DEBUG) File(context.filesDir, "live-tabs").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.let { return@lazy it }
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        TabList.liveBackground(info.totalMem, am?.isLowRamDevice == true)
    }

    private fun newTab() = Tab(privateShown, newBrowser)

    fun isCurrent(tab: Tab) = side.current === tab

    /** A loaded page's WebView, any of them: for what WebView does app-wide (its timers). */
    val anyWeb: WebView? get() = side.items.firstNotNullOfOrNull { it.browser.web }

    /** A new empty tab after [after] (at the end when null), not yet in sight; null when the side is full. */
    fun add(after: Tab? = null): Tab? = newTab().takeIf { side.add(it, after) }

    fun select(tab: Tab) = side.select(tab)

    /** Closes [tab]; the side's tab in sight is then the one seen before it, or a new empty one. */
    fun close(tab: Tab) {
        tab.close()
        side.remove(tab)
    }

    /** Unloads the pages out of sight past what the TV keeps; [all]: every one of them (Android is short of memory). */
    fun trim(all: Boolean = false) {
        side.beyond(if (all) 0 else liveBackground) { it.live }.forEach { it.drop() }
    }

    /** Private browsing starts with one empty tab; the normal tabs are unloaded until it ends. */
    fun enterPrivate() {
        if (privateShown) return
        normal.items.forEach { it.drop() }
        privateSide = TabList(MAX)
    }

    /** Private browsing ends: its tabs close, with everything in them. The private profile is the caller's to delete. */
    fun leavePrivate() {
        val p = privateSide ?: return
        p.items.forEach { it.close() }
        privateSide = null
    }

    /** Every page's WebView goes (Sofa closes, or its screen is rebuilt); the normal tabs' addresses stay. */
    fun destroyAll() {
        leavePrivate()
        normal.items.forEach { it.drop(history = false) }
    }

    /** The normal tabs, as Settings keeps them (`tabs`). */
    fun saved(): String {
        val tabs = normal.items
        return TabList.encode(tabs.map { it.address.orEmpty() }, tabs.indexOf(normal.current))
    }

    /** Brings back the normal tabs [saved] wrote, unloaded; nothing when there are tabs already. */
    fun restore(saved: String) {
        if (normal.size > 0) return
        val (addresses, at) = TabList.decode(saved, MAX)
        val tabs = addresses.map { a -> newTab().also { it.keep(a); normal.add(it) } }
        tabs.getOrNull(at)?.let(normal::select)
    }

    companion object {
        /** Tabs on one side. Few enough to tell apart in the strip; unloaded ones cost almost nothing. */
        const val MAX = 10
    }
}
