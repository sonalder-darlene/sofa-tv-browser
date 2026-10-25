package app.sofabrowser.adblock

/**
 * Which site a host belongs to ("www.bbc.co.uk" is on "bbc.co.uk"), for third-party rules and for filters written
 * for "example.*". Browsers use the Public Suffix List; it is over 200 KB, so Sofa uses a rule of thumb instead:
 * a country domain under a common second level ("co.uk", "com.au") and a few shared hosting domains take one more
 * label. A rare miss only makes a third-party rule apply where it shouldn't, or not apply where it should.
 */
object Sites {

    /** The registrable domain of [host]: its public suffix plus one label. IP addresses are their own site. */
    fun of(host: String): String {
        if (isIp(host)) return host
        val suffix = suffixOf(host) ?: return host
        val rest = host.length - suffix.length - 1
        if (rest <= 0) return host
        val dot = host.lastIndexOf('.', rest - 1)
        return host.substring(dot + 1)
    }

    /** The public suffix of [host], such as "com" or "co.uk"; null for a host without dots or an IP address. */
    fun suffixOf(host: String): String? {
        if (isIp(host)) return null
        val last = host.lastIndexOf('.')
        if (last <= 0) return null
        val tld = host.substring(last + 1)
        val before = host.lastIndexOf('.', last - 1)
        val two = host.substring(before + 1)
        if (two in SHARED_HOSTING) return two
        // "bbc.co.uk" is on "co.uk", but "co.uk" itself is on "uk".
        if (tld.length == 2 && before > 0 && host.substring(before + 1, last) in COUNTRY_SECOND_LEVEL) return two
        return tld
    }

    /** The host without its public suffix: "www.example.co.uk" gives "www.example", for "example.*" rules. */
    fun withoutSuffix(host: String): String? {
        val suffix = suffixOf(host) ?: return null
        return host.substring(0, host.length - suffix.length - 1).ifEmpty { null }
    }

    /** True when both hosts belong to the same site. */
    fun same(a: String, b: String) = a == b || of(a) == of(b)

    fun isIp(host: String) = ':' in host || host.isNotEmpty() && host.last().isDigit() && host.all { it.isDigit() || it == '.' }

    private val COUNTRY_SECOND_LEVEL = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "gouv", "nic", "mil", "ltd", "plc",
        "sch", "nom", "info", "biz", "web", "res", "nhs", "police", "in", "lg", "ed", "gr", "mi", "pp", "ind",
    )

    private val SHARED_HOSTING = setOf(
        "github.io", "gitlab.io", "blogspot.com", "appspot.com", "herokuapp.com", "netlify.app", "vercel.app",
        "pages.dev", "workers.dev", "web.app", "firebaseapp.com", "azurewebsites.net", "cloudfront.net",
        "wordpress.com", "tumblr.com", "glitch.me", "neocities.org", "surge.sh", "fly.dev", "onrender.com",
    )
}
