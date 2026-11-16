package app.sofabrowser.dns

import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Asks one plain DNS server (RFC 1035) at [server], port 53 by default: not encrypted, so the network can read the
 * names and could change the answers. Against forged answers, as resolvers do: each query has a random id and goes
 * out from a new socket on a port the system picks at random; anything that isn't from [server] with that id and the
 * same question is dropped. UDP first, sent once more after [resendMs] without an answer; TCP when the answer was cut
 * short. Thread-safe; nothing is logged.
 */
class PlainDnsClient(
    val server: InetSocketAddress,
    private val resendMs: Int = RESEND_MS,
    private val timeoutMs: Int = DnsWire.READ_TIMEOUT_MS,
) : DnsClient {

    override fun query(host: String, type: Int): DnsMessage.Answer {
        val query = DnsMessage.query(host, type, DnsWire.newId())
        val response = udp(query).takeUnless(DnsMessage::isTruncated) ?: tcp(query)
        return DnsWire.answerTo(query, response)
    }

    /** The first datagram that answers [query]; throws SocketTimeoutException when none came in time. */
    private fun udp(query: ByteArray): ByteArray {
        DatagramSocket().use { socket ->
            // Connected: the system drops datagrams from any other address and port, and reports "port unreachable".
            socket.connect(server)
            val buf = ByteArray(MAX_UDP)
            val start = System.nanoTime()
            for (until in intArrayOf(resendMs, timeoutMs)) {
                socket.send(DatagramPacket(query, query.size))
                while (true) {
                    val wait = until - ((System.nanoTime() - start) / 1_000_000).toInt()
                    if (wait <= 0) break
                    socket.soTimeout = wait
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                    val response = buf.copyOf(packet.length)
                    if (DnsMessage.answers(query, response)) return response
                    // Anything else (a late answer to an earlier query, or a forgery) is dropped; keep listening.
                }
            }
        }
        throw SocketTimeoutException("No answer from ${server.address.hostAddress}")
    }

    private fun tcp(query: ByteArray): ByteArray = Socket(Proxy.NO_PROXY).use { socket ->
        socket.connect(server, DnsWire.CONNECT_TIMEOUT_MS)
        socket.soTimeout = timeoutMs
        DnsWire.writeFramed(socket.getOutputStream(), query)
        DnsWire.readFramed(BufferedInputStream(socket.getInputStream()))
    }

    override fun dropIdle() = Unit // nothing kept between queries

    override fun close() = Unit

    companion object {
        const val PORT = 53
        /** Without an answer by then, the query goes out once more: one lost datagram shouldn't cost a full timeout. */
        const val RESEND_MS = 1_000
        private const val MAX_UDP = 4_096
    }
}
