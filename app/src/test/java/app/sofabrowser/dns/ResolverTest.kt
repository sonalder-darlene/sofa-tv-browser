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

    private fun error(rcode: Int) = DnsMessage.Answer(rcode, emptyList(), 0)
    private val refused = 5

    private fun assertFails(r: Resolver, host: String) {
        try {
            r.lookup(host)
            fail()
        } catch (_: UnknownHostException) {
        }
    }

    @Test
    fun anErrorAnswerAsksTheFallbackAndNoSuchSiteIsTheAnswer() {
        val main = Fake { error(DnsMessage.SERVFAIL) }
        val fallback = Fake { error(DnsMessage.NXDOMAIN) }
        val r = resolver(main, fallback)
        assertFails(r, "nothing.example")
        assertEquals(1, fallback.asked)
        assertEquals(0, deviceAsked) // NXDOMAIN is the answer: never asked again elsewhere
    }

    @Test
    fun anErrorAnswerFailsThatNameOnlyAndNeverReachesTheDeviceDns() {
        // A filtering DNS refusing a blocked name, or SERVFAIL for a name with broken DNSSEC.
        for (rcode in listOf(refused, DnsMessage.SERVFAIL)) {
            deviceAsked = 0
            var bad = true
            val main = Fake { if (bad) error(rcode) else ok(v4) }
            val r = resolver(main)
            assertFails(r, "blocked.example")
            assertEquals(0, deviceAsked)
            bad = false
            assertEquals(listOf(v4), r.lookup("other.example")) // the server isn't marked dead
            assertEquals(2, main.asked)
            assertEquals(0, deviceAsked)
        }
    }

    @Test
    fun anErrorAnswerOnTheMainStillTakesTheFallbackForThatName() {
        var bad = true
        val main = Fake { if (bad) error(DnsMessage.SERVFAIL) else ok(v6) }
        val fallback = Fake { ok(v4) }
        val r = resolver(main, fallback)
        assertEquals(listOf(v4), r.lookup("one.example"))
        bad = false
        assertEquals(listOf(v6), r.lookup("two.example")) // the main is asked first again
        assertEquals(2, main.asked)
        assertEquals(1, fallback.asked)
    }

    @Test
    fun aDeadMainAndAnErrorAnswerOnTheFallbackFailTheName() {
        val r = resolver(Fake(down), Fake { error(refused) })
        assertFails(r, "blocked.example")
        assertEquals(0, deviceAsked) // one chosen server answered: its answer stands
    }

    @Test
    fun anErrorAnswerIsRememberedOnlyBriefly() {
        var bad = true
        val main = Fake { if (bad) error(DnsMessage.SERVFAIL) else ok(v4) }
        val r = resolver(main)
        assertFails(r, "flaky.example")
        assertFails(r, "flaky.example") // a page asking again doesn't ask the server again
        assertEquals(1, main.asked)
        bad = false
        now += Resolver.ERROR_TTL_MS + 1
        assertEquals(listOf(v4), r.lookup("flaky.example"))
        assertEquals(2, main.asked)
    }

    @Test
    fun aTimeoutStillCountsAsDown() {
        val main = Fake { throw java.net.SocketTimeoutException("read timed out") }
        val r = resolver(main)
        assertEquals(listOf(device), r.lookup("one.example"))
        assertEquals(listOf(device), r.lookup("two.example"))
        assertEquals(1, main.asked)
        assertEquals(2, deviceAsked)
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

    private var notices = 0
    private var seen = true
    private fun watched(strict: Boolean, vararg servers: Resolver.Server) =
        Resolver(servers.toList(), system = { deviceAsked++; listOf(device) }, clock = { now }, strict = strict,
            onUnreachable = { notices++; seen })

    @Test
    fun strictNeverUsesTheDeviceDnsButStillAsksTheChosenOnes() {
        val main = Fake(down)
        val fallback = Fake(down)
        val r = watched(true, main, fallback)
        assertFails(r, "one.example")
        assertFails(r, "two.example")
        assertEquals(0, deviceAsked)
        assertEquals(1, main.asked)
        main.answer = { ok(v4) }
        now += Resolver.DEAD_MS + 1
        assertEquals(listOf(v4), r.lookup("three.example")) // back once the minute is over
        assertEquals(listOf(device), r.lookup("printer.local")) // home names still use the device's DNS
    }

    @Test
    fun theUserIsToldOncePerOutageAndNotTooOften() {
        val main = Fake(down)
        val r = watched(false, main)
        r.lookup("one.example")
        r.lookup("two.example")
        assertEquals(1, notices) // once, not for every name
        now += Resolver.DEAD_MS + 1
        main.answer = { ok(v4) }
        r.lookup("three.example") // answers again: the outage is over
        main.answer = down
        now += Resolver.DEAD_MS + 1
        r.lookup("four.example")
        assertEquals(1, notices) // a new outage, but within five minutes of the last notice
        now += Resolver.NOTICE_GAP_MS
        main.answer = { ok(v4) }
        r.lookup("five.example")
        main.answer = down
        r.lookup("six.example")
        assertEquals(2, notices)
    }

    @Test
    fun aNoticeNobodySawIsGivenAgain() {
        seen = false // Sofa wasn't on screen (a list update in the background)
        val r = watched(false, Fake(down))
        r.lookup("one.example")
        seen = true
        r.lookup("two.example")
        r.lookup("three.example")
        assertEquals(2, notices)
    }

    @Test
    fun anErrorAnswerIsNoOutage() {
        watched(true, Fake { error(refused) }).let { assertFails(it, "blocked.example") }
        assertEquals(0, notices)
    }
}
