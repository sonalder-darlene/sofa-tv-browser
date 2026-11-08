package app.sofabrowser.dns

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Looks host names up through [servers] in order (the main DNS, then the fallback), then through the device's own
 * DNS only when none of them could be asked: unreachable, timed out, or answering with something that isn't a DNS
 * answer. Such a server is skipped for [deadMs]. A DNS error answer (SERVFAIL, REFUSED…) is that server's answer for
 * that one name: the next chosen server is asked, and if none has an address the name fails for [ERROR_TTL_MS]. It
 * never goes to the device's DNS, so one broken or blocked name can't switch a filtering DNS off for every site.
 * "No such site" (NXDOMAIN) is a real answer and stops there. Answers are cached by their TTL, within bounds. Local
 * names (no dot, `.local`, `.lan`, `.home.arpa`…) and IP literals never leave the device. Thread-safe; host names are
 * never logged.
 *
 * With [strict], a name no chosen server could be asked about fails instead of going to the device's DNS. Either way,
 * [onUnreachable] is told when that starts (it returns whether the user saw it): once until a chosen server answers
 * again, and at most every [NOTICE_GAP_MS].
 */
class Resolver(
    private val servers: List<Server>,
    private val system: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val deadMs: Long = DEAD_MS,
    private val strict: Boolean = false,
    private val onUnreachable: (() -> Boolean)? = null,
) {
    /** One DoH server: anything that answers one A or AAAA query. */
    fun interface Server {
        fun query(host: String, type: Int): DnsMessage.Answer
    }

    private class Entry(val addresses: List<InetAddress>, val expires: Long)

    private val cache = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_CACHE
    }
    private val inFlight = ConcurrentHashMap<String, FutureTask<List<InetAddress>>>()
    private val deadUntil = LongArray(servers.size)
    /** The user was told the chosen servers can't be reached, and none has answered since. */
    @Volatile private var told = false
    private var lastTold: Long? = null

    /** The addresses for [host], IPv4 first; throws UnknownHostException when it doesn't exist or nothing answered. */
    fun lookup(host: String): List<InetAddress> {
        val name = host.trimEnd('.').lowercase()
        if (isIpLiteral(name)) return listOf(InetAddress.getByName(name.removePrefix("[").removeSuffix("]")))
        if (isLocal(name)) return system(name)
        synchronized(cache) { cache[name]?.takeIf { it.expires > clock() } }?.let { return it.addresses.ifEmpty { throw UnknownHostException(host) } }
        // One lookup per name at a time: a page asks for the same host many times while it loads.
        val task = FutureTask { resolve(name) }
        val running = inFlight.putIfAbsent(name, task)
        if (running == null) try { task.run() } finally { inFlight.remove(name, task) }
        return try { (running ?: task).get() } catch (e: ExecutionException) {
            throw (e.cause as? UnknownHostException) ?: UnknownHostException(host).apply { initCause(e.cause) }
        }
    }

    /** Forgets cached answers and failed servers (after the choice of DNS or the network changes). */
    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(deadUntil) { deadUntil.fill(0) }
    }

    private fun resolve(name: String): List<InetAddress> {
        var errorAnswer = false
        servers.forEachIndexed { i, server ->
            if (synchronized(deadUntil) { deadUntil[i] > clock() }) return@forEachIndexed
            val answer = try { ask(server, name) } catch (_: IOException) {
                synchronized(deadUntil) { deadUntil[i] = clock() + deadMs }
                return@forEachIndexed
            }
            told = false // reachable again: a later outage is news
            if (answer == null) { errorAnswer = true; return@forEachIndexed }
            remember(name, answer.first, answer.second)
            return answer.first.ifEmpty { throw UnknownHostException(name) }
        }
        if (errorAnswer) {
            // A chosen server answered, with an error for this name: the name fails for a moment and stays off the
            // device's DNS (a filter's REFUSED would be bypassed there, and the name would leak).
            synchronized(cache) { cache[name] = Entry(emptyList(), clock() + ERROR_TTL_MS) }
            throw UnknownHostException(name)
        }
        // No chosen server could be asked: the device's DNS, so browsing doesn't stop (not cached: it has its own
        // cache), unless the user chose never to use it.
        tellUnreachable()
        if (strict) throw UnknownHostException(name)
        return system(name)
    }

    private fun tellUnreachable() {
        val tell = onUnreachable ?: return
        synchronized(this) {
            val now = clock()
            if (told || lastTold?.let { now - it < NOTICE_GAP_MS } == true) return
            if (tell()) { told = true; lastTold = now }
        }
    }

    /**
     * A and AAAA at once; the A query decides. Returns the addresses and TTL, or null when the server answered with a
     * DNS error for this name; throws IOException when the server couldn't be asked.
     */
    private fun ask(server: Server, name: String): Pair<List<InetAddress>, Int>? {
        val v6 = FutureTask { server.query(name, DnsMessage.AAAA) }
        pool.execute(v6)
        val v4 = try { server.query(name, DnsMessage.A) } catch (e: Exception) { v6.cancel(true); throw e }
        if (v4.rcode != DnsMessage.NOERROR && v4.rcode != DnsMessage.NXDOMAIN) { v6.cancel(true); return null }
        val six = try { v6.get(DohClient.READ_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS).takeIf { it.rcode == DnsMessage.NOERROR } }
        catch (_: Exception) { v6.cancel(true); null }
        if (v4.rcode == DnsMessage.NXDOMAIN) return emptyList<InetAddress>() to NEGATIVE_TTL_S
        val all = (v4.addresses + (six?.addresses ?: emptyList())).distinct()
        val ttl = listOfNotNull(v4.ttl.takeIf { v4.addresses.isNotEmpty() }, six?.ttl?.takeIf { six.addresses.isNotEmpty() }).minOrNull() ?: NEGATIVE_TTL_S
        return all to ttl
    }

    private fun remember(name: String, addresses: List<InetAddress>, ttlS: Int) {
        val ttl = ttlS.coerceIn(MIN_TTL_S, MAX_TTL_S)
        synchronized(cache) { cache[name] = Entry(addresses, clock() + ttl * 1000L) }
    }

    companion object {
        const val DEAD_MS = 60_000L
        private const val MIN_TTL_S = 60
        private const val MAX_TTL_S = 1800
        private const val NEGATIVE_TTL_S = 60
        /** How long a name stays failed after an error answer: enough to spare the server a page's repeated asks. */
        const val ERROR_TTL_MS = 10_000L
        /** The shortest time between two "Custom DNS isn't answering" notices, so a flaky network doesn't nag. */
        const val NOTICE_GAP_MS = 5 * 60_000L
        private const val MAX_CACHE = 1000
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "sofa-dns").apply { isDaemon = true } }

        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain", ".home", ".localhost")

        /** Names only the local network knows: no dot, or a suffix reserved or commonly used for home networks. */
        fun isLocal(name: String) = '.' !in name || LOCAL_SUFFIXES.any { name.endsWith(it) } || name == "localhost"

        fun isIpLiteral(name: String) = IPV4.matches(name) || (':' in name && IPV6.matches(name.removePrefix("[").removeSuffix("]")))

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IPV6 = Regex("""[0-9a-f:.]+(%\w+)?""")
    }
}
