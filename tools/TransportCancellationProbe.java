import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Android app_process probe of the APK's actual boundedPost, with synthetic loopback traffic only.
 * No installed-app access, microphone, credentials or external network. See docs/testing.md.
 */
public class TransportCancellationProbe {
    public static void main(String[] args) throws Exception {
        Class<?> callback = Class.forName("kotlin.jvm.functions.Function0");
        Method post = Class.forName("io.github.iteramen.voxdroid.HttpKt").getDeclaredMethod(
            "boundedPost", String.class, boolean.class, String.class, String.class,
            byte[].class, int.class, String.class, callback);
        for (boolean bodyStarted : new boolean[]{false, true}) {
            ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicBoolean current = new AtomicBoolean(true);
            AtomicInteger requests = new AtomicInteger();
            ExecutorService handlers = Executors.newCachedThreadPool();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            handlers.submit(() -> {
                try {
                    for (int i=0; i<2; i++) {
                        Socket socket = server.accept();
                        handlers.submit(() -> {
                            try (Socket connection = socket) {
                                InputStream input = connection.getInputStream();
                                StringBuilder header = new StringBuilder();
                                while (!header.toString().endsWith("\r\n\r\n")) {
                                    int b = input.read();
                                    if (b < 0 || header.length() > 8192) throw new IOException("header");
                                    header.append((char)b);
                                }
                                int length = 0;
                                for (String line : header.toString().split("\r\n"))
                                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                                        length = Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                                for (int n=0; n<length; n++) if (input.read()<0) throw new IOException("body");
                                if (requests.incrementAndGet() == 1) {
                                    if (bodyStarted) {
                                        connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n{").getBytes("UTF-8"));
                                        connection.getOutputStream().flush();
                                    }
                                    entered.countDown();
                                    release.await(10, TimeUnit.SECONDS);
                                } else {
                                    connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nfresh".getBytes("UTF-8"));
                                    connection.getOutputStream().flush();
                                }
                            } catch (Exception expectedOnCancelledSocket) { }
                        });
                    }
                } catch (IOException expectedOnCleanup) { }
            });
            String url = "http://127.0.0.1:"+server.getLocalPort();
            Object oldCurrent = java.lang.reflect.Proxy.newProxyInstance(callback.getClassLoader(),
                new Class<?>[]{callback}, (proxy, method, values) -> current.get());
            Object nextCurrent = java.lang.reflect.Proxy.newProxyInstance(callback.getClassLoader(),
                new Class<?>[]{callback}, (proxy, method, values) -> true);
            try {
                Future<?> old = worker.submit(() -> post.invoke(null, url, true, "text/plain", "",
                    new byte[]{1}, 30000, "probe-watchdog", oldCurrent));
                if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("request not started");
                long started = System.nanoTime();
                current.set(false);
                Future<?> next = worker.submit(() -> post.invoke(null, url, true, "text/plain", "",
                    new byte[]{2}, 30000, "probe-watchdog", nextCurrent));
                Object cancelled = old.get(3, TimeUnit.SECONDS);
                if (!cancelled.getClass().getSimpleName().equals("SendFailed")) throw new AssertionError("old succeeded");
                Object answer = next.get(3, TimeUnit.SECONDS);
                if (!answer.getClass().getSimpleName().equals("Answered") ||
                    !answer.getClass().getMethod("getBody").invoke(answer).equals("fresh") || requests.get()!=2)
                    throw new AssertionError("next request failed");
                System.out.println("PASS " + (bodyStarted ? "body" : "headers") +
                    " cancelled and next completed in " + (System.nanoTime()-started)/1000000 + "ms");
            } finally {
                current.set(false); release.countDown(); server.close();
                worker.shutdownNow(); handlers.shutdownNow();
            }
        }
    }
}
