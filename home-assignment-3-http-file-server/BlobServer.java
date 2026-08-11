import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public class BlobServer {

    private static final int PORT = 8000;
    private static final String PATH_PREFIX = "/blobs/";

    private static final Path STORAGE_DIR = Path.of("storage");
    private static final Path BLOB_DIR = STORAGE_DIR.resolve("blobs");
    // Under the same tree as its destination so the commit can be an atomic rename; a rename
    // across filesystems is a copy, which is exactly what must not happen here.
    private static final Path TMP_DIR = STORAGE_DIR.resolve("tmp");

    private static final int MAX_ID_LENGTH = 200;
    // "10MB" and "1GB" read as binary units, the usual convention for limits like this.
    private static final long MAX_PAYLOAD_LENGTH = 10L * 1024 * 1024;
    private static final long MAX_DISK_QUOTA = 1024L * 1024 * 1024;
    private static final long MAX_BLOBS_TOTAL = 1_000_000L;
    private static final int MAX_HEADER_KEY_LENGTH = 30;
    private static final int MAX_HEADER_VALUE_LENGTH = 400;
    private static final int MAX_HEADER_COUNT = 20;
    private static final int MAX_BLOBS_IN_FOLDER = 1000;

    // Three hex characters of the id's digest name the folder it lives in: 16^3 = 4096
    // folders, so a full store of 1,000,000 blobs averages 245 per folder, well inside the
    // 1000 cap. Measured over a million ids in several shapes the worst folder held 317.
    private static final int BUCKET_HEX_CHARS = 3;
    private static final int BUCKET_COUNT = 1 << (4 * BUCKET_HEX_CHARS);

    // Every blob file opens with the byte length of its header block, as eight ASCII digits
    // and a newline. Fixed width so the payload's offset is known after one small read,
    // without scanning for a delimiter that a header value might contain.
    private static final int HEADER_LENGTH_DIGITS = 8;
    private static final int PREFIX_LENGTH = HEADER_LENGTH_DIGITS + 1;

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String REBASE_PREFIX = "x-rebase-";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    public static void main(String[] args) throws IOException {
        // Catches a later edit to any of the three constants that would quietly break the
        // per-folder guarantee, at boot rather than at a million blobs.
        long averagePerFolder = MAX_BLOBS_TOTAL / BUCKET_COUNT;
        if (averagePerFolder >= MAX_BLOBS_IN_FOLDER) {
            throw new IllegalStateException(BUCKET_COUNT + " folders would average "
                    + averagePerFolder + " blobs each, at or over the " + MAX_BLOBS_IN_FOLDER + " cap");
        }

        Files.createDirectories(BLOB_DIR);
        Files.createDirectories(TMP_DIR);

        // Cleanup before the scan, so the totals describe storage after the wreckage of any
        // interrupted request has been swept up. Both run before the socket is even created,
        // which is how the server avoids answering while its figures are still unknown.
        cleanInterruptedWrites();
        Quota quota = scanStorage();
        System.out.println("Warm-up complete: " + quota);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Trailing slash is required. HttpServer matches contexts by prefix, so "/blobs"
        // would also route "/blobsomething" here - a path that is not part of this API.
        server.createContext(PATH_PREFIX, new BlobHandler(quota));

        // Without an executor HttpServer serves one request at a time. Virtual threads fit
        // this workload: mostly blocked on file IO, almost no CPU.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Must be last. start() does not block; the server's non-daemon threads keep the JVM
        // alive after main returns.
        server.start();
        System.out.println("BlobServer is running on port " + PORT);
    }

    /**
     * The one place a blob id becomes a file path, which is what let bucketing be introduced
     * without touching a single handler.
     */
    private static Path pathFor(String blobId) {
        return BLOB_DIR.resolve(bucketFor(blobId)).resolve(blobId);
    }

    /**
     * Names the folder a blob belongs in, from the first 12 bits of its SHA-256 digest.
     *
     * <p>A pure function of the id, so GET and DELETE recompute the same folder with no index
     * to consult and nothing to keep in sync.
     *
     * <p>SHA-256 rather than String.hashCode because folder occupancy is a hard cap, not a
     * preference. Measured over a million ids, hashCode's upper bits put 14,638 sequential
     * ids in one folder while leaving 4001 folders empty; its lower bits behave, but
     * colliding hashCode inputs are trivial to construct, so a client could aim at one folder
     * deliberately. A digest costs a few microseconds against a file write that costs more.
     */
    private static String bucketFor(String blobId) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(blobId.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform, so this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        int bits = ((digest[0] & 0xFF) << 4) | ((digest[1] & 0xF0) >>> 4);
        return String.format("%0" + BUCKET_HEX_CHARS + "x", bits);
    }

    /**
     * Discards uploads that never committed.
     *
     * <p>A file left in the temp directory is a payload whose request died before its rename:
     * nothing refers to it and it was never counted against the quota. It cannot be repaired,
     * only dropped, and dropping it is what lets the scan that follows produce a total that
     * matches what a client can actually retrieve.
     *
     * <p>There is no half-written blob to find. A blob becomes visible through one rename, so
     * either it is entirely there or it is not there at all.
     */
    private static void cleanInterruptedWrites() throws IOException {
        long removed = 0;

        try (Stream<Path> temps = Files.walk(TMP_DIR)) {
            for (Path temp : (Iterable<Path>) temps.filter(Files::isRegularFile)::iterator) {
                Files.delete(temp);
                removed++;
            }
        }

        if (removed > 0) {
            System.out.println("Discarded " + removed + " upload(s) that never committed");
        }
        pruneEmptyBuckets();
    }

    /**
     * Drops bucket folders whose last blob has been deleted.
     *
     * <p>Only tidiness - an empty folder costs an inode and nothing else - but it keeps a
     * listing of the storage tree honest about what is actually stored.
     */
    private static void pruneEmptyBuckets() throws IOException {
        try (Stream<Path> tree = Files.walk(BLOB_DIR)) {
            // Deepest first, so a folder is visited only after anything inside it is gone.
            List<Path> folders = tree.filter(Files::isDirectory)
                    .filter(path -> !path.equals(BLOB_DIR))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();

            for (Path folder : folders) {
                try (Stream<Path> contents = Files.list(folder)) {
                    if (contents.findAny().isEmpty()) {
                        Files.delete(folder);
                    }
                }
            }
        }
    }

    /**
     * Totals the stored bytes and blob count already on disk.
     *
     * <p>File size, not payload size: the quota is "overall disk space", and the header block
     * occupies disk too. Reading it straight from the directory entry keeps warm-up to one
     * stat per blob rather than opening a million files to subtract their headers.
     */
    private static Quota scanStorage() throws IOException {
        long usedBytes = 0;
        long blobCount = 0;

        try (Stream<Path> files = Files.walk(BLOB_DIR)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                usedBytes += Files.size(file);
                blobCount++;
            }
        }
        return new Quota(usedBytes, blobCount);
    }

    /**
     * Carries the HTTP status the client should receive, so validation deep in the call
     * stack can reject a request without every caller in between forwarding an error.
     */
    static class BlobException extends RuntimeException {
        private final int statusCode;

        BlobException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int getStatusCode() {
            return statusCode;
        }
    }

    /**
     * Stops reading a request body the moment it exceeds the limit.
     *
     * <p>Content-Length is only the client's claim. Without this, a request that declares a
     * small size and then sends gigabytes would be written to disk in full before anyone
     * noticed - the limit has to be enforced against the bytes themselves.
     */
    static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long consumed;

        BoundedInputStream(InputStream source, long limit) {
            super(source);
            this.limit = limit;
        }

        private void count(long justRead) {
            if (justRead < 0) {
                return;
            }
            consumed += justRead;
            if (consumed > limit) {
                throw new BlobException(413, "Payload exceeds the " + limit + " byte limit");
            }
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            count(b < 0 ? -1 : 1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int justRead = in.read(buffer, offset, length);
            count(justRead);
            return justRead;
        }
    }

    /**
     * Running totals for the two limits that cannot be answered from a single request.
     *
     * <p>Every method is synchronised. The spec rules out concurrent requests to the same id,
     * but not concurrent requests overall, and these totals are shared by all of them. A
     * plain check-then-write would let two POSTs both see room and together exceed the limit,
     * so space is reserved up front and handed back if the write turns out smaller or fails.
     */
    static final class Quota {
        private long usedBytes;
        private long blobCount;

        Quota(long usedBytes, long blobCount) {
            this.usedBytes = usedBytes;
            this.blobCount = blobCount;
        }

        /** Claims space for a pending write, or rejects the request if it does not fit. */
        synchronized void reserve(long deltaBytes, boolean newBlob) {
            if (usedBytes + deltaBytes > MAX_DISK_QUOTA) {
                throw new BlobException(507, "Storing this blob would use "
                        + (usedBytes + deltaBytes) + " bytes, over the " + MAX_DISK_QUOTA + " byte quota");
            }
            if (newBlob && blobCount + 1 > MAX_BLOBS_TOTAL) {
                throw new BlobException(507, "Blob count is at the limit of " + MAX_BLOBS_TOTAL);
            }

            usedBytes += deltaBytes;
            if (newBlob) {
                blobCount++;
            }
        }

        synchronized void release(long bytes, boolean removeBlob) {
            usedBytes -= bytes;
            if (removeBlob) {
                blobCount--;
            }
        }

        @Override
        public synchronized String toString() {
            return blobCount + " blobs, " + usedBytes + " bytes used of " + MAX_DISK_QUOTA;
        }
    }

    static class BlobHandler implements HttpHandler {

        private final Quota quota;

        BlobHandler(Quota quota) {
            this.quota = quota;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            // Nesting is deliberate: try-with-resources closes its resource before any catch
            // runs, so the inner catch must send the response while the exchange is still open.
            try (exchange) {
                try {
                    switch (method) {
                        case "GET" -> handleGet(exchange);
                        case "POST" -> handlePost(exchange);
                        case "DELETE" -> handleDelete(exchange);
                        default -> respond(exchange, 405);
                    }
                } catch (BlobException e) {
                    // The client sent something invalid and it was handled. Normal traffic,
                    // so one line - a stack trace per bad request would bury real failures.
                    System.err.println(e.getStatusCode() + " " + method + " "
                            + exchange.getRequestURI().getPath() + " - " + e.getMessage());
                    respond(exchange, e.getStatusCode());
                } catch (Exception e) {
                    // Anything else is a bug in this server, so it gets the full stack.
                    e.printStackTrace();
                    respond(exchange, 500);
                }
            }
        }

        /**
         * Sends a bodyless response.
         *
         * <p>The -1 length means "no body". 0 would mean "body of unknown length, use chunked
         * encoding", which is the opposite of what it reads like.
         *
         * <p>getResponseCode() is -1 only while no status line has gone out. Sending a second
         * one throws from inside a catch block, which escapes handle() and drops the
         * connection with no response at all.
         */
        private void respond(HttpExchange exchange, int status) throws IOException {
            if (exchange.getResponseCode() == -1) {
                exchange.sendResponseHeaders(status, -1);
            }
        }

        /**
         * Extracts and validates the blob id. Every handler goes through here, so validation
         * cannot be skipped by forgetting to call it.
         */
        private String extractBlobId(HttpExchange exchange) {
            // getPath() is percent-decoded, so "%2F" arrives here as a real "/". Validation
            // runs on this decoded value because that is what would reach the filesystem.
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(PATH_PREFIX)) {
                // Unreachable: the registered context guarantees the prefix. This asserts our
                // own routing, not client input, so it must surface as 500 rather than 400.
                throw new IllegalStateException("Path outside the registered context: " + path);
            }

            String blobId = path.substring(PATH_PREFIX.length());
            validateBlobId(blobId);
            return blobId;
        }

        private void validateBlobId(String blobId) {
            if (blobId == null || blobId.isEmpty()) {
                throw new BlobException(400, "Blob id must not be empty");
            }
            // Before the character scan: an O(1) test in front of an O(n) walk.
            if (blobId.length() > MAX_ID_LENGTH) {
                throw new BlobException(400, "Blob id exceeds " + MAX_ID_LENGTH + " characters");
            }
            // Exact match, not contains. "." and ".." are dangerous because they *are*
            // directory names, not because they contain dots - "a..b" is a legal id. They need
            // their own check because the character scan below accepts dots.
            if (blobId.equals(".") || blobId.equals("..")) {
                throw new BlobException(400, "Blob id must not be a directory reference");
            }
            for (char c : blobId.toCharArray()) {
                if (!isValidBlobIdChar(c)) {
                    throw new BlobException(400, "Blob id contains invalid character: " + c);
                }
            }
        }

        // Explicit ASCII ranges, not Character.isLetterOrDigit - that returns true for Hebrew,
        // accented Latin and Arabic-Indic digits, which the spec disallows.
        private boolean isValidBlobIdChar(char c) {
            return (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
        }

        /**
         * Rejects a payload the server will not accept before reading any of it, and returns
         * the declared size so the quota can be reserved against it.
         *
         * <p>A declared size is a claim, not a fact; BoundedInputStream enforces the same
         * limit against the bytes that actually arrive.
         */
        private long validateContentLength(HttpExchange exchange) {
            // Header lookups are case-insensitive, so "content-length" matches too.
            String raw = exchange.getRequestHeaders().getFirst("Content-Length");
            if (raw == null) {
                // 411 is the status defined for exactly this case.
                throw new BlobException(411, "Content-Length header is required");
            }

            long declared;
            try {
                declared = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new BlobException(400, "Content-Length is not a number: " + raw);
            }

            if (declared < 0) {
                throw new BlobException(400, "Content-Length is negative: " + declared);
            }
            if (declared > MAX_PAYLOAD_LENGTH) {
                throw new BlobException(413,
                        "Payload of " + declared + " bytes exceeds the " + MAX_PAYLOAD_LENGTH + " byte limit");
            }
            return declared;
        }

        /**
         * Picks out the headers worth keeping and checks them against the spec's limits.
         *
         * <p>Runs before anything is written, so a request rejected on its headers leaves no
         * files behind.
         *
         * <p>The map is case-insensitive because HttpServer rewrites header names to its own
         * casing - "Content-Type" arrives as "Content-type" - so a case-sensitive lookup for
         * the constant would silently miss and fall through to the default content type.
         * Case-insensitive matching is what the spec asks for regardless. A header sent more
         * than once keeps its first value and counts once.
         */
        private Map<String, String> collectStoredHeaders(HttpExchange exchange) {
            Map<String, String> stored = caseInsensitiveMap();

            for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                String key = header.getKey();
                String lowerKey = key.toLowerCase();

                boolean worthStoring = lowerKey.equals(CONTENT_TYPE.toLowerCase())
                        || lowerKey.startsWith(REBASE_PREFIX);
                if (!worthStoring) {
                    continue;
                }

                if (key.length() > MAX_HEADER_KEY_LENGTH) {
                    throw new BlobException(431,
                            "Header name exceeds " + MAX_HEADER_KEY_LENGTH + " characters: " + key);
                }

                String value = header.getValue().isEmpty() ? "" : header.getValue().get(0);
                if (value.length() > MAX_HEADER_VALUE_LENGTH) {
                    throw new BlobException(431,
                            "Value of header " + key + " exceeds " + MAX_HEADER_VALUE_LENGTH + " characters");
                }

                stored.put(key, value);
            }

            // The limit counts stored headers, not the ones that arrived: a request with fifty
            // headers is fine if only three of them are ones we keep.
            if (stored.size() > MAX_HEADER_COUNT) {
                throw new BlobException(431,
                        "Storing " + stored.size() + " headers exceeds the limit of " + MAX_HEADER_COUNT);
            }

            return stored;
        }

        /** Header names are case-insensitive; see collectStoredHeaders. */
        private Map<String, String> caseInsensitiveMap() {
            return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        }

        /**
         * Renders the stored headers as the block that precedes the payload: one
         * "Name: value" per line.
         *
         * <p>Safe as a line-based format because HTTP header values cannot contain a line
         * break - a request carrying one is rejected before it ever reaches this handler.
         */
        private byte[] encodeHeaderBlock(Map<String, String> headers) {
            StringBuilder block = new StringBuilder();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                block.append(header.getKey()).append(": ").append(header.getValue()).append('\n');
            }
            return block.toString().getBytes(StandardCharsets.UTF_8);
        }

        private Map<String, String> decodeHeaderBlock(byte[] block) {
            Map<String, String> headers = caseInsensitiveMap();
            String text = new String(block, StandardCharsets.UTF_8);

            for (String line : text.split("\n")) {
                // Split on the first separator only: a value may legitimately contain ": ".
                int separator = line.indexOf(": ");
                if (separator > 0) {
                    headers.put(line.substring(0, separator), line.substring(separator + 2));
                }
            }
            return headers;
        }

        /**
         * The spec allows a fixed application/octet-stream, or an attempt to infer the type.
         * probeContentType infers from the filename and costs no dependency, so it is tried
         * first and the fixed value is the fallback.
         */
        private String contentTypeFor(Path blobPath, Map<String, String> storedHeaders) throws IOException {
            String stored = storedHeaders.get(CONTENT_TYPE);
            if (stored != null) {
                return stored;
            }

            String probed = Files.probeContentType(blobPath);
            return probed != null ? probed : DEFAULT_CONTENT_TYPE;
        }

        private void handleGet(HttpExchange exchange) throws IOException {
            String blobId = extractBlobId(exchange);
            Path blobPath = pathFor(blobId);

            if (!Files.exists(blobPath)) {
                respond(exchange, 404);
                return;
            }

            long fileSize = Files.size(blobPath);

            try (InputStream file = Files.newInputStream(blobPath)) {
                int headerLength = readHeaderLength(file, blobPath);
                Map<String, String> storedHeaders = decodeHeaderBlock(readExactly(file, headerLength, blobPath));

                // Response headers must all be set before sendResponseHeaders writes the
                // status line; anything added afterwards is silently dropped.
                for (Map.Entry<String, String> header : storedHeaders.entrySet()) {
                    exchange.getResponseHeaders().set(header.getKey(), header.getValue());
                }
                exchange.getResponseHeaders().set(CONTENT_TYPE, contentTypeFor(blobPath, storedHeaders));

                // An empty blob has to be announced as -1, not 0: a length of 0 puts the
                // response into chunked encoding rather than declaring an empty body.
                long payloadLength = fileSize - PREFIX_LENGTH - headerLength;
                exchange.sendResponseHeaders(200, payloadLength == 0 ? -1 : payloadLength);

                // The stream is already positioned at the first payload byte, so the rest
                // copies straight through in fixed-size chunks. Serving a 10MB blob costs a
                // buffer, not 10MB of heap per concurrent request.
                try (OutputStream body = exchange.getResponseBody()) {
                    file.transferTo(body);
                }
            }
        }

        private int readHeaderLength(InputStream file, Path blobPath) throws IOException {
            String prefix = new String(readExactly(file, PREFIX_LENGTH, blobPath), StandardCharsets.US_ASCII);
            try {
                return Integer.parseInt(prefix.substring(0, HEADER_LENGTH_DIGITS));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Blob " + blobPath + " has an unreadable header length", e);
            }
        }

        /**
         * Reads exactly the requested count, or fails.
         *
         * <p>A short read here means the stored file is truncated. Writes commit by rename so
         * that should be impossible, which is why it surfaces as a 500 with a stack trace
         * rather than being quietly tolerated.
         */
        private byte[] readExactly(InputStream file, int count, Path blobPath) throws IOException {
            byte[] bytes = file.readNBytes(count);
            if (bytes.length != count) {
                throw new IllegalStateException("Blob " + blobPath + " is truncated: expected "
                        + count + " bytes, found " + bytes.length);
            }
            return bytes;
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            String blobId = extractBlobId(exchange);
            long declaredLength = validateContentLength(exchange);
            byte[] headerBlock = encodeHeaderBlock(collectStoredHeaders(exchange));

            Path blobPath = pathFor(blobId);
            // An overwrite only consumes the difference; the bytes already on disk are being
            // replaced, not added to.
            boolean isNewBlob = !Files.exists(blobPath);
            long existingSize = isNewBlob ? 0 : Files.size(blobPath);

            // Reserved before a byte is read, so two concurrent POSTs cannot both find room
            // and together exceed the quota. The header block counts: it occupies disk too.
            long declaredFileSize = PREFIX_LENGTH + headerBlock.length + declaredLength;
            long outstanding = declaredFileSize - existingSize;
            quota.reserve(outstanding, isNewBlob);

            Path temp = Files.createTempFile(TMP_DIR, blobId + "-", ".upload");
            boolean committed = false;

            try {
                long actualFileSize = writeBlobFile(exchange, temp, headerBlock);

                // The temp file is the truth; hand back whatever the declared length
                // over-reserved before anything is committed.
                quota.release(declaredFileSize - actualFileSize, false);
                outstanding = actualFileSize - existingSize;

                // The blob's bucket folder may not exist yet; creating it first keeps the
                // commit itself a single rename.
                Files.createDirectories(blobPath.getParent());

                // One rename within one filesystem, and it is atomic. A reader sees either
                // the previous blob or this one, never a mixture and never a partial file -
                // headers and payload become visible in the same instant because they are
                // the same file.
                Files.move(temp, blobPath, StandardCopyOption.ATOMIC_MOVE);
                committed = true;
            } finally {
                if (!committed) {
                    // Nothing reached the blob tree, so the whole reservation goes back and
                    // the partial upload is discarded.
                    quota.release(outstanding, isNewBlob);
                    Files.deleteIfExists(temp);
                }
            }

            // 200, not 201: this endpoint is an upsert, and 201 would claim creation on every
            // overwrite.
            respond(exchange, 200);
        }

        /**
         * Writes the length prefix, the header block and the streamed payload into one file,
         * and returns its total size.
         *
         * <p>Nothing accumulates in memory, so peak usage is a buffer rather than the payload,
         * and writing to a temp file means a request that dies half way leaves no partial blob
         * where a client could read it.
         */
        private long writeBlobFile(HttpExchange exchange, Path temp, byte[] headerBlock) throws IOException {
            // "\n" literally, not %n: the separator is part of an on-disk format and must not
            // vary with the platform the server happens to run on.
            byte[] prefix = (String.format("%0" + HEADER_LENGTH_DIGITS + "d", headerBlock.length) + "\n")
                    .getBytes(StandardCharsets.US_ASCII);

            try (OutputStream out = Files.newOutputStream(temp);
                 InputStream body = new BoundedInputStream(exchange.getRequestBody(), MAX_PAYLOAD_LENGTH)) {
                out.write(prefix);
                out.write(headerBlock);
                long payloadLength = body.transferTo(out);
                return prefix.length + headerBlock.length + payloadLength;
            }
        }

        private void handleDelete(HttpExchange exchange) throws IOException {
            String blobId = extractBlobId(exchange);
            Path blobPath = pathFor(blobId);

            // Size has to be read before the file goes away, and only a blob that really
            // existed may be subtracted from the totals.
            boolean existed = Files.exists(blobPath);
            long freedBytes = existed ? Files.size(blobPath) : 0;

            // One file, so one delete - there is no second half to leave behind. The spec
            // explicitly excuses 404 here, so deleteIfExists keeps DELETE idempotent: the
            // same 204 whether or not the blob was there.
            Files.deleteIfExists(blobPath);

            if (existed) {
                quota.release(freedBytes, true);
            }
            respond(exchange, 204);
        }
    }
}
