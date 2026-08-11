import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class ProxyServer {

    private static final int PORT = 43210;

    /**
     * Headers that describe a single connection rather than the message being carried, so they
     * are meaningful only between two adjacent parties and must not be passed on. The list is
     * the assignment's, plus Proxy-Connection - a non-standard header curl sends on every
     * proxied request, hop-by-hop by the same reasoning as Connection.
     *
     * <p>Content-Length is here for a different reason. The request payload is empty by
     * assumption so there is nothing to describe, and on the response side the length is not a
     * header this server writes: it is an argument to sendResponseHeaders. Leaving it in the
     * forwarded set would mean declaring the length twice, once correctly and once by hand.
     */
    private static final Set<String> NOT_FORWARDED = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "proxy-connection",
            "content-length");

    // Comfortably over the 1024 the assignment asks for, and twice InputStream.transferTo's
    // built-in 8KB. Memory cost is per in-flight response, not per byte transferred.
    private static final int COPY_BUFFER_BYTES = 16 * 1024;

    // Bounds only the wait for a usable connection. Deliberately no request timeout: that
    // clock would run against the whole response, and killing a large but healthy download
    // part way through is worse than waiting for it.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    public static void main(String[] args) throws IOException {
        // Must run before any java.net.http class initialises - the permitted set is read once,
        // in a static initialiser. HttpClient refuses to send a caller-supplied Host because an
        // ordinary client has no business overriding it; a proxy does, since forwarding the
        // client's headers is the job. See buildUpstreamRequest.
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // "/" catches everything. A proxied request line carries an absolute URI -
        // "GET http://httpbin.org/uuid HTTP/1.1" - and HttpServer matches contexts on that
        // URI's path, so every destination path has to land on the same handler.
        server.createContext("/", new ProxyHandler());

        // Without an executor HttpServer serves one request at a time, and a proxy is almost
        // entirely blocked waiting on an upstream socket - which is what virtual threads are for.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.start();
        System.out.println("ProxyServer is running on 127.0.0.1:" + PORT);
    }

    /**
     * Carries the status the client should receive, so a check deep in the call stack can
     * reject a request without every caller in between forwarding an error.
     */
    static final class ProxyException extends RuntimeException {
        private final int statusCode;

        ProxyException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int getStatusCode() {
            return statusCode;
        }
    }

    /**
     * A relay that stopped after the status line had already gone out.
     *
     * <p>Separate from ProxyException because there is no status left to send: the response is
     * committed the moment sendResponseHeaders runs. All that remains is one line in the log,
     * which is the whole reason this is a type of its own - it is routine traffic, not a fault,
     * and it must not be reported like one.
     */
    static final class RelayFailure extends RuntimeException {
        RelayFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Some IOExceptions carry no message, which turns naive concatenation into "- null". */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
    }

    /** True if the given type appears anywhere in the exception's cause chain. */
    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    static final class ProxyHandler implements HttpHandler {

        /**
         * One client for the whole proxy, because it owns the connection pool - a client per
         * request would open a fresh TCP connection every time and never reuse one.
         *
         * <p>HTTP/1.1 is pinned. Left at its default the client advertises an upgrade to h2c on
         * every cleartext request, which would add Upgrade and HTTP2-Settings headers the client
         * never sent - and Upgrade is a header this proxy is specifically told not to forward.
         *
         * <p>Redirects are not followed, which is the default. A proxy hands the 3xx back and
         * lets the client decide; following it would return a body from a URL the client never
         * asked for, under a status saying otherwise.
         */
        private final HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Nested deliberately: try-with-resources closes the exchange before any catch of
            // its own runs, so the inner catch must send the response while it is still open.
            try (exchange) {
                try {
                    proxy(exchange);
                } catch (ProxyException e) {
                    System.err.println(e.getStatusCode() + " " + exchange.getRequestMethod() + " "
                            + exchange.getRequestURI() + " - " + e.getMessage());
                    respond(exchange, e.getStatusCode());
                } catch (RelayFailure e) {
                    // Normal traffic - someone pressed Ctrl-C or a timeout fired - so one line.
                    // A stack trace per abandoned download would bury a real failure.
                    System.err.println("relay stopped " + exchange.getRequestURI() + " - " + e.getMessage());
                } catch (Exception e) {
                    // A bug in this proxy rather than a bad request or a bad upstream, so it
                    // gets the full stack. Keeping the two apart stops a client hammering an
                    // unreachable host from burying a real failure.
                    e.printStackTrace();
                    respond(exchange, 500);
                }
            }
        }

        private void proxy(HttpExchange exchange) throws IOException {
            URI target = validateRequest(exchange);
            HttpRequest upstreamRequest = buildUpstreamRequest(target, exchange);

            HttpResponse<InputStream> upstreamResponse = sendUpstream(upstreamRequest);

            // Closes the upstream body whatever happens, including the client hanging up mid
            // transfer - otherwise that connection is never returned to the pool.
            try (InputStream upstreamBody = upstreamResponse.body()) {
                relay(exchange, upstreamResponse, upstreamBody);
            }
        }

        /**
         * Checks the two things that make a request proxyable, and returns the destination.
         *
         * <p>An absolute URI is the whole difference between a proxy request and an ordinary
         * one: a client talking to a proxy puts the full URL in the request line, because the
         * proxy needs to know which host to contact. A bare path means someone pointed a normal
         * HTTP client at this port, and there is no destination to derive.
         */
        private URI validateRequest(HttpExchange exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET")) {
                // 501, not 405. 405 says the method is wrong for this resource, which would be
                // a claim about the destination - and this proxy has not asked it. 501 says
                // this server does not implement the method, which is the true statement.
                throw new ProxyException(501, "Only GET is supported");
            }

            URI target = exchange.getRequestURI();
            if (!target.isAbsolute()) {
                throw new ProxyException(400,
                        "Request target must be an absolute URI; got \"" + target + "\"");
            }

            String scheme = target.getScheme().toLowerCase();
            if (!scheme.equals("http")) {
                // https through a proxy is a CONNECT tunnel, not a forwarded GET.
                throw new ProxyException(501, "Only the http scheme is supported; got " + scheme);
            }

            if (target.getHost() == null) {
                throw new ProxyException(400, "Request target has no host: \"" + target + "\"");
            }

            return target;
        }

        /**
         * Rebuilds the client's request against the destination, carrying every header across
         * except the ones that belong to the client-to-proxy hop.
         *
         * <p>Header names arrive re-cased by HttpServer - "User-Agent" becomes "User-agent" -
         * which changes nothing, since HTTP header names are case-insensitive by definition.
         * The matching here is lowercased for the same reason.
         */
        private HttpRequest buildUpstreamRequest(URI target, HttpExchange exchange) {
            HttpRequest.Builder request = HttpRequest.newBuilder(target).GET();

            for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                if (NOT_FORWARDED.contains(header.getKey().toLowerCase())) {
                    continue;
                }
                // A header sent more than once keeps all of its values, in order. Collapsing
                // them would change the meaning of a repeated Accept or Cookie.
                for (String value : header.getValue()) {
                    request.header(header.getKey(), value);
                }
            }

            return request.build();
        }

        /**
         * Runs the upstream request and translates a failure to reach the destination into the
         * status that describes it.
         *
         * <p>The distinction that matters: these are not this proxy failing, they are this
         * proxy reporting that the destination could not be reached. 5xx from the 502/504
         * family says exactly that, where a 500 would blame the wrong machine.
         */
        private HttpResponse<InputStream> sendUpstream(HttpRequest request) throws IOException {
            try {
                // ofInputStream, not ofByteArray: the body is handed over as a stream so it can
                // be relayed a chunk at a time. See relayBody.
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw new ProxyException(504, "Timed out contacting " + request.uri().getHost());
            } catch (IOException e) {
                // The chain has to be walked rather than caught by type: a failed lookup arrives
                // as a ConnectException with the real reason buried two levels down, so a catch
                // clause on that reason never runs - it is only ever a cause, never the throwable.
                //
                // Two types, because which one appears depends on how the address was resolved.
                // HttpClient's NIO path reports UnresolvedAddressException, which is not even an
                // IOException; UnknownHostException is what the blocking path throws. Checking
                // only the familiar one silently mislabels every DNS failure as a refused
                // connection, which points debugging at the wrong machine.
                if (causedBy(e, UnknownHostException.class) || causedBy(e, UnresolvedAddressException.class)) {
                    throw new ProxyException(502, "Cannot resolve host " + request.uri().getHost());
                }
                if (causedBy(e, ConnectException.class)) {
                    throw new ProxyException(502, "Cannot connect to " + request.uri().getAuthority()
                            + " - " + describe(e));
                }
                throw new ProxyException(502, "Upstream request to " + request.uri()
                        + " failed - " + describe(e));
            } catch (InterruptedException e) {
                // Restore the flag rather than swallow it: something asked this thread to stop
                // and the next blocking call must still see that.
                Thread.currentThread().interrupt();
                throw new ProxyException(502, "Interrupted while contacting " + request.uri().getHost());
            }
        }

        /** Sends the destination's status and headers back to the client, then its body. */
        private void relay(HttpExchange exchange, HttpResponse<InputStream> upstream,
                           InputStream upstreamBody) throws IOException {
            // Every response header must be set before sendResponseHeaders writes the status
            // line; anything added after it is silently dropped.
            for (Map.Entry<String, List<String>> header : upstream.headers().map().entrySet()) {
                if (NOT_FORWARDED.contains(header.getKey().toLowerCase())) {
                    continue;
                }
                exchange.getResponseHeaders().put(header.getKey(), List.copyOf(header.getValue()));
            }

            int status = upstream.statusCode();
            exchange.sendResponseHeaders(status, responseLength(status, upstream));

            if (!hasBody(status)) {
                return;
            }
            relayBody(upstreamBody, exchange.getResponseBody());
        }

        /**
         * Translates the destination's framing into the one argument HttpServer takes for it.
         *
         * <p>The three values are not sizes, they are modes: a positive number declares a
         * Content-Length, 0 asks for chunked encoding, and -1 means there is no body at all.
         * The unintuitive one is 0, which reads like "empty" and means the opposite.
         *
         * <p>So a destination that declared a length keeps it, and one that did not - it was
         * chunked, or it is closing the connection to mark the end - is re-chunked by this
         * server. Either way the length is never guessed at by reading the body first.
         */
        private long responseLength(int status, HttpResponse<InputStream> upstream) {
            if (!hasBody(status)) {
                return -1;
            }

            long declared = upstream.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > 0) {
                return declared;
            }
            if (declared == 0) {
                return -1;
            }
            return 0;
        }

        /**
         * 1xx, 204 and 304 are defined to carry no body, and a client stops reading after the
         * headers on all three. Announcing a body that never arrives would leave it waiting.
         */
        private boolean hasBody(int status) {
            return status >= 200 && status != 204 && status != 304;
        }

        /**
         * Copies the destination's body to the client one chunk at a time (level 2).
         *
         * <p>Nothing is accumulated: a chunk is read, written, and pushed out before the next
         * is asked for, so a 10MB image costs a 16KB buffer rather than 10MB of heap, and the
         * client starts receiving bytes while the destination is still sending them.
         *
         * <p>The flush is what makes that true rather than merely possible. Without it the
         * chunks pile up in the response stream's own buffer and leave in batches, which is the
         * buffering this level exists to avoid - just moved one layer down.
         */
        private void relayBody(InputStream upstreamBody, OutputStream clientBody) {
            long copied = 0;

            try (clientBody) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int justRead;
                while ((justRead = upstreamBody.read(buffer)) != -1) {
                    clientBody.write(buffer, 0, justRead);
                    clientBody.flush();
                    copied += justRead;
                }
            } catch (IOException e) {
                // Either end can land here - a client that hung up mid download, or a
                // destination that died mid body - and by this point the two are no longer
                // distinguishable from a single failed write. The message says so rather than
                // picking one, because the status line is already out either way: nothing can
                // be reported to the client and there is nothing to repair.
                throw new RelayFailure("after " + copied + " bytes, client or destination went away ("
                        + describe(e) + ")", e);
            }
        }

        /**
         * Sends a bodyless response.
         *
         * <p>getResponseCode() is -1 only while no status line has gone out. Sending a second
         * one throws from inside a catch block, which escapes handle() and drops the connection
         * with no response at all - so a failure part way through relaying would turn into
         * silence rather than a status.
         */
        private void respond(HttpExchange exchange, int status) throws IOException {
            if (exchange.getResponseCode() == -1) {
                exchange.sendResponseHeaders(status, -1);
            }
        }
    }
}
