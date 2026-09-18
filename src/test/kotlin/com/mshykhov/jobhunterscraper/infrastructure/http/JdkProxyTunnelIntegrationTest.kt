package com.mshykhov.jobhunterscraper.infrastructure.http

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JdkProxyTunnelIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `basic CONNECT authentication requires tunneling scheme JVM override`() {
        assertFalse(runProbe("Basic"))
        assertTrue(runProbe(""))
    }

    private fun runProbe(disabledSchemes: String): Boolean {
        Files.writeString(temporaryDirectory.resolve(PROBE_FILE), PROBE_SOURCE)
        ServerSocket(0).use { server ->
            server.soTimeout = ACCEPT_TIMEOUT_MILLIS
            val authenticated = AtomicBoolean()
            val stopped = AtomicBoolean()
            val proxyThread = Thread.ofPlatform().start { serveProxy(server, authenticated, stopped) }
            val process =
                ProcessBuilder(
                    javaExecutable(),
                    "-Djdk.http.auth.tunneling.disabledSchemes=$disabledSchemes",
                    temporaryDirectory.resolve(PROBE_FILE).toString(),
                    server.localPort.toString(),
                ).redirectErrorStream(true).start()

            val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            stopped.set(true)
            server.close()
            proxyThread.join(1_000)
            if (!completed) process.destroyForcibly()
            assertTrue(completed, "Proxy CONNECT probe timed out")
            assertTrue(
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .isBlank(),
                "Proxy CONNECT probe wrote unexpected output",
            )
            assertTrue(process.exitValue() == 0, "Proxy CONNECT probe failed")
            return authenticated.get()
        }
    }

    private fun serveProxy(
        server: ServerSocket,
        authenticated: AtomicBoolean,
        stopped: AtomicBoolean,
    ) {
        while (!stopped.get()) {
            try {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    val requestLine = reader.readLine() ?: return@use
                    var proxyAuthorization: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Proxy-Authorization:", ignoreCase = true)) {
                            proxyAuthorization = line.substringAfter(':').trim()
                        }
                    }
                    if (requestLine.startsWith("CONNECT ") && proxyAuthorization == EXPECTED_AUTHORIZATION) {
                        authenticated.set(true)
                        socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    } else {
                        socket
                            .getOutputStream()
                            .write(
                                (
                                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                                        "Proxy-Authenticate: Basic realm=\"test\"\r\n" +
                                        "Content-Length: 0\r\n" +
                                        "Connection: close\r\n\r\n"
                                ).toByteArray(),
                            )
                    }
                }
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: java.net.SocketException) {
                if (!stopped.get()) throw IllegalStateException("Proxy server failed")
            }
        }
    }

    private fun javaExecutable(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private companion object {
        const val PROBE_FILE = "ProxyConnectProbe.java"
        const val ACCEPT_TIMEOUT_MILLIS = 100
        const val PROCESS_TIMEOUT_SECONDS = 10L
        val EXPECTED_AUTHORIZATION: String =
            "Basic " + Base64.getEncoder().encodeToString("proxy-user:proxy-password".toByteArray())
        val PROBE_SOURCE =
            """
            import java.net.Authenticator;
            import java.net.InetSocketAddress;
            import java.net.PasswordAuthentication;
            import java.net.ProxySelector;
            import java.net.URI;
            import java.net.http.HttpClient;
            import java.net.http.HttpRequest;
            import java.net.http.HttpResponse;
            import java.time.Duration;

            class ProxyConnectProbe {
                public static void main(String[] args) {
                    int port = Integer.parseInt(args[0]);
                    HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", port)))
                        .authenticator(new Authenticator() {
                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                if (getRequestorType() == RequestorType.PROXY) {
                                    return new PasswordAuthentication("proxy-user", "proxy-password".toCharArray());
                                }
                                return null;
                            }
                        })
                        .build();
                    HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.test/probe"))
                        .timeout(Duration.ofSeconds(2))
                        .build();
                    try {
                        client.send(request, HttpResponse.BodyHandlers.discarding());
                    } catch (Exception ignored) {
                    } finally {
                        client.shutdownNow();
                    }
                }
            }
            """.trimIndent()
    }
}
