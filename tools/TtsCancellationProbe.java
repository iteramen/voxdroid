import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Android app_process probe of the APK's actual ttsSynthesize, with synthetic loopback traffic only.
 * No installed-app access, microphone, credentials or external network. See docs/testing.md.
 */
public class TtsCancellationProbe {
    public static void main(String[] args) throws Exception {
        Class<?> callback = Class.forName("kotlin.jvm.functions.Function0");
        Class<?> connectionCallback = Class.forName("kotlin.jvm.functions.Function1");
        Class<?> endpointClass = Class.forName("io.github.iteramen.voxdroid.TtsEndpoint");
        Class<?> configClass = Class.forName("io.github.iteramen.voxdroid.ReadAloudConfig");
        Object unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        Object changed = java.lang.reflect.Proxy.newProxyInstance(connectionCallback.getClassLoader(),
            new Class<?>[]{connectionCallback}, (proxy, method, values) -> unit);
        Method post = Class.forName("io.github.iteramen.voxdroid.ReadAloudKt").getDeclaredMethod(
            "ttsSynthesize", configClass, endpointClass, String.class, callback, connectionCallback);
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
                                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 52\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                                    connection.getOutputStream().write(wav());
                                    connection.getOutputStream().flush();
                                }
                            } catch (Exception expectedOnCancelledSocket) { }
                        });
                    }
                } catch (IOException expectedOnCleanup) { }
            });
            String url = "http://127.0.0.1:"+server.getLocalPort();
            Object endpoint = endpointClass.getConstructor(String.class, String.class, String.class, String.class)
                .newInstance(url, "synthetic-model", "synthetic-voice", "");
            Object config = configClass.getConstructor(boolean.class, endpointClass, java.util.Map.class, float.class, int.class, boolean.class)
                .newInstance(true, endpoint, java.util.Collections.emptyMap(), 1f, 30, false);
            Object oldCurrent = java.lang.reflect.Proxy.newProxyInstance(callback.getClassLoader(),
                new Class<?>[]{callback}, (proxy, method, values) -> current.get());
            Object nextCurrent = java.lang.reflect.Proxy.newProxyInstance(callback.getClassLoader(),
                new Class<?>[]{callback}, (proxy, method, values) -> true);
            try {
                Future<?> old = worker.submit(() -> call(post, config, endpoint, oldCurrent, changed));
                if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("request not started");
                if (bodyStarted) Thread.sleep(150); // Allow the client to block beyond the partial body.
                long started = System.nanoTime();
                current.set(false);
                Future<?> next = worker.submit(() -> call(post, config, endpoint, nextCurrent, changed));
                Object cancelled = old.get(3, TimeUnit.SECONDS);
                if (!(cancelled instanceof Throwable)) throw new AssertionError("old succeeded");
                Object answer = next.get(3, TimeUnit.SECONDS);
                if (!answer.getClass().getSimpleName().equals("SpeechPcm") ||
                    ((short[])answer.getClass().getMethod("getSamples").invoke(answer)).length != 4 || requests.get()!=2)
                    throw new AssertionError("next request failed");
                System.out.println("PASS " + (bodyStarted ? "body" : "headers") +
                    " cancelled and next completed in " + (System.nanoTime()-started)/1000000 + "ms");
            } finally {
                current.set(false); release.countDown(); server.close();
                worker.shutdownNow(); handlers.shutdownNow();
            }
        }
    }
    private static Object call(Method post, Object config, Object endpoint, Object current, Object changed) throws Exception {
        try { return post.invoke(null, config, endpoint, "Synthetic fixture.", current, changed); }
        catch (InvocationTargetException cancelled) { return cancelled.getCause(); }
    }
    private static byte[] wav() throws Exception {
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(52).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes("US-ASCII")).putInt(44).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16);
        out.putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16);
        out.put("data".getBytes("US-ASCII")).putInt(8).put(new byte[8]);
        return out.array();
    }
}
