package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.DocumentBuilderFactory

class EndpointsTest {

    @Test
    fun aBlankUrlIsAccepted() {
        assertNull(validateBaseUrl("", true, EndpointKind.Stt))
        assertNull(validateBaseUrl("   ", true, EndpointKind.Polish))
    }

    @Test
    fun httpAndHttpsAreBothAccepted() {
        assertNull(validateBaseUrl("http://whisper.example.ts.net:8770/v1", true, EndpointKind.Stt))
        assertNull(validateBaseUrl("https://api.example.com/v1", true, EndpointKind.Stt))
    }

    @Test
    fun aTrailingSlashIsAccepted() {
        assertNull(validateBaseUrl("https://api.example.com/v1/", true, EndpointKind.Stt))
    }

    @Test
    fun anExplicitPortOutsideTheRangeIsRefusedForEveryKind() {
        for (kind in EndpointKind.values()) {
            assertEquals(BaseUrlProblem.PortOutOfRange, validateBaseUrl("http://whisper:0/v1", true, kind))
            assertEquals(BaseUrlProblem.PortOutOfRange, validateBaseUrl("http://whisper:65536/v1", true, kind))
            assertEquals(BaseUrlProblem.PortOutOfRange, validateBaseUrl("https://whisper:99999/v1", true, kind))
            assertEquals(BaseUrlProblem.PortOutOfRange, validateBaseUrl("http://whisper:87700/v1", true, kind))
        }
    }

    @Test
    fun anUnderscoreHostIsHeldToTheSamePortRange() {
        assertEquals(BaseUrlProblem.PortOutOfRange, validateBaseUrl("http://whisper_server:87700/v1", true, EndpointKind.Stt))
        assertEquals(BaseUrlProblem.PortOutOfRange, validateBaseUrl("http://whisper_host:99999/v1", true, EndpointKind.Polish))
        assertNull(validateBaseUrl("http://whisper_server:8770/v1", true, EndpointKind.Stt))
    }

    @Test
    fun portInRangeIsTrueForNoPortAndForAStringUrlCannotParse() {
        assertTrue(portInRange("http://whisper/v1"))
        assertTrue(portInRange("not a url"))
        assertFalse(portInRange(" http://whisper:65536/v1 "))
    }

    @Test
    fun thePortRangeEdgesAndNoPortAreAccepted() {
        assertNull(validateBaseUrl("http://whisper:1/v1", true, EndpointKind.Stt))
        assertNull(validateBaseUrl("http://whisper:65535/v1", true, EndpointKind.Stt))
        assertNull(validateBaseUrl("http://whisper/v1", true, EndpointKind.Stt))
        assertNull(validateBaseUrl("http://[::1]:8770/v1", true, EndpointKind.Stt))
    }

    @Test
    fun aUrlWithNoSchemeIsRefused() {
        assertEquals(
            BaseUrlProblem.NotAnHttpUrl,
            validateBaseUrl("whisper.example.ts.net:8770/v1", true, EndpointKind.Stt),
        )
    }

    @Test
    fun aSchemeThatIsNotHttpIsRefused() {
        assertEquals(BaseUrlProblem.NotAnHttpUrl, validateBaseUrl("ftp://host/v1", true, EndpointKind.Stt))
        assertEquals(BaseUrlProblem.NotAnHttpUrl, validateBaseUrl("file:///tmp/v1", true, EndpointKind.Stt))
    }

    @Test
    fun anSttUrlThatAlreadyEndsInTheTranscriptionsPathIsRefused() {
        assertEquals(
            BaseUrlProblem.PathAlreadyThere,
            validateBaseUrl("https://api.example.com/v1/audio/transcriptions", true, EndpointKind.Stt),
        )
        assertEquals(
            BaseUrlProblem.PathAlreadyThere,
            validateBaseUrl("https://api.example.com/v1/audio/transcriptions/", true, EndpointKind.Stt),
        )
    }

    @Test
    fun aPolishUrlThatAlreadyEndsInTheCompletionsPathIsRefused() {
        assertEquals(
            BaseUrlProblem.PathAlreadyThere,
            validateBaseUrl("https://api.example.com/v1/chat/completions", true, EndpointKind.Polish),
        )
    }

    @Test
    fun eachKindRefusesOnlyItsOwnPath() {
        assertNull(validateBaseUrl("https://api.example.com/v1/chat/completions", true, EndpointKind.Stt))
        assertNull(validateBaseUrl("https://api.example.com/v1/audio/transcriptions", true, EndpointKind.Polish))
    }

    @Test
    fun cleartextToATailnetHostIsWarned() {
        assertTrue(isCleartextToRemoteHost("http://whisper.example.ts.net:8770/v1"))
    }

    @Test
    fun anUppercaseSchemeIsStillCleartext() {
        assertTrue(isCleartextToRemoteHost("HTTP://whisper.example.ts.net:8770/v1"))
    }

    @Test
    fun theSummaryIsTheUrlThenTheWarning() {
        val tailnet = "http://whisper.example.ts.net:8770/v1"
        assertEquals("$tailnet\nwarn", urlSummary(tailnet, true, "none", "warn", "blocked"))
        assertEquals("http://127.0.0.1:8770/v1", urlSummary("http://127.0.0.1:8770/v1", true, "none", "warn", "blocked"))
        assertEquals("https://whisper.example.ts.net:8770/v1", urlSummary("https://whisper.example.ts.net:8770/v1", true, "none", "warn", "blocked"))
        assertEquals("none", urlSummary("", true, "none", "warn", "blocked"))
        assertEquals("none", urlSummary("  ", true, "none", "warn", "blocked"))
    }

    @Test
    fun loopbackIsNotWarned() {
        assertFalse(isCleartextToRemoteHost("http://127.0.0.1:8770/v1"))
        assertFalse(isCleartextToRemoteHost("http://127.1.2.3:8770"))
        assertFalse(isCleartextToRemoteHost("http://localhost:8770"))
        assertFalse(isCleartextToRemoteHost("http://LOCALHOST:8770"))
        assertFalse(isCleartextToRemoteHost("http://[::1]:8770"))
    }

    @Test
    fun httpsIsNotWarnedAnywhere() {
        assertFalse(isCleartextToRemoteHost("https://whisper.example.ts.net:8770/v1"))
        assertFalse(isCleartextToRemoteHost("https://127.0.0.1:8770"))
    }

    @Test
    fun aBlankUrlIsNotWarned() {
        assertFalse(isCleartextToRemoteHost(""))
        assertFalse(isCleartextToRemoteHost("   "))
    }

    @Test
    fun aHostnameStartingWith127IsNotLoopback() {
        assertTrue(isCleartextToRemoteHost("http://127.example.com:8770/v1"))
        assertTrue(isCleartextToRemoteHost("http://127.0.0:8770/v1"))
        // An octet above 255 is no IPv4 literal and no hostname either: `URI` reads no host at
        // all, and no host is not loopback.
        assertTrue(isCleartextToRemoteHost("http://127.999.999.999:8770/v1"))
    }

    @Test
    fun aCleartextUrlWithAnUnreadableHostIsWarned() {
        assertTrue(isCleartextToRemoteHost("http://whisper_host:8770/v1"))
    }

    @Test
    fun withTheSwitchOffPlainHttpToARemoteHostIsRefused() {
        for (url in REMOTE_HTTP) {
            for (kind in EndpointKind.values()) {
                assertEquals("$url $kind", BaseUrlProblem.InsecureHttp, validateBaseUrl(url, false, kind))
            }
        }
    }

    @Test
    fun withTheSwitchOffLoopbackHttpAndHttpsAreAccepted() {
        for (url in LOOPBACK_HTTP + HTTPS) assertNull(url, validateBaseUrl(url, false, EndpointKind.Stt))
    }

    @Test
    fun withTheSwitchOnEveryOneIsAccepted() {
        for (url in REMOTE_HTTP + LOOPBACK_HTTP + HTTPS) assertNull(url, validateBaseUrl(url, true, EndpointKind.Stt))
    }

    @Test
    fun withTheSwitchOffAUrlOnlyJavaNetUrlReadsIsBlocked() {
        for (url in ONLY_URL_READS) {
            assertTrue("$url, switch off", insecureBlocked(url, false))
            assertFalse("$url, switch on", insecureBlocked(url, true))
        }
    }

    @Test
    fun withTheSwitchOffAnythingButHttpsAndLoopbackHttpIsBlocked() {
        for (url in listOf("not a url", "ftp://127.0.0.1/v1", "http://localhost./v1")) assertTrue(url, insecureBlocked(url, false))
        for (url in LOOPBACK_HTTP + HTTPS + "HTTP://LOCALHOST:8770/v1") assertFalse(url, insecureBlocked(url, false))
    }

    @Test
    fun httpsThatOnlyJavaNetUrlReadsIsAllowedBecauseItConnectsOverTls() {
        val url = "https://203.0.113.1/v1|"
        assertFalse(insecureBlocked(url, false))
        assertTrue(URL(url).openConnection() is HttpsURLConnection)
    }

    @Test
    fun aCleartextUrlOnlyJavaNetUrlReadsIsWarned() {
        for (url in ONLY_URL_READS) {
            assertTrue(url, isCleartextToRemoteHost(url))
            assertEquals("$url\nwarn", urlSummary(url, true, "none", "warn", "blocked"))
            assertEquals("$url\nblocked", urlSummary(url, false, "none", "warn", "blocked"))
        }
    }

    @Test
    fun theSummaryOfABlockedUrlIsTheUrlThenTheBlockedLine() {
        val tailnet = "http://whisper.example.ts.net:8770/v1"
        assertEquals("$tailnet\nblocked", urlSummary(tailnet, false, "none", "warn", "blocked"))
        assertEquals("$tailnet\nwarn", urlSummary(tailnet, true, "none", "warn", "blocked"))
        assertEquals("http://127.0.0.1:8770/v1", urlSummary("http://127.0.0.1:8770/v1", false, "none", "warn", "blocked"))
        assertEquals("https://whisper.example.ts.net/v1", urlSummary("https://whisper.example.ts.net/v1", false, "none", "warn", "blocked"))
        assertEquals("none", urlSummary("", false, "none", "warn", "blocked"))
    }

    @Test
    fun theSwitchIsOffByDefaultInCodeAndOnTheSpeechPage() {
        assertFalse("ALLOW_INSECURE_HTTP_DEFAULT", ALLOW_INSECURE_HTTP_DEFAULT)
        val screen = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(speechFile())
            .documentElement
        val rows = (0 until screen.childNodes.length).map { screen.childNodes.item(it) }.filterIsInstance<Element>()
        val keys = rows.map { it.getAttributeNS(ANDROID_NS, "key") }
        val switch = rows.filter { it.getAttributeNS(ANDROID_NS, "key") == "allow_insecure_http" }
        assertEquals("allow_insecure_http rows on the Speech page", 1, switch.size)
        assertEquals("SwitchPreferenceCompat", switch.single().tagName)
        assertEquals("defaultValue", ALLOW_INSECURE_HTTP_DEFAULT.toString(), switch.single().getAttributeNS(ANDROID_NS, "defaultValue"))
        assertEquals("the row under the STT endpoint", keys.indexOf("stt_base_url") + 1, keys.indexOf("allow_insecure_http"))
    }

    @Test
    fun aLaneOnAnotherHostGetsNoToken() {
        assertEquals(
            "",
            laneToken("https://api.example/v1", "k", "http://other:9000/v1", ownToken = "", inherit = null),
        )
    }

    @Test
    fun aLaneIsSentATokenOnlyWhenAsked() {
        val stt = "https://api.example/v1"
        fun token(lane: String, sttToken: String = "k", own: String = "", inherit: Boolean? = null) =
            laneToken(stt, sttToken, lane, own, inherit)

        assertEquals("own token with the switch on", "o", token("http://other:9000/v1", own = "o", inherit = true))
        assertEquals("own token with the switch unset", "o", token("http://other:9000/v1", own = "o"))
        assertEquals("own token wins over the switch off", "o", token("https://api.example/v2", own = "o", inherit = false))
        assertEquals("a blank own token is none", "k", token("http://other:9000/v1", own = "   ", inherit = true))
        assertEquals("switch on, another host", "k", token("http://other:9000/v1", inherit = true))
        assertEquals("switch off, the same host", "", token("https://api.example/v2", inherit = false))
        assertEquals("unset, same scheme host and port", "k", token("https://api.example/he/v1"))
        assertEquals("unset, another host", "", token("https://other.example/v1"))
        assertEquals("unset, another port", "", token("https://api.example:8443/v1"))
        assertEquals("unset, http against https", "", token("http://api.example/v1"))
        assertEquals("unset, same host, blank STT token", "", token("https://api.example/v1", sttToken = ""))
        assertEquals("unset, same host, whitespace STT token", "", token("https://api.example/v1", sttToken = " "))
        assertEquals("switch on with no STT token has nothing to send", "", token("http://other:9000/v1", sttToken = "", inherit = true))
    }

    @Test
    fun theSwitchShowsTheChoiceElseTheComputedDefault() {
        val stt = "https://api.example/v1"
        assertTrue(laneInherits(stt, "k", "https://api.example/he/v1", null))
        assertFalse(laneInherits(stt, "k", "http://other:9000/v1", null))
        assertFalse(laneInherits(stt, "", "https://api.example/he/v1", null))
        assertTrue(laneInherits(stt, "k", "http://other:9000/v1", true))
        assertFalse(laneInherits(stt, "k", "https://api.example/he/v1", false))
    }

    @Test
    fun theSwitchOpensOnTheComputedOrStoredStateAndIsGreyedOutUnderAnOwnToken() {
        val stt = "https://api.example/v1"
        fun shown(lane: String, sttToken: String = "k", own: String = "", inherit: Boolean? = null) =
            laneSwitch(stt, sttToken, lane, own, inherit)

        assertEquals("unset, same server, STT token", LaneSwitch(checked = true, enabled = true), shown("https://api.example/he/v1"))
        assertEquals("unset, another host", LaneSwitch(checked = false, enabled = true), shown("http://other:9000/v1"))
        assertEquals("stored on, another host", LaneSwitch(checked = true, enabled = true), shown("http://other:9000/v1", inherit = true))
        assertEquals("stored off, same server", LaneSwitch(checked = false, enabled = true), shown("https://api.example/he/v1", inherit = false))
        assertEquals("own token set", false, shown("http://other:9000/v1", own = "o").enabled)
        assertEquals("own token set, unset switch keeps its computed position", true, shown("https://api.example/v1", own = "o").checked)
        assertEquals("a blank own token greys nothing out", true, shown("http://other:9000/v1", own = "  ").enabled)
    }

    @Test
    fun sameServerIsSchemeHostAndPortAsWritten() {
        val same = listOf(
            "https://api.example/v1" to "https://api.example/v1",
            "https://api.example/v1" to "https://api.example/he/v1/",
            "https://api.example" to "https://api.example/",
            "HTTPS://API.Example:8443/v1" to "https://api.example:8443/x",
            "  https://api.example/v1 " to "https://api.example/v1",
            "https://api.example?x=1" to "https://api.example#top",
            "https://user:pw@api.example/v1" to "https://api.example/v1",
            "http://whisper_host:8770/v1" to "http://WHISPER_HOST:8770/he/v1",
            "api.example/v1" to "api.example/v2",
        )
        for ((a, b) in same) {
            assertTrue("$a ~ $b", sameServer(a, b))
            assertTrue("$b ~ $a", sameServer(b, a))
        }
        val different = listOf(
            "https://api.example/v1" to "http://api.example/v1",
            "https://api.example/v1" to "https://api.example:8443/v1",
            "https://api.example:8443/v1" to "https://api.example:8444/v1",
            // A missing port is not the scheme's default.
            "https://api.example/v1" to "https://api.example:443/v1",
            "https://api.example/v1" to "https://api.example.evil/v1",
            "https://api.example/v1" to "https://evil-api.example/v1",
            "https://api.example/v1" to "https://api.example.evil@other.example/v1",
            "https://api.example/v1" to "api.example/v1",
            // U+212A KELVIN SIGN folds onto `k` in Unicode but is no ASCII letter.
            "https://kapi.example/v1" to "https://Kapi.example/v1",
        )
        for ((a, b) in different) {
            assertFalse("$a !~ $b", sameServer(a, b))
            assertFalse("$b !~ $a", sameServer(b, a))
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        val REMOTE_HTTP = listOf(
            "http://whisper.example.ts.net:8770/v1",
            "http://192.168.1.10:8770/v1",
            "http://api.example.com/v1",
            "http://whisper_host:8770/v1",
        )
        val LOOPBACK_HTTP = listOf("http://127.0.0.1:8770/v1", "http://[::1]:8770/v1", "http://localhost:8770/v1")
        val HTTPS = listOf("https://whisper.example.ts.net:8770/v1", "https://192.168.1.10/v1", "https://api.example.com/v1")

        val ONLY_URL_READS = listOf(
            "http://203.0.113.1:9/v1|",
            "url:http://203.0.113.1:9/v1",
            "http://203.0.113.1:9/%zz",
            "http://203.0.113.1:9/{x}",
            "http://203.0.113.1:9/v 1",
            "http://203.0.113.1:9/v1?q=|",
            "http://127.0.0.1\\@203.0.113.1/",
            "http://[::1]@203.0.113.1/",
        )

        fun speechFile(): File = sequenceOf(
            "src/main/res/xml/section_speech.xml",
            "app/src/main/res/xml/section_speech.xml",
        ).map(::File).first(File::exists)
    }
}
