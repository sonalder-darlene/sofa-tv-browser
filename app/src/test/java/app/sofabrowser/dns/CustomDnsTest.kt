package app.sofabrowser.dns

import app.sofabrowser.dns.CustomDns.Kind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CustomDnsTest {

    private fun kindOf(text: String) = CustomDns.parse(text)?.kind

    @Test
    fun theThreeFormsAreToldApartByTheirShape() {
        assertEquals(Kind.HTTPS, kindOf("https://dns.example/dns-query"))
        assertEquals(Kind.TLS, kindOf("tls://dns.example"))
        assertEquals(Kind.PLAIN, kindOf("192.0.2.53"))
        assertEquals(Kind.PLAIN, kindOf("2001:db8::53"))
        assertEquals(Kind.PLAIN, kindOf("[2001:db8::53]:5353"))
    }

    @Test
    fun providerIdsAndEmptyAreNotAddresses() {
        DnsProviders.ALL.forEach { assertNull(it.id, CustomDns.parse(it.id)) }
        assertNull(CustomDns.parse(""))
        assertFalse(DnsProviders.isCustom("quad9"))
        assertTrue(DnsProviders.isCustom("tls://dns.example"))
        assertTrue(DnsProviders.isCustom("192.0.2.53"))
    }

    @Test
    fun dnsOverTlsUsesPort853UnlessGiven() {
        val t = CustomDns.parse("tls://DNS.Example") as CustomDns.Tls
        assertEquals("dns.example", t.host)
        assertEquals(853, t.port)
        assertEquals("tls://dns.example", t.text)
        assertEquals("dns.example", t.name)
        val other = CustomDns.parse("tls://dns.example:8853/") as CustomDns.Tls
        assertEquals(8853, other.port)
        assertEquals("tls://dns.example:8853", other.text)
        assertEquals("tls://dns.example", CustomDns.parse("tls://dns.example:853")!!.text)
        assertEquals("[2001:db8::853]", (CustomDns.parse("tls://[2001:db8::853]") as CustomDns.Tls).host)
    }

    @Test
    fun dnsOverTlsRefusesAPathQueryOrUser() {
        listOf("tls://", "tls://dns.example/dns-query", "tls://dns.example?x", "tls://me@dns.example", "tls://dns.example:0",
            "tls://dns.example:70000").forEach { assertNull(it, CustomDns.parse(it)) }
    }

    @Test
    fun plainDnsUsesPort53UnlessGiven() {
        val p = CustomDns.parse("192.0.2.53") as CustomDns.Plain
        assertEquals(InetAddress.getByName("192.0.2.53"), p.address)
        assertEquals(53, p.port)
        assertEquals("192.0.2.53", p.text)
        assertEquals("192.0.2.53:5353", CustomDns.parse("192.0.2.53:5353")!!.text)
        assertEquals("192.0.2.53", CustomDns.parse("192.0.2.53:53")!!.text)
        assertEquals("2001:db8::53", CustomDns.parse("[2001:DB8::53]")!!.text)
        assertEquals("[2001:db8::53]:5353", CustomDns.parse("[2001:db8::53]:5353")!!.text)
        assertEquals(5353, (CustomDns.parse("[2001:db8::53]:5353") as CustomDns.Plain).port)
    }

    @Test
    fun plainDnsRefusesWhatIsNotAnIpAddress() {
        listOf("256.1.1.1", "1.2.3", "1.2.3.4.5", "01234.1.1.1", "1.2.3.4:", "1.2.3.4:x", "1.2.3.4:65536", "dns.example",
            "dns.example:53", "0.0.0.0", "::", "224.0.0.251", "fe80::1%wlan0", "1:2:3:4:5:6:7", "1::2::3", "[1.2.3.4]",
            "[2001:db8::53]x", "2001:db8::53 ", "12345::1", "::1.2.3.4::").forEach { assertNull(it, CustomDns.parse(it)) }
    }

    @Test
    fun ipv6AddressesParseInEveryShortForm() {
        fun v6(s: String) = CustomDns.ipv6(s)?.let { InetAddress.getByAddress(it) }
        assertEquals(InetAddress.getByName("2620:fe::fe"), v6("2620:fe::fe"))
        assertEquals(InetAddress.getByName("::1"), v6("::1"))
        assertEquals(InetAddress.getByName("1:2:3:4:5:6:7:8"), v6("1:2:3:4:5:6:7:8"))
        assertEquals(InetAddress.getByName("1::"), v6("1::"))
        assertEquals(InetAddress.getByName("64:ff9b::c000:221"), v6("64:ff9b::192.0.2.33"))
        assertEquals(InetAddress.getByName("1:2:3:4:5:6:c000:221"), v6("1:2:3:4:5:6:192.0.2.33"))
        assertArrayEquals(ByteArray(16).also { it[15] = 1 }, CustomDns.ipv6("0:0:0:0:0:0:0:1"))
        listOf("", ":", "1:2", "1:2:3:4:5:6:7:8:9", "1::2:3:4:5:6:7:8", "g::1", "1.2.3.4::").forEach { assertNull(it, CustomDns.ipv6(it)) }
    }

    @Test
    fun anAddressTypedInIsTidied() {
        assertEquals("https://dns.example/dns-query", CustomDns.fromTyped("  dns.example/dns-query ")!!.text)
        assertEquals("https://dns.example/dns-query", CustomDns.fromTyped("HTTPS://dns.example/dns-query")!!.text)
        assertEquals("https://192.0.2.1/dns-query", CustomDns.fromTyped("192.0.2.1/dns-query")!!.text)
        assertEquals("tls://dns.example", CustomDns.fromTyped("TLS://dns.example")!!.text)
        assertEquals("192.0.2.53", CustomDns.fromTyped(" 192.0.2.53 ")!!.text)
        // An IP address that isn't one is refused, not sent to some https:// host.
        assertNull(CustomDns.fromTyped("192.0.2.256"))
        assertNull(CustomDns.fromTyped("192.0.2.53:x"))
        assertNull(CustomDns.fromTyped("http://dns.example/dns-query"))
        assertNull(CustomDns.fromTyped("dns example"))
        assertNull(CustomDns.fromTyped(""))
    }

    @Test
    fun aTidiedAddressReadsBackAsItself() {
        listOf("https://dns.example:8443/q?x=1", "tls://dns.example", "tls://dns.example:8853", "192.0.2.53", "192.0.2.53:5353",
            "2001:db8::53", "[2001:db8::53]:5353").forEach { assertEquals(it, CustomDns.parse(it)!!.text) }
    }
}
