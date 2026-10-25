package app.sofabrowser.adblock

import jp.hazuki.yuzubrowser.adblock.core.ContentRequest
import jp.hazuki.yuzubrowser.adblock.core.FilterContainer
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_LISTS
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_ALLOW
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_BADFILTER
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_DENY
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_DISABLE_ELEMENT_PAGE
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_DISABLE_GENERIC_ELEMENT_PAGE
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_DOCUMENT_ALLOW
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_IMPORTANT
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_IMPORTANT_ALLOW
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_POPUP_ALLOW
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_POPUP_DENY
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_DOCUMENT_DENY
import jp.hazuki.yuzubrowser.adblock.filter.unified.FILTER_TYPE_START_END
import jp.hazuki.yuzubrowser.adblock.filter.unified.NO_PARTY_PREFERENCE
import jp.hazuki.yuzubrowser.adblock.filter.unified.STRICT_FIRST_PARTY
import jp.hazuki.yuzubrowser.adblock.filter.unified.FIRST_PARTY
import jp.hazuki.yuzubrowser.adblock.filter.unified.THIRD_PARTY
import jp.hazuki.yuzubrowser.adblock.filter.unified.UnifiedFilter

/**
 * Every enabled list merged into one immutable set of rules, shared by WebView's network threads. A request is
 * blocked the way uBlock Origin decides it: a page-wide @@…$document exception lets everything through; otherwise an
 * $important exception allows, an $important rule blocks, an exception allows, and a domain or network rule blocks.
 * Plain domain rules, most of every list, are 8-byte hashes; only the rest are rule objects.
 */
class RuleSet private constructor(
    /** Blocked domains; also what pop-up and redirect blocking check. */
    val domains: DomainBlocklist,
    /** Domains blocked from other sites only ("||tracker.example^$third-party"). */
    private val thirdPartyDomains: DomainBlocklist,
    private val lists: Map<String, FilterContainer>,
    val cosmetics: CosmeticIndex,
    val rules: Int,
) {
    private val deny = lists.getValue(ABP_PREFIX_DENY)
    private val allow = lists.getValue(ABP_PREFIX_ALLOW)
    private val important = lists.getValue(ABP_PREFIX_IMPORTANT)
    private val importantAllow = lists.getValue(ABP_PREFIX_IMPORTANT_ALLOW)
    private val documentAllow = lists.getValue(ABP_PREFIX_DOCUMENT_ALLOW)
    private val elemhideAllow = lists.getValue(ABP_PREFIX_DISABLE_ELEMENT_PAGE)
    private val generichideAllow = lists.getValue(ABP_PREFIX_DISABLE_GENERIC_ELEMENT_PAGE)
    private val popupDeny = lists.getValue(ABP_PREFIX_POPUP_DENY)
    private val popupAllow = lists.getValue(ABP_PREFIX_POPUP_ALLOW)
    private val documentDeny = lists.getValue(ABP_PREFIX_DOCUMENT_DENY)

    /**
     * Whether a window a page at [openerHost] opens at [url] is blocked: its host is a blocked domain, or a $popup rule
     * matches it and no $popup exception does.
     */
    fun blocksPopup(url: RequestUrl, openerHost: String?): Boolean {
        val host = url.host ?: return false
        val request = ContentRequest(url, openerHost, ContentRequest.TYPE_POPUP, party(host, openerHost))
        if (popupAllow[request] != null) return false
        return domains.isBlocked(host) || popupDeny[request] != null
    }

    /** Whether [url] is a page lists block outright: a blocked domain, or a $document rule without exception. */
    fun blocksPage(url: RequestUrl): Boolean {
        val host = url.host ?: return false
        if (domains.isBlocked(host)) return true
        val request = ContentRequest(url, host, ContentRequest.TYPE_DOCUMENT, STRICT_FIRST_PARTY)
        return documentDeny[request] != null && documentAllow[request] == null
    }

    /**
     * Whether a subresource request for [url] of content [type] (ContentRequest.TYPE_*) is blocked on a page at
     * [pageHost]. Call [pageAllowed] once per page first; this doesn't repeat it.
     */
    fun blocks(url: RequestUrl, type: Int, pageHost: String?): Boolean {
        val host = url.host ?: return false
        val party = party(host, pageHost)
        val request = ContentRequest(url, pageHost, type, party)
        if (importantAllow[request] != null) return false
        if (important[request] != null) return true
        val denied = domains.isBlocked(host) || party == THIRD_PARTY && thirdPartyDomains.isBlocked(host) || deny[request] != null
        if (!denied) return false
        return allow[request] == null
    }

    /** False when a @@…$document exception switches blocking off on the page at [pageUrl]. */
    fun pageAllowed(pageUrl: RequestUrl): Boolean = documentAllow[page(pageUrl)] == null

    /** Element hiding for the page at [pageUrl]: none with $document or $elemhide exceptions, no generic with $generichide. */
    fun cosmeticsFor(pageUrl: RequestUrl): CosmeticIndex.Page? {
        val host = pageUrl.host ?: return null
        val request = page(pageUrl)
        if (documentAllow[request] != null || elemhideAllow[request] != null) return null
        return cosmetics.page(host, generic = generichideAllow[request] == null)
    }

    private fun page(pageUrl: RequestUrl) = ContentRequest(pageUrl, pageUrl.host, ContentRequest.TYPE_ALL, STRICT_FIRST_PARTY)

    /** Loads lists one at a time and merges them; $badfilter rules cancel matching rules from any list. */
    class Builder {
        private val domainLists = ArrayList<DomainBlocklist>()
        private val filters = (ABP_LISTS + ABP_LISTS.map { ABP_PREFIX_BADFILTER + it }).associateWith { ArrayList<Pair<String, UnifiedFilter>>() }
        private val cosmetics = CosmeticIndex.Builder()

        fun add(list: CompiledList) {
            domainLists += list.domains
            list.filters.forEach { (name, f) -> filters[name]?.addAll(f) }
            cosmetics.add(list.cosmetics)
        }

        fun build(): RuleSet {
            val cancelled = HashSet<Long>()
            val cancelledThirdParty = HashSet<Long>()
            val thirdParty = HashSet<Long>()
            val plain = HashSet<Long>()
            val containers = ABP_LISTS.associateWith { name ->
                val bad = filters.getValue(ABP_PREFIX_BADFILTER + name).map { it.second }.toHashSet()
                val deny = name == ABP_PREFIX_DENY
                if (deny) bad.forEach { f ->
                    when (domainRule(f)) {
                        NO_PARTY_PREFERENCE -> cancelled += DomainBlocklist.fnv1a(f.pattern)
                        THIRD_PARTY -> cancelledThirdParty += DomainBlocklist.fnv1a(f.pattern)
                    }
                }
                FilterContainer().apply {
                    // Duplicates across lists (EasyList and uBlock filters share many rules) are kept once.
                    val seen = HashSet<UnifiedFilter>()
                    for ((tag, f) in filters.getValue(name)) {
                        if (f in bad) continue
                        // "||ads.example^" and "||ads.example^$third-party" are hashes too, 8 bytes instead of ~170.
                        when (if (deny) domainRule(f) else null) {
                            NO_PARTY_PREFERENCE -> plain += DomainBlocklist.fnv1a(f.pattern)
                            THIRD_PARTY -> thirdParty += DomainBlocklist.fnv1a(f.pattern)
                            else -> if (seen.add(f)) addWithTag(tag, f)
                        }
                    }
                }
            }
            val domains = DomainBlocklist.mergeAll(domainLists + DomainBlocklist.of(plain)).without(cancelled)
            val thirdPartyDomains = DomainBlocklist.of(thirdParty).without(cancelledThirdParty + cancelled)
            val index = cosmetics.build()
            return RuleSet(domains, thirdPartyDomains, containers, index,
                domains.size + thirdPartyDomains.size + containers.values.sumOf { it.size } + index.size)
        }

        /** NO_PARTY_PREFERENCE or THIRD_PARTY for a whole-domain rule with at most $third-party; null for others. */
        private fun domainRule(f: UnifiedFilter): Int? {
            if (f.filterType != FILTER_TYPE_START_END || f.domains != null || f.contentType != ContentRequest.TYPE_ALL) return null
            if ('/' in f.pattern || '.' !in f.pattern) return null
            return f.thirdParty.takeIf { it == NO_PARTY_PREFERENCE || it == THIRD_PARTY }
        }
    }

    companion object {
        val EMPTY = Builder().build()

        /** STRICT_FIRST_PARTY for the page's own host, FIRST_PARTY for its site, THIRD_PARTY for others. */
        fun party(host: String, pageHost: String?): Int = when {
            pageHost == null -> THIRD_PARTY
            host == pageHost -> STRICT_FIRST_PARTY
            Sites.same(host, pageHost) -> FIRST_PARTY
            else -> THIRD_PARTY
        }
    }
}
