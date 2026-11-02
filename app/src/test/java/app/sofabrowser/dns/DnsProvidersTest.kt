package app.sofabrowser.dns

import app.sofabrowser.dns.DnsProviders.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProvidersTest {

    @Test
    fun providersAreRankedFromMostToLeastPrivate() {
        // The ranking is deliberate: tiers in this order, and providers in this order inside each tier.
        val expected = linkedMapOf(
            Tier.PRIVATE to listOf("Quad9", "UncensoredDNS", "HaGeZi DNS", "dnsforge.de"),
            Tier.TRADE_OFFS to listOf("Cloudflare", "DNS.SB", "AdGuard DNS", "CleanBrowsing"),
            Tier.LIMITED to listOf("Bancuh", "Tiarap", "Control D"),
            Tier.UTILITY to listOf("Google Public DNS", "DNS4EU", "OpenDNS", "DNS for Family"),
        )
        assertEquals(expected.keys.toList(), DnsProviders.ALL.map { it.tier }.distinct())
        expected.forEach { (tier, names) ->
            val inTier = DnsProviders.ALL.filter { it.tier == tier }.map { p -> names.first { p.name.startsWith(it) } }.distinct()
            assertEquals(names, inTier)
        }
    }

    @Test
    fun everyChoiceHasAUniqueIdAndHttpsEndpointsWithLiteralBootstrapAddresses() {
        assertEquals(DnsProviders.ALL.size, DnsProviders.ALL.map { it.id }.toSet().size)
        DnsProviders.ALL.forEach { p ->
            assertTrue(p.id, !DnsProviders.isCustom(p.id) && p.endpoints.isNotEmpty())
            p.endpoints.forEach { e ->
                assertTrue(e.url, e.url.startsWith("https://") && e.bootstrap.isNotEmpty())
                e.bootstrap.forEach { assertTrue("${p.id} $it", Resolver.isIpLiteral(it)) }
            }
        }
    }
}
