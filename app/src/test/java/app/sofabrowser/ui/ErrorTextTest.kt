package app.sofabrowser.ui

import app.sofabrowser.ui.ErrorText.Reason
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ErrorTextTest {

    @Test
    fun customDnsRefusalsNameTheHost() {
        // What HttpURLConnection makes of the loopback proxy's refusal line.
        val e = java.net.ProtocolException("Unexpected status line: Custom DNS: no address for nonexistent.invalid")
        assertEquals(Reason.NoAddress("nonexistent.invalid"), ErrorText.reason(e))
        assertEquals(Reason.Unreachable("example.com"), ErrorText.reason(null, "Unexpected status line: Custom DNS: can't reach example.com"))
    }

    @Test
    fun theTvsOwnDnsAndConnections() {
        assertEquals(Reason.NoAddress("example.invalid"),
            ErrorText.reason(UnknownHostException("Unable to resolve host \"example.invalid\": No address associated with hostname")))
        assertEquals(Reason.NoAddress(null), ErrorText.reason(UnknownHostException()))
        assertEquals(Reason.Unreachable("127.0.0.1"), ErrorText.reason(ConnectException("Failed to connect to /127.0.0.1:1")))
        assertEquals(Reason.Unreachable("example.com"),
            ErrorText.reason(null, "failed to connect to example.com/93.184.216.34 (port 443) from /10.0.0.2 (port 4242)"))
        assertEquals(Reason.TimedOut, ErrorText.reason(SocketTimeoutException("timeout")))
        assertEquals(Reason.TimedOut, ErrorText.reason(null, "Read timed out"))
        assertEquals(Reason.Secure, ErrorText.reason(SSLHandshakeException("Chain validation failed")))
    }

    @Test
    fun httpStatusesAndSofasOwnWords() {
        assertEquals(Reason.Http(404), ErrorText.reason(IOException("HTTP 404")))
        assertEquals(Reason.Http(503), ErrorText.reason(null, "HTTP 503"))
        assertEquals(Reason.Other("no rules found"), ErrorText.reason(IOException("no rules found")))
        assertEquals(Reason.Other("IOException"), ErrorText.reason(IOException()))
    }

    @Test
    fun otherTextReadsAsASentence() {
        assertEquals("No rules found.", ErrorText.sentence("no rules found"))
        assertEquals("Already one.", ErrorText.sentence("Already one."))
        assertEquals("", ErrorText.sentence("  "))
    }
}
