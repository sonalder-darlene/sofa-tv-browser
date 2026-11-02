package app.sofabrowser.dns

import app.sofabrowser.R

/** A built-in DNS over HTTPS choice: one variant of one provider ([variant] says what it filters). */
class DnsProvider(
    val id: String,
    val name: String,
    /** What this variant blocks, or that it blocks nothing (string resource). */
    val variant: Int,
    /** Who runs it and what it keeps, in a few words (string resource). */
    val about: Int,
    val tier: DnsProviders.Tier,
    /** Tried in order; each has its own bootstrap addresses. */
    val endpoints: List<DohEndpoint>,
)

/**
 * The built-in choices for Custom DNS, in four privacy tiers ranked from most to least private, and in that rank order
 * inside each tier too. Addresses and bootstrap IPs were checked against each provider's own pages and answered over
 * DoH in November 2026; the "about" lines summarise each one's privacy policy as published then. Re-check both before
 * changing anything here.
 */
object DnsProviders {

    enum class Tier(val label: Int, val color: Int) {
        PRIVATE(R.string.dns_tier_private, 0xFF35B26F.toInt()),
        TRADE_OFFS(R.string.dns_tier_trade_offs, 0xFFF2A33A.toInt()),
        LIMITED(R.string.dns_tier_limited, 0xFFE8702A.toInt()),
        UTILITY(R.string.dns_tier_utility, 0xFFE5484D.toInt()),
    }

    private fun doh(url: String, vararg ips: String) = DohEndpoint(url, ips.toList())

    val ALL: List<DnsProvider> = listOf(
        // Private
        DnsProvider("quad9", "Quad9", R.string.dns_blocks_malware, R.string.dns_about_quad9, Tier.PRIVATE, listOf(
            doh("https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"))),
        DnsProvider("quad9-unfiltered", "Quad9", R.string.dns_unfiltered, R.string.dns_about_quad9, Tier.PRIVATE, listOf(
            doh("https://dns10.quad9.net/dns-query", "9.9.9.10", "149.112.112.10", "2620:fe::10", "2620:fe::fe:10"))),
        DnsProvider("uncensoreddns", "UncensoredDNS", R.string.dns_unfiltered, R.string.dns_about_uncensoreddns, Tier.PRIVATE, listOf(
            doh("https://anycast.uncensoreddns.org/dns-query", "91.239.100.100", "2001:67c:28a4::"),
            doh("https://unicast.uncensoreddns.org/dns-query", "89.233.43.71", "2a01:3a0:53:53::"))),
        DnsProvider("hagezi", "HaGeZi DNS", R.string.dns_blocks_ads_malware, R.string.dns_about_hagezi, Tier.PRIVATE, listOf(
            doh("https://root.hagezi.org/dns-query", "188.34.161.210", "2a01:4f8:c17:1c66::1"),
            doh("https://wurzn.hagezi.org/dns-query", "159.69.155.94", "2a01:4f8:1c1c:d363::1"),
            doh("https://juuri.hagezi.org/dns-query", "95.217.163.17", "2a01:4f9:c013:dc4e::1"))),
        DnsProvider("hagezi-threats", "HaGeZi DNS", R.string.dns_blocks_malware, R.string.dns_about_hagezi, Tier.PRIVATE, listOf(
            doh("https://ctif.hagezi.org/dns-query", "162.55.58.40", "2a01:4f8:1c19:6c19::1"))),
        DnsProvider("dnsforge", "dnsforge.de", R.string.dns_blocks_ads_malware, R.string.dns_about_dnsforge, Tier.PRIVATE, listOf(
            doh("https://dnsforge.de/dns-query", "49.12.67.122", "91.99.154.175", "176.9.93.198", "176.9.1.117",
                "2a01:4f8:c013:29d::122", "2a01:4f8:c010:8c35::175"))),
        DnsProvider("dnsforge-family", "dnsforge.de", R.string.dns_blocks_family_safesearch, R.string.dns_about_dnsforge, Tier.PRIVATE, listOf(
            doh("https://clean.dnsforge.de/dns-query", "49.12.223.2", "49.12.43.208", "2a01:4f8:c17:4fbc::2", "2a01:4f8:c012:ed89::208"))),
        DnsProvider("dnsforge-unfiltered", "dnsforge.de", R.string.dns_unfiltered, R.string.dns_about_dnsforge, Tier.PRIVATE, listOf(
            doh("https://blank.dnsforge.de/dns-query", "138.199.149.249", "78.47.71.194", "2a01:4f8:c17:7aa5::249", "2a01:4f8:c013:aae9::194"))),

        // Private with trade-offs
        DnsProvider("cloudflare", "Cloudflare 1.1.1.1", R.string.dns_unfiltered, R.string.dns_about_cloudflare, Tier.TRADE_OFFS, listOf(
            doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"))),
        DnsProvider("cloudflare-malware", "Cloudflare 1.1.1.2", R.string.dns_blocks_malware, R.string.dns_about_cloudflare, Tier.TRADE_OFFS, listOf(
            doh("https://security.cloudflare-dns.com/dns-query", "1.1.1.2", "1.0.0.2", "2606:4700:4700::1112", "2606:4700:4700::1002"))),
        DnsProvider("cloudflare-family", "Cloudflare 1.1.1.3", R.string.dns_blocks_malware_adult, R.string.dns_about_cloudflare, Tier.TRADE_OFFS, listOf(
            doh("https://family.cloudflare-dns.com/dns-query", "1.1.1.3", "1.0.0.3", "2606:4700:4700::1113", "2606:4700:4700::1003"))),
        DnsProvider("dnssb", "DNS.SB", R.string.dns_unfiltered, R.string.dns_about_dnssb, Tier.TRADE_OFFS, listOf(
            doh("https://doh.sb/dns-query", "185.222.222.222", "45.11.45.11", "2a09::", "2a11::"))),
        DnsProvider("adguard", "AdGuard DNS", R.string.dns_blocks_ads, R.string.dns_about_adguard, Tier.TRADE_OFFS, listOf(
            doh("https://dns.adguard-dns.com/dns-query", "94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"))),
        DnsProvider("adguard-unfiltered", "AdGuard DNS", R.string.dns_unfiltered, R.string.dns_about_adguard, Tier.TRADE_OFFS, listOf(
            doh("https://unfiltered.adguard-dns.com/dns-query", "94.140.14.140", "94.140.14.141", "2a10:50c0::1:ff", "2a10:50c0::2:ff"))),
        // CleanBrowsing's DoH host has no IPv6 address, so its bootstrap is IPv4 only.
        DnsProvider("cleanbrowsing", "CleanBrowsing", R.string.dns_blocks_malware, R.string.dns_about_cleanbrowsing, Tier.TRADE_OFFS, listOf(
            doh("https://doh.cleanbrowsing.org/doh/security-filter/", "185.228.168.9", "185.228.169.9"))),
        DnsProvider("cleanbrowsing-family", "CleanBrowsing", R.string.dns_blocks_family_cleanbrowsing, R.string.dns_about_cleanbrowsing, Tier.TRADE_OFFS, listOf(
            doh("https://doh.cleanbrowsing.org/doh/family-filter/", "185.228.168.168", "185.228.169.168"))),

        // Limited privacy
        // Bancuh has no shared host name: one server per name, all with the same filter.
        DnsProvider("bancuh", "Bancuh", R.string.dns_blocks_family_safesearch, R.string.dns_about_bancuh, Tier.LIMITED, listOf(
            doh("https://fr-dns1.bancuh.com/dns-query", "51.158.99.7", "2001:bc8:710:1efa:dc00:ff:fe34:a339"),
            doh("https://fr-dns2.bancuh.com/dns-query", "163.172.180.231", "2001:bc8:710:1efb:dc00:ff:fe34:a1d7"),
            doh("https://us-dns1.bancuh.com/dns-query", "45.33.24.129", "2600:3c00::f03c:94ff:fe97:f94f"),
            doh("https://sg-dns1.bancuh.com/dns-query", "139.59.219.245", "2400:6180:0:d0::f7:1001"),
            doh("https://jp-dns1.bancuh.com/dns-query", "103.29.68.118", "2400:8902::f03c:92ff:fe3e:46cd"))),
        DnsProvider("tiarap", "Tiarap", R.string.dns_blocks_ads_malware, R.string.dns_about_tiarap, Tier.LIMITED, listOf(
            doh("https://doh.tiar.app/dns-query", "174.138.29.175", "2400:6180:0:d0::5f73:4001"))),
        DnsProvider("controld", "Control D", R.string.dns_unfiltered, R.string.dns_about_controld, Tier.LIMITED, listOf(
            doh("https://freedns.controld.com/p0", "76.76.2.11", "76.76.10.11", "2606:1a40::11"))),
        DnsProvider("controld-malware", "Control D", R.string.dns_blocks_malware, R.string.dns_about_controld, Tier.LIMITED, listOf(
            doh("https://freedns.controld.com/p1", "76.76.2.11", "76.76.10.11", "2606:1a40::11"))),
        DnsProvider("controld-ads", "Control D", R.string.dns_blocks_ads, R.string.dns_about_controld, Tier.LIMITED, listOf(
            doh("https://freedns.controld.com/p2", "76.76.2.11", "76.76.10.11", "2606:1a40::11"))),

        // Utility-first
        DnsProvider("google", "Google Public DNS", R.string.dns_unfiltered, R.string.dns_about_google, Tier.UTILITY, listOf(
            doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"))),
        DnsProvider("dns4eu", "DNS4EU", R.string.dns_blocks_malware, R.string.dns_about_dns4eu, Tier.UTILITY, listOf(
            doh("https://protective.joindns4.eu/dns-query", "86.54.11.1", "86.54.11.201", "2a13:1001::86:54:11:1", "2a13:1001::86:54:11:201"))),
        DnsProvider("dns4eu-ads", "DNS4EU", R.string.dns_blocks_ads_malware, R.string.dns_about_dns4eu, Tier.UTILITY, listOf(
            doh("https://noads.joindns4.eu/dns-query", "86.54.11.13", "86.54.11.213", "2a13:1001::86:54:11:13", "2a13:1001::86:54:11:213"))),
        DnsProvider("dns4eu-unfiltered", "DNS4EU", R.string.dns_unfiltered_court, R.string.dns_about_dns4eu, Tier.UTILITY, listOf(
            doh("https://unfiltered.joindns4.eu/dns-query", "86.54.11.100", "86.54.11.200", "2a13:1001::86:54:11:100", "2a13:1001::86:54:11:200"))),
        DnsProvider("opendns", "OpenDNS", R.string.dns_blocks_phishing, R.string.dns_about_opendns, Tier.UTILITY, listOf(
            doh("https://doh.opendns.com/dns-query", "146.112.41.2", "208.67.222.222", "208.67.220.220", "2620:119:fc::2"))),
        DnsProvider("opendns-family", "OpenDNS FamilyShield", R.string.dns_blocks_adult, R.string.dns_about_opendns, Tier.UTILITY, listOf(
            doh("https://doh.familyshield.opendns.com/dns-query", "146.112.41.3", "208.67.222.123", "208.67.220.123", "2620:119:fc::3"))),
        // One server behind all of DNS for Family's names; no IPv6.
        DnsProvider("dnsforfamily", "DNS for Family", R.string.dns_blocks_family_safesearch, R.string.dns_about_dnsforfamily, Tier.UTILITY, listOf(
            doh("https://dns-doh.dnsforfamily.com/dns-query", "167.235.236.107"))),
        DnsProvider("dnsforfamily-no-safesearch", "DNS for Family", R.string.dns_blocks_family, R.string.dns_about_dnsforfamily, Tier.UTILITY, listOf(
            doh("https://dns-doh-no-safe-search.dnsforfamily.com/dns-query", "167.235.236.107"))),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    /** A DoH address typed by the user, stored as itself. */
    fun isCustom(choice: String) = choice.startsWith("https://")
}
