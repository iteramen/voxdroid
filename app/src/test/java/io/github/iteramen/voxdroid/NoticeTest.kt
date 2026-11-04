package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NoticeTest {

    @Test
    fun aFailureOrARefusalFlashesForFourSecondsAndANeutralNoticeForTwoAndAHalf() {
        assertEquals(4000L, flashMs(true))
        assertEquals(2500L, flashMs(false))
        assertEquals("silence passes no failure", 2500L, flashMs())
    }

    @Test
    fun aCombinedNoticeTakesItsMostSeverePart() {
        assertEquals("polish failed · copied", 4000L, flashMs(true, false))
        assertEquals("copied after a clean polish", 2500L, flashMs(false, false))
        assertEquals("clean polish · not inserted", 4000L, flashMs(false, true))
        assertEquals("polish failed · not inserted", 4000L, flashMs(true, true))
    }

    @Test
    fun onlyACopyIsANeutralPillText() {
        val neutral = setOf(PillText.Copied, PillText.FieldChangedCopied)
        for (text in PillText.entries) assertEquals(text.name, text !in neutral, isFailure(text))
    }

    @Test
    fun aNoticeFlashesOnlyOverAnIdlePillSoAnErrorPillStays() {
        assertTrue(flashesOver(TakeState.Idle))
        assertFalse(flashesOver(TakeState.Error("stt timed out", canRetry = true)))
        assertFalse(flashesOver(TakeState.Error("grant the microphone in voxdroid", canRetry = false)))
        assertFalse(flashesOver(TakeState.Recording))
        assertFalse(flashesOver(TakeState.Working(Stage.Stt)))
    }

    @Test
    fun theTailnetRangeIsExactly100Dot64Slash10() {
        for (host in listOf("100.64.0.0", "100.64.0.1", "100.100.100.100", "100.127.255.255")) {
            assertTrue(host, tailnetHost(host))
        }
        for (host in listOf("100.63.255.255", "100.128.0.0", "100.0.0.1", "101.64.0.1", "99.64.0.1", "10.64.0.1", "192.168.1.10")) {
            assertFalse(host, tailnetHost(host))
        }
    }

    @Test
    fun aStringThatIsNotADottedQuadIsNoTailnetAddress() {
        for (host in listOf(
            "", "100.64.0", "100.64.0.0.1", "100.64.0.1a", "100.64..1", "1000.64.0.1", "100.64.256.1",
            "100.64.-1.1", "100.64.0.1 ", " 100.64.0.1", "[100.64.0.1]", "[fd7a:115c:a1e0::1]", "not a host",
        )) {
            assertFalse("'$host'", tailnetHost(host))
        }
    }

    @Test
    fun aTsNetNameMatchesInAnyCaseOnALabelBoundary() {
        for (host in listOf("box.tailnet.ts.net", "BOX.TAILNET.TS.NET", "Box.Ts.Net", "box.tailnet.ts.net.")) {
            assertTrue(host, tailnetHost(host))
        }
        for (host in listOf("ts.net", ".ts.net", "evilts.net", "box.ts.net.example.com", "box.ts.network", "box.tsxnet", "example.com", "localhost")) {
            assertFalse(host, tailnetHost(host))
        }
    }

    @Test
    fun theHostIsReadFromTheUrlWithoutItsCredentialsOrPort() {
        assertTrue(tailnetUrl("http://100.64.0.1:8080/v1"))
        assertTrue(tailnetUrl(" https://user:secret@box.tailnet.ts.net/v1 "))
        assertFalse(tailnetUrl("http://box.example.com/v1?h=box.tailnet.ts.net"))
        assertFalse(tailnetUrl("http://100.64.0.1.example.com/v1"))
        assertFalse(tailnetUrl("not a url"))
        assertFalse(tailnetUrl(""))
    }

    @Test
    fun aTimeoutAndAnUnreachableHostAreTheOnlyConnectionClasses() {
        assertEquals(Reach.TimedOut, reachOf(SocketTimeoutException("connect timed out")))
        assertEquals(Reach.Unreachable, reachOf(UnknownHostException("box.tailnet.ts.net")))
        assertEquals(Reach.Unreachable, reachOf(ConnectException("Connection refused")))
        assertEquals(Reach.Unreachable, reachOf(NoRouteToHostException("No route to host")))
        assertEquals(Reach.Unreachable, reachOf(PortUnreachableException("ICMP Port Unreachable")))
        assertNull(reachOf(SocketException("Connection reset")))
        assertNull(reachOf(SSLHandshakeException("PKIX path building failed")))
        assertNull(reachOf(ProtocolException("Unexpected status line")))
        assertNull(reachOf(IOException("not an http(s) URL")))
    }

    @Test
    fun aConnectionNoteAsksAboutTailscaleOnlyForATailnetHost() {
        assertEquals("stt timed out — Tailscale not connected?", connectionNote("stt", Reach.TimedOut, "http://100.64.0.1:8080/v1"))
        assertEquals("stt unreachable — Tailscale not connected?", connectionNote("stt", Reach.Unreachable, "http://box.tailnet.ts.net"))
        assertEquals("stt unreachable", connectionNote("stt", Reach.Unreachable, "https://api.example.com/v1"))
        assertEquals(
            "polish timed out, raw text — Tailscale not connected?",
            connectionNote("polish", Reach.TimedOut, "http://box.tailnet.ts.net/v1", tail = ", raw text"),
        )
        assertEquals("polish unreachable, raw text", connectionNote("polish", Reach.Unreachable, "http://10.0.0.2/v1", tail = ", raw text"))
    }

    @Test
    fun aConnectionNoteCarriesNothingFromTheUrl() {
        val url = "http://user:$SECRET@100.64.0.1:8080/v1/$SECRET?token=$SECRET"

        for (reach in Reach.entries) {
            val note = connectionNote("stt", reach, url)
            assertEquals("stt ${reach.label} — $TAILSCALE_HINT", note)
            for (piece in listOf(SECRET, "user", "100.64", "8080", "/v1")) assertFalse(note, note.contains(piece))
        }
    }

    private companion object {
        const val SECRET = "tokenvalue12345"
    }
}
