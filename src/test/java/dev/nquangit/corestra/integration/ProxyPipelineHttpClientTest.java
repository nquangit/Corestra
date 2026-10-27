package dev.nquangit.corestra.integration;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.persistence.ProjectSettings;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyPipelineHttpClientTest {

    @Test
    void httpProxyRequestUsesAbsoluteFormAndMarkerHeader() {
        HttpRequest request = request(false, "example.test", 8081, "POST", "/api?q=1",
                List.of(
                        header("User-Agent", "Corestra"),
                        header(CorestraTrafficOrigin.HEADER, "stale-internal-marker"),
                        header("Content-Length", "999"),
                        header("Connection", "keep-alive")),
                "hello");

        String raw = ProxyPipelineHttpClient.buildHttpProxyRequest(request);

        assertTrue(raw.startsWith("POST http://example.test:8081/api?q=1 HTTP/1.1\r\n"));
        assertTrue(raw.contains("\r\nHost: example.test:8081\r\n"));
        assertTrue(raw.contains("\r\n" + ProxyPipelineHttpClient.MARKER_HEADER + ": "));
        assertTrue(raw.contains("\r\nUser-Agent: Corestra\r\n"));
        assertTrue(raw.contains("\r\nContent-Length: 5\r\n"));
        assertTrue(raw.contains("\r\nConnection: close\r\n\r\nhello"));
        assertFalse(raw.contains("Content-Length: 999"));
        assertFalse(raw.contains("Connection: keep-alive"));
        assertFalse(raw.contains(CorestraTrafficOrigin.HEADER));
    }

    @Test
    void originFormRequestKeepsOriginPathForHttpsTunnel() {
        HttpRequest request = request(true, "example.test", 443, "GET", "/secure", List.of(), "");

        String raw = ProxyPipelineHttpClient.buildOriginFormRequest(request);

        assertTrue(raw.startsWith("GET /secure HTTP/1.1\r\n"));
        assertTrue(raw.contains("\r\nHost: example.test\r\n"));
        assertFalse(raw.contains("Content-Length:"));
    }

    @Test
    void emptyBodyPreservesAnExplicitZeroLengthAndCollapsesCapturedDuplicates() {
        var request = request(false, "example.test", 80, "POST", "/login",
                List.of(header("Content-Length", "999"), header("content-length", "42")), "");

        for (var raw : List.of(ProxyPipelineHttpClient.buildHttpProxyRequest(request),
                ProxyPipelineHttpClient.buildOriginFormRequest(request))) {
            assertTrue(raw.contains("\r\nContent-Length: 0\r\n"));
            assertEquals(1, raw.lines().filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")).count());
            assertTrue(raw.endsWith("\r\n\r\n"));
        }
    }

    @Test
    void unicodeAndBinaryBodiesUseActualNativeBytes() {
        byte[] body = {0, (byte) 0xff, (byte) 0xc3, (byte) 0xa9, 13, 10};
        var request = ManualTestMessages.proxy(HttpRequest.class, (p, method, args) -> switch (method.getName()) {
            case "body" -> ManualTestMessages.bytes(body);
            // Deliberately different text: framing and wire payload must use body(), not this representation.
            case "bodyToString" -> "lossy";
            default -> method.invoke(request(false, "example.test", 80, "POST", "/login",
                    List.of(header("Content-Length", "999")), ""), args);
        });
        for (var raw : List.of(ProxyPipelineHttpClient.buildHttpProxyRequest(request),
                ProxyPipelineHttpClient.buildOriginFormRequest(request))) {
            assertTrue(raw.contains("\r\nContent-Length: 6\r\n"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(body,
                    raw.substring(raw.indexOf("\r\n\r\n") + 4).getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void connectRequestKeepsTunnelOpenForTlsHandshake() {
        String raw = ProxyPipelineHttpClient.buildConnectRequest(service("example.test", 443, true));

        assertTrue(raw.startsWith("CONNECT example.test:443 HTTP/1.1\r\n"));
        assertTrue(raw.contains("\r\nHost: example.test:443\r\n"));
        assertTrue(raw.contains("\r\n" + ProxyPipelineHttpClient.MARKER_HEADER + ": "));
        assertTrue(raw.contains("\r\nProxy-Connection: keep-alive\r\n\r\n"));
        assertFalse(raw.toLowerCase().contains("connection: close"));
    }

    @Test
    void connectRequestFormatsIpv6AuthorityWithRequiredPort() {
        String raw = ProxyPipelineHttpClient.buildConnectRequest(service("2001:db8::1", 8443, true));

        assertTrue(raw.startsWith("CONNECT [2001:db8::1]:8443 HTTP/1.1\r\n"));
        assertTrue(raw.contains("\r\nHost: [2001:db8::1]:8443\r\n"));
    }

    @Test
    void sendHttpUsesProxySocketAndConvertsResponse() {
        FakeSocket socket = new FakeSocket("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK");
        ProjectSettings settings = new ProjectSettings(HttpSendMode.PROXY_PIPELINE, "127.0.0.9", 9090);
        ProxyPipelineHttpClient client = new ProxyPipelineHttpClient(
                () -> settings,
                (host, port, timeout) -> {
                    assertEquals("127.0.0.9", host);
                    assertEquals(9090, port);
                    assertEquals(10_000, timeout);
                    return socket;
                },
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                responseFactory());

        HttpResponse response = client.send(request(false, "example.test", 80, "GET", "/status", List.of(), ""));

        assertEquals(200, response.statusCode());
        assertTrue(socket.output().startsWith("GET http://example.test/status HTTP/1.1\r\n"));
    }

    @Test
    void contentLengthResponseDoesNotWaitForProxySocketClose() throws Exception {
        InputStream abortAfterResponse = new InputStream() {
            private final byte[] response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK"
                    .getBytes(StandardCharsets.ISO_8859_1);
            private int offset;

            @Override
            public int read() throws IOException {
                if (offset >= response.length) {
                    throw new IOException("An established connection was aborted");
                }
                return response[offset++] & 0xff;
            }

            @Override
            public int read(byte[] target, int start, int length) throws IOException {
                if (offset >= response.length) {
                    throw new IOException("An established connection was aborted");
                }
                int count = Math.min(length, response.length - offset);
                System.arraycopy(response, offset, target, start, count);
                offset += count;
                return count;
            }
        };

        String raw = ProxyPipelineHttpClient.readHttpResponse(abortAfterResponse, "GET");

        assertTrue(raw.endsWith("\r\n\r\nOK"));
    }

    @Test
    void closeDelimitedResponseAcceptsWindowsAbortAfterDeliveredBytes() throws Exception {
        InputStream resetAfterResponse = abortingInput(
                "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nOK");

        String raw = ProxyPipelineHttpClient.readHttpResponse(resetAfterResponse, "GET");

        assertTrue(raw.endsWith("\r\n\r\nOK"));
    }

    @Test
    void chunkedResponseStopsAtFinalChunkWithoutWaitingForSocketClose() throws Exception {
        String encoded = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "2\r\nOK\r\n0\r\nX-Trailer: done\r\n\r\n";

        String raw = ProxyPipelineHttpClient.readHttpResponse(
                new ByteArrayInputStream(encoded.getBytes(StandardCharsets.ISO_8859_1)), "GET");

        assertEquals(encoded, raw);
    }

    @Test
    void sendWrapsProxyConnectionFailures() {
        ProxyPipelineHttpClient client = new ProxyPipelineHttpClient(
                ProjectSettings::defaults,
                (host, port, timeout) -> {
                    throw new IOException("connection refused");
                },
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                responseFactory());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.send(request(false, "example.test", 80, "GET", "/", List.of(), "")));

        assertTrue(exception.getMessage().contains("Proxy pipeline send failed"));
        assertTrue(exception.getMessage().contains("127.0.0.1:8080"));
        assertTrue(exception.getMessage().contains("http://example.test"));
        assertTrue(exception.getMessage().contains("connection refused"));
    }

    private static Function<String, HttpResponse> responseFactory() {
        return raw -> (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> (short) 200;
                    case "reasonPhrase" -> "OK";
                    case "httpVersion" -> "HTTP/1.1";
                    case "bodyToString" -> "OK";
                    case "toString" -> raw;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static InputStream abortingInput(String rawResponse) {
        return new InputStream() {
            private final byte[] response = rawResponse.getBytes(StandardCharsets.ISO_8859_1);
            private int offset;

            @Override
            public int read() throws IOException {
                if (offset >= response.length) {
                    throw new SocketException("An established connection was aborted");
                }
                return response[offset++] & 0xff;
            }

            @Override
            public int read(byte[] target, int start, int length) throws IOException {
                if (offset >= response.length) {
                    throw new SocketException("An established connection was aborted");
                }
                int count = Math.min(length, response.length - offset);
                System.arraycopy(response, offset, target, start, count);
                offset += count;
                return count;
            }
        };
    }

    private static HttpRequest request(
            boolean secure,
            String host,
            int port,
            String method,
            String path,
            List<HttpHeader> headers,
            String body) {
        HttpService service = service(host, port, secure);
        return (HttpRequest) Proxy.newProxyInstance(
                HttpRequest.class.getClassLoader(),
                new Class<?>[]{HttpRequest.class},
                (proxy, invoked, args) -> switch (invoked.getName()) {
                    case "httpService" -> service;
                    case "method" -> method;
                    case "path" -> path;
                    case "httpVersion" -> "HTTP/1.1";
                    case "headers" -> headers;
                    case "body" -> throw new UnsupportedOperationException("body");
                    case "bodyToString" -> body;
                    case "toString" -> method + " " + path + " HTTP/1.1";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(invoked.getName());
                });
    }

    private static HttpService service(String host, int port, boolean secure) {
        return (HttpService) Proxy.newProxyInstance(
                HttpService.class.getClassLoader(),
                new Class<?>[]{HttpService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "host" -> host;
                    case "port" -> port;
                    case "secure" -> secure;
                    case "toString" -> (secure ? "https://" : "http://") + host + ":" + port;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static HttpHeader header(String name, String value) {
        return (HttpHeader) Proxy.newProxyInstance(
                HttpHeader.class.getClassLoader(),
                new Class<?>[]{HttpHeader.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> name;
                    case "value" -> value;
                    case "toString" -> name + ": " + value;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class FakeSocket extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private FakeSocket(String response) {
            this.input = new ByteArrayInputStream(response.getBytes(StandardCharsets.ISO_8859_1));
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public synchronized void setSoTimeout(int timeout) {
        }

        String output() {
            return output.toString(StandardCharsets.ISO_8859_1);
        }
    }
}
