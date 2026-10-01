package dev.nquangit.corestra.integration;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.persistence.ProjectSettings;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * HTTP client that sends Corestra traffic through a configured Burp Proxy listener.
 */
public final class ProxyPipelineHttpClient implements HttpClient {
    /**
     * Internal marker header used to prevent Corestra auto-trigger recursion for proxy-routed traffic.
     */
    public static final String MARKER_HEADER = "X-Corestra-Proxy-Pipeline";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_HEADER_BYTES = 1024 * 1024;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final Supplier<ProjectSettings> settingsSupplier;
    private final SocketConnector socketConnector;
    private final SSLSocketFactory sslSocketFactory;
    private final Function<String, HttpResponse> responseFactory;

    /**
     * Creates a proxy-pipeline HTTP client.
     *
     * @param settingsSupplier supplies the current project settings
     */
    public ProxyPipelineHttpClient(Supplier<ProjectSettings> settingsSupplier) {
        this(settingsSupplier, ProxyPipelineHttpClient::connectSocket, trustAllSslSocketFactory(),
                HttpResponse::httpResponse);
    }

    ProxyPipelineHttpClient(
            Supplier<ProjectSettings> settingsSupplier,
            SocketConnector socketConnector,
            SSLSocketFactory sslSocketFactory,
            Function<String, HttpResponse> responseFactory) {
        this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
        this.socketConnector = Objects.requireNonNull(socketConnector, "socketConnector");
        this.sslSocketFactory = Objects.requireNonNull(sslSocketFactory, "sslSocketFactory");
        this.responseFactory = Objects.requireNonNull(responseFactory, "responseFactory");
    }

    /**
     * Sends the request through the configured Burp Proxy listener.
     *
     * @param request the request to send
     * @return the response received
     */
    @Override
    public HttpResponse send(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        ProjectSettings settings = Objects.requireNonNullElse(settingsSupplier.get(), ProjectSettings.defaults());
        try {
            String rawResponse = request.httpService().secure()
                    ? sendHttps(request, settings)
                    : sendHttp(request, settings);
            if (rawResponse.isBlank()) {
                throw new IOException("empty response from proxy pipeline");
            }
            return responseFactory.apply(rawResponse);
        } catch (Exception exception) {
            HttpService service = request.httpService();
            String target = (service.secure() ? "https://" : "http://") + hostHeader(service);
            String detail = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            throw new IllegalStateException(
                    "Proxy pipeline send failed via " + settings.proxyHost() + ":" + settings.proxyPort()
                            + " to " + target + ": " + detail,
                    exception);
        }
    }

    private String sendHttp(HttpRequest request, ProjectSettings settings) throws IOException {
        try (Socket socket = socketConnector.connect(settings.proxyHost(), settings.proxyPort(), CONNECT_TIMEOUT_MS)) {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream output = socket.getOutputStream();
            output.write(requestBytes(request, absoluteUri(request)));
            output.flush();
            return readHttpResponse(socket.getInputStream(), method(request));
        }
    }

    private String sendHttps(HttpRequest request, ProjectSettings settings) throws IOException {
        Socket proxySocket = socketConnector.connect(settings.proxyHost(), settings.proxyPort(), CONNECT_TIMEOUT_MS);
        try {
            proxySocket.setSoTimeout(READ_TIMEOUT_MS);
            HttpService service = request.httpService();
            OutputStream proxyOutput = proxySocket.getOutputStream();
            proxyOutput.write(buildConnectRequest(service).getBytes(StandardCharsets.ISO_8859_1));
            proxyOutput.flush();

            String connectResponse = readHeaders(proxySocket.getInputStream());
            int connectStatus = statusCode(connectResponse);
            if (connectStatus < 200 || connectStatus > 299) {
                throw new IOException("proxy CONNECT failed with status " + connectStatus);
            }

            try (SSLSocket tlsSocket = (SSLSocket) sslSocketFactory.createSocket(
                    proxySocket, service.host(), service.port(), true)) {
                tlsSocket.setUseClientMode(true);
                tlsSocket.setSoTimeout(READ_TIMEOUT_MS);
                try {
                    tlsSocket.startHandshake();
                } catch (IOException exception) {
                    throw new IOException("TLS handshake after proxy CONNECT failed: "
                            + exception.getMessage(), exception);
                }
                OutputStream tlsOutput = tlsSocket.getOutputStream();
                tlsOutput.write(requestBytes(request, originFormPath(request)));
                tlsOutput.flush();
                return readHttpResponse(tlsSocket.getInputStream(), method(request));
            }
        } finally {
            try {
                proxySocket.close();
            } catch (IOException ignored) {
                // Already closing after a failed proxy/TLS exchange.
            }
        }
    }

    static String buildHttpProxyRequest(HttpRequest request) {
        return new String(requestBytes(request, absoluteUri(request)), StandardCharsets.ISO_8859_1);
    }

    static String buildOriginFormRequest(HttpRequest request) {
        return new String(requestBytes(request, originFormPath(request)), StandardCharsets.ISO_8859_1);
    }

    private static byte[] requestBytes(HttpRequest request, String requestTarget) {
        byte[] body = bodyBytes(request);
        StringBuilder builder = new StringBuilder();
        builder.append(method(request)).append(' ')
                .append(requestTarget).append(' ')
                .append(httpVersion(request)).append("\r\n");
        builder.append("Host: ").append(hostHeader(request.httpService())).append("\r\n");
        builder.append(MARKER_HEADER).append(": ").append(UUID.randomUUID()).append("\r\n");
        for (HttpHeader header : safeHeaders(request)) {
            String name = header.name();
            if (isSkippedHeader(name)) {
                continue;
            }
            builder.append(name).append(": ").append(header.value()).append("\r\n");
        }
        if (body.length > 0) {
            builder.append("Content-Length: ").append(body.length).append("\r\n");
        }
        builder.append("Connection: close\r\n\r\n");

        byte[] head = builder.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] combined = new byte[head.length + body.length];
        System.arraycopy(head, 0, combined, 0, head.length);
        System.arraycopy(body, 0, combined, head.length, body.length);
        return combined;
    }

    static String buildConnectRequest(HttpService service) {
        String authority = connectAuthority(service);
        return "CONNECT " + authority + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + MARKER_HEADER + ": " + UUID.randomUUID() + "\r\n"
                + "Proxy-Connection: keep-alive\r\n\r\n";
    }

    private static String absoluteUri(HttpRequest request) {
        HttpService service = request.httpService();
        String scheme = service.secure() ? "https" : "http";
        return scheme + "://" + hostHeader(service) + originFormPath(request);
    }

    private static String originFormPath(HttpRequest request) {
        String path = request.path();
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            int schemeEnd = path.indexOf("://");
            int firstSlash = path.indexOf('/', schemeEnd + 3);
            return firstSlash >= 0 ? path.substring(firstSlash) : "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String hostHeader(HttpService service) {
        boolean defaultPort = (service.secure() && service.port() == 443)
                || (!service.secure() && service.port() == 80);
        String host = bracketIpv6Host(service.host());
        return defaultPort ? host : host + ":" + service.port();
    }

    private static String connectAuthority(HttpService service) {
        return bracketIpv6Host(service.host()) + ":" + service.port();
    }

    private static String bracketIpv6Host(String host) {
        String normalized = Objects.requireNonNullElse(host, "");
        return normalized.indexOf(':') >= 0 && !normalized.startsWith("[")
                ? "[" + normalized + "]"
                : normalized;
    }

    private static String method(HttpRequest request) {
        String method = request.method();
        return method == null || method.isBlank() ? "GET" : method;
    }

    private static String httpVersion(HttpRequest request) {
        String version = request.httpVersion();
        return version == null || version.isBlank() ? "HTTP/1.1" : version;
    }

    private static List<HttpHeader> safeHeaders(HttpRequest request) {
        List<HttpHeader> headers = request.headers();
        return headers == null ? List.of() : headers;
    }

    private static boolean isSkippedHeader(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.equals("host")
                || normalized.equals("connection")
                || normalized.equals("proxy-connection")
                || normalized.equals("content-length")
                || normalized.equals(MARKER_HEADER.toLowerCase(Locale.ROOT));
    }

    private static byte[] bodyBytes(HttpRequest request) {
        try {
            ByteArray body = request.body();
            return body == null || body.length() == 0 ? EMPTY_BYTES : body.getBytes();
        } catch (RuntimeException exception) {
            String body = request.bodyToString();
            return body == null || body.isEmpty() ? EMPTY_BYTES : body.getBytes(StandardCharsets.UTF_8);
        }
    }

    static String readHttpResponse(InputStream input, String requestMethod) throws IOException {
        String headers = readHeaders(input);
        int status = statusCode(headers);
        while (status >= 100 && status < 200 && status != 101) {
            headers = readHeaders(input);
            status = statusCode(headers);
        }

        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        if ("HEAD".equalsIgnoreCase(requestMethod)
                || status == 101 || status == 204 || status == 205 || status == 304) {
            return response.toString(StandardCharsets.ISO_8859_1);
        }

        long contentLength = contentLength(headers);
        if (isChunked(headers)) {
            copyChunked(input, response);
        } else if (contentLength >= 0L) {
            copyExactly(input, response, contentLength);
        } else {
            copyUntilClosed(input, response);
        }
        return response.toString(StandardCharsets.ISO_8859_1);
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            buffer.write(current);
            if (buffer.size() > MAX_HEADER_BYTES) {
                throw new IOException("proxy response headers exceed " + MAX_HEADER_BYTES + " bytes");
            }
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        String headers = buffer.toString(StandardCharsets.ISO_8859_1);
        if (!headers.endsWith("\r\n\r\n")) {
            throw new IOException("incomplete proxy response headers");
        }
        return headers;
    }

    private static long contentLength(String headers) throws IOException {
        for (String line : headers.split("\\r\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).strip().equalsIgnoreCase("Content-Length")) {
                try {
                    long length = Long.parseLong(line.substring(colon + 1).strip());
                    if (length < 0L) {
                        throw new NumberFormatException("negative");
                    }
                    return length;
                } catch (NumberFormatException exception) {
                    throw new IOException("invalid proxy response Content-Length", exception);
                }
            }
        }
        return -1L;
    }

    private static boolean isChunked(String headers) {
        for (String line : headers.split("\\r\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).strip().equalsIgnoreCase("Transfer-Encoding")) {
                for (String encoding : line.substring(colon + 1).split(",")) {
                    if (encoding.strip().equalsIgnoreCase("chunked")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void copyExactly(InputStream input, ByteArrayOutputStream output, long byteCount)
            throws IOException {
        byte[] chunk = new byte[8192];
        long remaining = byteCount;
        while (remaining > 0L) {
            int read = input.read(chunk, 0, (int) Math.min(chunk.length, remaining));
            if (read == -1) {
                throw new IOException("proxy response ended before its declared Content-Length");
            }
            output.write(chunk, 0, read);
            remaining -= read;
        }
    }

    private static void copyChunked(InputStream input, ByteArrayOutputStream output) throws IOException {
        while (true) {
            String sizeLine = readLine(input);
            output.write((sizeLine + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            String sizeToken = sizeLine.split(";", 2)[0].strip();
            final long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeToken, 16);
            } catch (NumberFormatException exception) {
                throw new IOException("invalid chunk size in proxy response", exception);
            }
            if (chunkSize < 0L) {
                throw new IOException("negative chunk size in proxy response");
            }
            if (chunkSize == 0L) {
                String trailer;
                do {
                    trailer = readLine(input);
                    output.write((trailer + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                } while (!trailer.isEmpty());
                return;
            }
            copyExactly(input, output, chunkSize);
            byte[] terminator = input.readNBytes(2);
            if (terminator.length != 2 || terminator[0] != '\r' || terminator[1] != '\n') {
                throw new IOException("invalid chunk terminator in proxy response");
            }
            output.write(terminator);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            previous = current;
            if (line.size() > MAX_HEADER_BYTES) {
                throw new IOException("proxy response line exceeds " + MAX_HEADER_BYTES + " bytes");
            }
        }
        throw new IOException("incomplete line in proxy response");
    }

    private static void copyUntilClosed(InputStream input, ByteArrayOutputStream output) throws IOException {
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = input.read(chunk)) != -1) {
                output.write(chunk, 0, read);
            }
        } catch (SocketTimeoutException exception) {
            throw exception;
        } catch (SocketException exception) {
            // Some proxy/socket combinations on Windows report an abort/reset instead of EOF
            // after delivering a close-delimited response. The bytes already received are the
            // complete response available for this framing mode.
        }
    }

    private static int statusCode(String responseHeaders) throws IOException {
        String firstLine = responseHeaders == null ? "" : responseHeaders.split("\\R", 2)[0];
        String[] parts = firstLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("invalid proxy CONNECT response");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new IOException("invalid proxy CONNECT status: " + parts[1], exception);
        }
    }

    private static Socket connectSocket(String host, int port, int timeoutMs) throws IOException {
        Socket socket = SocketFactory.getDefault().createSocket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        return socket;
    }

    private static SSLSocketFactory trustAllSslSocketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return context.getSocketFactory();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create proxy pipeline TLS context", exception);
        }
    }

    @FunctionalInterface
    interface SocketConnector {
        Socket connect(String host, int port, int timeoutMs) throws IOException;
    }
}
