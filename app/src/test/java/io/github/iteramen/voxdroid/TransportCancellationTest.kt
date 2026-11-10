package io.github.iteramen.voxdroid

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportCancellationTest {
    @Test
    fun cancellingSttFreesTheSerialWorkerDuringHeadersAndBody() {
        for (bodyStarted in listOf(false, true)) {
            stalledRequest(bodyStarted, """{"text":"fresh"}""") { url, current ->
                SttClient(url, true, "test", "", 30_000, false, current, {})
                    .transcribe(ByteArray(44), SttLanguage.Auto(emptyList()), null)
                    .map { it.text }
            }
        }
    }

    private fun stalledRequest(bodyStarted: Boolean, reply: String,
        send: (String, () -> Boolean) -> Result<String>) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val alive = AtomicBoolean(true)
        val handlers = Executors.newCachedThreadPool()
        val worker = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = handlers
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                if (requests.incrementAndGet() == 1) {
                    if (bodyStarted) {
                        exchange.sendResponseHeaders(200, 10_000)
                        exchange.responseBody.write('{'.code)
                        exchange.responseBody.flush()
                    }
                    entered.countDown()
                    release.await(35, TimeUnit.SECONDS)
                } else {
                    val bytes = reply.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } finally { exchange.close() }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        try {
            val old = worker.submit<Result<String>> { send(url, alive::get) }
            assertTrue("request must be in flight", entered.await(5, TimeUnit.SECONDS))
            alive.set(false)
            val next = worker.submit<Result<String>> { send(url) { true } }
            assertTrue("cancelled request must fail", old.get(3, TimeUnit.SECONDS).isFailure)
            assertEquals("fresh", next.get(3, TimeUnit.SECONDS).getOrThrow())
            assertEquals("cancelled request must not retry", 2, requests.get())
        } finally {
            alive.set(false)
            release.countDown()
            server.stop(0)
            handlers.shutdownNow()
            worker.shutdownNow()
        }
    }
}
