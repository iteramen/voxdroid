package io.github.iteramen.voxdroid

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TtsPolicyTest {
    private fun config() = ReadAloudConfig(true, endpoint("http://127.0.0.1/v1"), emptyMap(), 1f, 15, false)
    private fun endpoint(url: String) = TtsEndpoint(url, "synthetic-model", "synthetic-voice", "")

    @Test fun endpointPolicyRejectsAmbiguousOrEmbeddedData() {
        for (url in listOf("https://user@example.invalid/v1", "https://example.invalid/v1?key=value",
            "https://example.invalid/v1#fragment", "https:///v1", "https://example.invalid:bad/v1")) {
            assertNotNull(url, validateBaseUrl(url, false, EndpointKind.Tts))
        }
        for (url in listOf("https://example.invalid/v1", "http://127.0.0.1:8770/v1", "http://[::1]:8770/v1")) {
            assertNull(url, validateBaseUrl(url, false, EndpointKind.Tts))
        }
    }

    @Test fun transportChecksDirectEndpointBeforeRegisteringConnection() {
        var registered = false
        val result = runCatching {
            ttsSynthesize(config(), endpoint("http://127.0.0.1/v1?key=value"), "Synthetic fixture.", { true }) {
                if (it != null) { registered = true; error("unexpected connection") }
            }
        }
        assertFalse(registered)
        assertTrue(result.exceptionOrNull() is TtsFailure)
    }

    @Test fun cancellationAtRegistrationNeverConnects() {
        ServerSocket(0).use { server ->
            server.soTimeout = 700
            val accepted = AtomicBoolean(false)
            val current = AtomicBoolean(true)
            val peer = thread(isDaemon = true) {
                try { server.accept().use { accepted.set(true) } } catch (_: SocketTimeoutException) { }
            }
            val result = runCatching {
                ttsSynthesize(config(), endpoint("http://127.0.0.1:${server.localPort}/v1"), "Synthetic fixture.", current::get) {
                    if (it != null) current.set(false)
                }
            }
            peer.join(1200)
            assertTrue(result.isFailure)
            assertFalse("cancelled request contacted the server", accepted.get())
        }
    }

    @Test fun cancelledHeaderWaitReleasesTheWorkerPromptly() {
        ServerSocket(0).use { server ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val current = AtomicBoolean(true)
            val peer = thread(isDaemon = true) {
                server.accept().use { socket -> entered.countDown(); release.await(20, TimeUnit.SECONDS) }
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Boolean> {
                    runCatching { ttsSynthesize(config(), endpoint("http://127.0.0.1:${server.localPort}/v1"),
                        "Synthetic fixture.", current::get, {}) }.isFailure
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                current.set(false)
                assertTrue(result.get(2, TimeUnit.SECONDS))
            } finally {
                current.set(false); release.countDown(); worker.shutdownNow(); peer.join(2000)
            }
        }
    }
}
