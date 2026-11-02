package app.sofabrowser.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

class ResolverTest {

    private val v4 = InetAddress.getByName("192.0.2.1")
    private val v6 = InetAddress.getByName("2001:db8::1")
    private val device = InetAddress.getByName("198.51.100.7")
    private var now = 1_000_000L

    /** A server that counts its A queries and answers with [answer] (or throws). */
    private class Fake(var answer: () -> DnsMessage.Answer) : Resolver.Server {
        var asked = 0
        override fun query(host: String, type: Int): DnsMessage.Answer {
            if (type == DnsMessage.A) asked++
            return answer()
        }
    }

    private fun ok(vararg a: InetAddress, ttl: Int = 300) = DnsMessage.Answer(DnsMessage.NOERROR, a.toList(), ttl)
    private val down: () -> DnsMessage.Answer = { throw IOException("unreachable") }
    private var deviceAsked = 0
    private fun resolver(vararg servers: Resolver.Server) =
        Resolver(servers.toList(), system = { deviceAsked++; listOf(device) }, clock = { now })

    @Test
    fun theMainDnsAnswersAndIpv4ComesFirst() {
        val main = object : Resolver.Server {
            override fun query(host: String, type: Int) = if (type == DnsMessage.A) ok(v4) else ok(v6)
        }
        assertEquals(listOf(v4, v6), resolver(main).lookup("example.com"))
        assertEquals(0, deviceAsked)
    }

    @Test
    fun aDeadMainFallsBackAndIsSkippedForAMinute() {
        val main = Fake(down)
        val fallback = Fake { ok(v4) }
        val r = resolver(main, fallback)
        assertEquals(listOf(v4), r.lookup("one.example"))
        assertEquals(listOf(v4), r.lookup("two.example"))
        assertEquals(1, main.asked) // not retried on every lookup
        now += Resolver.DEAD_MS + 1
        r.lookup("three.example")
        assertEquals(2, main.asked)
    }

    @Test
    fun theDeviceDnsIsTheLastStep() {
        assertEquals(listOf(device), resolver(Fake(down), Fake(down)).lookup("example.com"))
        assertEquals(1, deviceAsked)
    }

    @Test
    fun aServerFailureCountsAsDownButNoSuchSiteDoesNot() {
        val main = Fake { DnsMessage.Answer(DnsMessage.SERVFAIL, emptyList(), 0) }
        val fallback = Fake { DnsMessage.Answer(DnsMessage.NXDOMAIN, emptyList(), 0) }
        val r = resolver(main, fallback)
        try {
            r.lookup("nothing.example")
            fail()
        } catch (_: UnknownHostException) {
        }
        assertEquals(1, fallback.asked)
        assertEquals(0, deviceAsked) // NXDOMAIN is the answer: never asked again elsewhere
    }

    @Test
    fun answersAreCachedForTheirTtlWithinBounds() {
        val main = Fake { ok(v4, ttl = 5) } // raised to the 60 s floor
        val r = resolver(main)
        r.lookup("example.com")
        r.lookup("EXAMPLE.com.")
        assertEquals(1, main.asked)
        now += 61_000
        r.lookup("example.com")
        assertEquals(2, main.asked)
    }

    @Test
    fun localNamesAndAddressesNeverReachTheChosenDns() {
        val main = Fake { ok(v4) }
        val r = resolver(main)
        r.lookup("nas")
        r.lookup("printer.local")
        r.lookup("router.home.arpa")
        assertEquals(InetAddress.getByName("10.0.0.2"), r.lookup("10.0.0.2").single())
        assertEquals(InetAddress.getByName("::1"), r.lookup("[::1]").single())
        assertEquals(0, main.asked)
        assertEquals(3, deviceAsked)
    }

    @Test
    fun clearForgetsCacheAndDeadServers() {
        val main = Fake(down)
        val r = resolver(main, Fake { ok(v4) })
        r.lookup("example.com")
        main.answer = { ok(v6) }
        r.clear()
        assertTrue(v6 in r.lookup("example.com"))
    }
}
