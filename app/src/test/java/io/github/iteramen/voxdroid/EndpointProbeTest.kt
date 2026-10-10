package io.github.iteramen.voxdroid

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

class EndpointProbeTest {

    @Test
    fun aBlankEndpointIsNotProbed() {
        assertEquals(Probe.Unknown, probe("", true))
        assertEquals(Probe.Unknown, probe("   ", true))
    }

    @Test
    fun aStoredPortOutOfRangeIsUnreachableNotAThrow() {
        assertEquals(Probe.Unreachable, probe("http://whisper_server:87700/v1", true))
        assertEquals(Probe.Unreachable, probe("http://127.0.0.1:99999/v1", true))
    }

    @Test
    fun aNonHttpSchemeIsUnreachable() {
        assertEquals(Probe.Unreachable, probe("ftp://127.0.0.1/v1", true))
    }

    @Test
    fun withTheSwitchOffPlainHttpToARemoteHostIsUnreachableWithNothingSent() {
        val hits = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.add(exchange.requestURI.path)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val url = "http://[::ffff:127.0.0.1]:${server.address.port}"
            assertEquals(Probe.Unreachable, probe(url, false))
            // `URI` reads `url:` as the scheme, `java.net.URL` strips it and opens plain http.
            assertEquals(Probe.Unreachable, probe("url:$url", false))
            assertEquals("requests with the switch off: $hits", emptyList<String>(), hits.toList())
            val prefixed = probe("url:$url", true)
            assertTrue("the switch on reaches the server through url:, got $prefixed", prefixed is Probe.Reachable)
            val control = probe(url, true)
            assertTrue("the switch on reaches the server, got $control", control is Probe.Reachable)
            val loopback = probe("http://127.0.0.1:${server.address.port}", false)
            assertTrue("loopback with the switch off is reachable, got $loopback", loopback is Probe.Reachable)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aRedirectIsReachableAndIsNotFollowed() {
        val hits = Collections.synchronizedList(mutableListOf<String>())
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/") { exchange ->
            hits.add(exchange.requestURI.path)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        target.start()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/landing")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        origin.start()
        try {
            val answer = probe("http://127.0.0.1:${origin.address.port}", true)
            assertTrue("a 302 from /models is Reachable, got $answer", answer is Probe.Reachable)
            assertEquals("the redirect target saw requests: $hits", emptyList<String>(), hits.toList())
        } finally {
            origin.stop(0)
            target.stop(0)
        }
    }
}
