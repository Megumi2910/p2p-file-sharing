package vn.edu.p2p.peer.update;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReleaseClient {

    public enum CheckStatus {
        AVAILABLE,
        UP_TO_DATE,
        NO_RELEASE,
        UNAVAILABLE,
        INVALID_RELEASE,
        UNSUPPORTED
    }

    public static class Cancellation implements AutoCloseable {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final List<Runnable> hooks = new ArrayList<>();

        public synchronized void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                for (Runnable hook : hooks) {
                    try {
                        hook.run();
                    } catch (Throwable ignored) {
                    }
                }
                hooks.clear();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public synchronized void onCancel(Runnable hook) {
            if (hook == null) return;
            if (cancelled.get()) {
                hook.run();
            } else {
                hooks.add(hook);
            }
        }

        @Override
        public void close() {
            cancel();
        }
    }

    public record ValidatedCandidate(
            ClientVersion version,
            ReleaseManifest manifest,
            byte[] manifestBytes,
            byte[] signatureBytes,
            String jarDownloadUrl,
            long jarSize,
            String zipDownloadUrl,
            long zipSize
    ) {}

    public record CheckResult(
            CheckStatus status,
            ClientVersion latestVersion,
            String releaseNotes,
            ValidatedCandidate candidate,
            String message
    ) {
        public static CheckResult available(ClientVersion version, String notes, ValidatedCandidate candidate) {
            return new CheckResult(CheckStatus.AVAILABLE, version, notes, candidate, "Update available: v" + version);
        }

        public static CheckResult upToDate(ClientVersion currentVersion) {
            return new CheckResult(CheckStatus.UP_TO_DATE, currentVersion, "", null, "Application is up to date");
        }

        public static CheckResult noRelease(String message) {
            return new CheckResult(CheckStatus.NO_RELEASE, null, "", null, message);
        }

        public static CheckResult unavailable(String message) {
            return new CheckResult(CheckStatus.UNAVAILABLE, null, "", null, message);
        }

        public static CheckResult invalidRelease(String message) {
            return new CheckResult(CheckStatus.INVALID_RELEASE, null, "", null, message);
        }

        public static CheckResult unsupported(ClientVersion version, String notes, String message) {
            return new CheckResult(CheckStatus.UNSUPPORTED, version, notes, null, message);
        }
    }

    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    public interface HttpTransport {
        TransportResponse send(
                URI uri,
                String method,
                Map<String, String> headers,
                Duration timeout,
                Cancellation cancellation
        ) throws IOException, InterruptedException;
    }

    public record TransportResponse(
            int statusCode,
            Map<String, List<String>> headers,
            InputStream bodyStream
    ) implements AutoCloseable {
        public String firstHeader(String name) {
            List<String> values = headers.get(name);
            if (values == null || values.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                        return entry.getValue().getFirst();
                    }
                }
                return null;
            }
            return values.getFirst();
        }

        public byte[] readAllBytesBounded(int maxBytes) throws IOException {
            try (InputStream in = bodyStream) {
                byte[] buf = new byte[Math.min(maxBytes + 1, 8192)];
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int total = 0;
                int read;
                while ((read = in.read(buf)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException("Response body exceeded maximum allowed bytes: " + maxBytes);
                    }
                    out.write(buf, 0, read);
                }
                return out.toByteArray();
            }
        }

        @Override
        public void close() throws IOException {
            if (bodyStream != null) {
                bodyStream.close();
            }
        }
    }

    public static final String GITHUB_API_LATEST = "https://api.github.com/repos/Megumi2910/p2p-file-sharing/releases/latest";
    public static final String EXPECTED_REPOSITORY = "Megumi2910/p2p-file-sharing";
    public static final int MAX_API_JSON_BYTES = 1024 * 1024;
    public static final int MAX_RELEASE_NOTES_BYTES = 32768;
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    public static final Duration METADATA_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration JAR_TIMEOUT = Duration.ofMinutes(5);

    private static final Set<String> ALLOWED_STORAGE_HOSTS = Set.of(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com"
    );

    private final BuildInfo buildInfo;
    private final HttpTransport transport;
    private final URI latestReleaseEndpoint;

    public ReleaseClient(BuildInfo buildInfo) {
        this(buildInfo, new DefaultHttpTransport(), URI.create(GITHUB_API_LATEST));
    }

    ReleaseClient(BuildInfo buildInfo, HttpTransport transport, URI latestReleaseEndpoint) {
        this.buildInfo = Objects.requireNonNull(buildInfo, "buildInfo cannot be null");
        this.transport = Objects.requireNonNull(transport, "transport cannot be null");
        this.latestReleaseEndpoint = Objects.requireNonNull(latestReleaseEndpoint, "latestReleaseEndpoint cannot be null");
    }

    public BuildInfo buildInfo() {
        return buildInfo;
    }

    public CheckResult check() {
        return check(null);
    }

    public CheckResult check(Cancellation cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            return CheckResult.unavailable("Update check cancelled");
        }

        long deadlineNanos = System.nanoTime() + METADATA_TIMEOUT.toNanos();

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/vnd.github+json");
            headers.put("User-Agent", "P2P-File-Sharing-Client/" + buildInfo.displayVersion() + " (Java 21)");

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return CheckResult.unavailable("Update check timed out");
            }
            Duration timeout = Duration.ofNanos(remainingNanos);

            byte[] jsonBytes;
            try (TransportResponse resp = transport.send(latestReleaseEndpoint, "GET", headers, timeout, cancellation)) {
                int code = resp.statusCode();
                if (code == 404) {
                    return CheckResult.noRelease("No published release");
                }
                if (code == 403 || code == 429) {
                    String reset = resp.firstHeader("x-ratelimit-reset");
                    String retryAfter = resp.firstHeader("retry-after");
                    String detail = parseRetryReset(retryAfter, reset);
                    return CheckResult.unavailable("Checking unavailable: " + detail);
                }
                if (code != 200) {
                    return CheckResult.unavailable("GitHub API returned HTTP " + code);
                }
                jsonBytes = resp.readAllBytesBounded(MAX_API_JSON_BYTES);
            }

            GitHubRelease release;
            try {
                release = parseGitHubRelease(jsonBytes);
            } catch (Exception ex) {
                return CheckResult.invalidRelease("Malformed release metadata: " + ex.getMessage());
            }
            if (release.draft() || release.prerelease()) {
                return CheckResult.noRelease("Latest release is marked draft or prerelease");
            }

            ClientVersion latestVersion;
            try {
                latestVersion = ClientVersion.parseTag(release.tagName());
            } catch (Exception ex) {
                return CheckResult.invalidRelease("Release tag is not a valid version: " + release.tagName());
            }

            String notes = boundReleaseNotes(release.body());

            if (buildInfo.isDevelopment()) {
                return CheckResult.unsupported(latestVersion, notes, "Development build does not support automated updates");
            }

            if (buildInfo.version() != null && buildInfo.version().compareTo(latestVersion) >= 0) {
                return CheckResult.upToDate(buildInfo.version());
            }

            if (!buildInfo.canInstall() || buildInfo.publicKey() == null) {
                return CheckResult.unsupported(latestVersion, notes, "Installation disabled: missing or invalid public key");
            }

            Map<String, GitHubAsset> assets = release.assets();
            String expectedZip = "p2p-client-" + latestVersion + ".zip";
            if (!assets.containsKey("peer-app.jar")
                    || !assets.containsKey(expectedZip)
                    || !assets.containsKey("update-manifest.json")
                    || !assets.containsKey("update-manifest.sig")) {
                return CheckResult.invalidRelease("Release missing required update assets");
            }

            GitHubAsset jarAsset = assets.get("peer-app.jar");
            GitHubAsset zipAsset = assets.get(expectedZip);
            GitHubAsset manifestAsset = assets.get("update-manifest.json");
            GitHubAsset sigAsset = assets.get("update-manifest.sig");

            validateInitialDownloadUrl(URI.create(manifestAsset.downloadUrl()), latestVersion, "update-manifest.json");
            validateInitialDownloadUrl(URI.create(sigAsset.downloadUrl()), latestVersion, "update-manifest.sig");
            validateInitialDownloadUrl(URI.create(jarAsset.downloadUrl()), latestVersion, "peer-app.jar");
            validateInitialDownloadUrl(URI.create(zipAsset.downloadUrl()), latestVersion, expectedZip);

            long manifestDeadline = System.nanoTime() + METADATA_TIMEOUT.toNanos();
            byte[] manifestBytes = downloadBoundedBytes(URI.create(manifestAsset.downloadUrl()), latestVersion, "update-manifest.json", ReleaseManifest.MAX_MANIFEST_BYTES, manifestDeadline, cancellation);

            long sigDeadline = System.nanoTime() + METADATA_TIMEOUT.toNanos();
            byte[] sigBytes = downloadBoundedBytes(URI.create(sigAsset.downloadUrl()), latestVersion, "update-manifest.sig", ReleaseManifest.EXPECTED_SIGNATURE_BYTES, sigDeadline, cancellation);

            if (sigBytes.length != ReleaseManifest.EXPECTED_SIGNATURE_BYTES) {
                return CheckResult.invalidRelease("Signature length invalid: " + sigBytes.length);
            }

            ReleaseManifest manifest;
            try {
                manifest = ReleaseManifest.parseAndVerify(manifestBytes, sigBytes, buildInfo.publicKey(), EXPECTED_REPOSITORY);
            } catch (SecurityException ex) {
                return CheckResult.invalidRelease("Manifest signature verification failed: " + ex.getMessage());
            } catch (Exception ex) {
                return CheckResult.invalidRelease("Manifest validation failed: " + ex.getMessage());
            }

            if (!manifest.version().equals(latestVersion)) {
                return CheckResult.invalidRelease("Manifest version (" + manifest.version() + ") does not match tag (" + latestVersion + ")");
            }
            if (manifest.size() != jarAsset.size()) {
                return CheckResult.invalidRelease("Manifest JAR size (" + manifest.size() + ") does not match release asset size (" + jarAsset.size() + ")");
            }
            if (manifest.bundle().size() != zipAsset.size()) {
                return CheckResult.invalidRelease("Manifest bundle size (" + manifest.bundle().size() + ") does not match release asset size (" + zipAsset.size() + ")");
            }

            ValidatedCandidate candidate = new ValidatedCandidate(
                    latestVersion,
                    manifest,
                    manifestBytes,
                    sigBytes,
                    jarAsset.downloadUrl(),
                    jarAsset.size(),
                    zipAsset.downloadUrl(),
                    zipAsset.size()
            );

            return CheckResult.available(latestVersion, notes, candidate);
        } catch (IOException | InterruptedException ex) {
            return CheckResult.unavailable("Update check failed: " + ex.getMessage());
        } catch (Exception ex) {
            return CheckResult.invalidRelease("Unexpected error during update check: " + ex.getMessage());
        }
    }

    private static String parseRetryReset(String retryAfter, String reset) {
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long secs = Long.parseLong(retryAfter.trim());
                if (secs >= 0) {
                    return "retry after " + secs + "s";
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (reset != null && !reset.isBlank()) {
            try {
                long epoch = Long.parseLong(reset.trim());
                if (epoch > 0) {
                    return "reset at " + epoch;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return "rate limit exceeded";
    }

    public void downloadCandidateJar(
            ValidatedCandidate candidate,
            Path destination,
            ProgressListener progressListener,
            Cancellation cancellation
    ) throws Exception {
        Objects.requireNonNull(candidate, "candidate cannot be null");
        Objects.requireNonNull(destination, "destination cannot be null");

        if (cancellation != null && cancellation.isCancelled()) {
            throw new IOException("Download cancelled by user");
        }

        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        URI currentUri = URI.create(candidate.jarDownloadUrl());
        validateInitialDownloadUrl(currentUri, candidate.version(), "peer-app.jar");

        Set<URI> visited = new HashSet<>();
        visited.add(currentUri);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long totalRead = 0;
        long expectedSize = candidate.jarSize();

        long deadlineNanos = System.nanoTime() + JAR_TIMEOUT.toNanos();

        try (OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int redirects = 0;
            while (true) {
                if (cancellation != null && cancellation.isCancelled()) {
                    throw new IOException("Download cancelled by user");
                }

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new IOException("Download timed out (exceeded 5-minute budget)");
                }
                Duration timeout = Duration.ofNanos(remainingNanos);

                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "P2P-File-Sharing-Client/" + buildInfo.displayVersion());

                try (TransportResponse resp = transport.send(currentUri, "GET", headers, timeout, cancellation)) {
                    int status = resp.statusCode();
                    if (status >= 300 && status < 400) {
                        redirects++;
                        if (redirects > 3) {
                            throw new IOException("Too many redirects (> 3)");
                        }
                        String location = resp.firstHeader("location");
                        if (location == null || location.isBlank()) {
                            throw new IOException("Redirect missing Location header");
                        }
                        URI nextUri = currentUri.resolve(location);
                        validateRedirect(currentUri, nextUri, candidate.version(), "peer-app.jar", visited);
                        visited.add(nextUri);
                        currentUri = nextUri;
                        continue;
                    }

                    if (status != 200) {
                        throw new IOException("Download failed with HTTP " + status);
                    }

                    String encoding = resp.firstHeader("content-encoding");
                    if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.trim())) {
                        throw new IOException("Unexpected Content-Encoding: " + encoding);
                    }

                    String clHeader = resp.firstHeader("content-length");
                    if (clHeader != null && !clHeader.isBlank()) {
                        try {
                            long cl = Long.parseLong(clHeader.trim());
                            if (cl != expectedSize) {
                                throw new IOException("Content-Length mismatch: expected " + expectedSize + " but got " + cl);
                            }
                        } catch (NumberFormatException ex) {
                            throw new IOException("Invalid Content-Length header: " + clHeader);
                        }
                    }

                    byte[] buffer = new byte[64 * 1024];
                    InputStream in = resp.bodyStream();
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancellation != null && cancellation.isCancelled()) {
                            throw new IOException("Download cancelled by user");
                        }
                        if (System.nanoTime() > deadlineNanos) {
                            throw new IOException("Download timed out (exceeded 5-minute budget)");
                        }
                        totalRead += read;
                        if (totalRead > expectedSize) {
                            throw new IOException("Download size exceeded signed manifest size: " + expectedSize);
                        }
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                        if (progressListener != null) {
                            progressListener.onProgress(totalRead, expectedSize);
                        }
                    }
                    break;
                }
            }
        } catch (Exception ex) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException ignored) {
            }
            throw ex;
        }

        if (totalRead != expectedSize) {
            Files.deleteIfExists(destination);
            throw new IOException("Downloaded JAR size mismatch: expected " + expectedSize + " but got " + totalRead);
        }

        String computedSha256 = HexFormat.of().formatHex(digest.digest());
        if (!computedSha256.equalsIgnoreCase(candidate.manifest().sha256())) {
            Files.deleteIfExists(destination);
            throw new SecurityException("Downloaded JAR SHA-256 mismatch: expected "
                    + candidate.manifest().sha256() + " but computed " + computedSha256);
        }

        try (FileChannel fc = FileChannel.open(destination, StandardOpenOption.WRITE)) {
            fc.force(true);
        }

        try {
            ReleaseManifest.verifyJar(destination, candidate.manifest(), buildInfo.publicKey());
        } catch (Exception ex) {
            Files.deleteIfExists(destination);
            throw ex;
        }
    }

    private byte[] downloadBoundedBytes(
            URI initialUri,
            ClientVersion version,
            String assetName,
            int maxBytes,
            long deadlineNanos,
            Cancellation cancellation
    ) throws IOException, InterruptedException {
        URI current = initialUri;
        Set<URI> visited = new HashSet<>();
        visited.add(current);

        int redirects = 0;
        while (true) {
            if (cancellation != null && cancellation.isCancelled()) {
                throw new IOException("Download cancelled by user");
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IOException("Metadata request timed out");
            }
            Duration timeout = Duration.ofNanos(remainingNanos);

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "P2P-File-Sharing-Client/" + buildInfo.displayVersion());

            try (TransportResponse resp = transport.send(current, "GET", headers, timeout, cancellation)) {
                int status = resp.statusCode();
                if (status >= 300 && status < 400) {
                    redirects++;
                    if (redirects > 3) {
                        throw new IOException("Too many redirects (> 3)");
                    }
                    String location = resp.firstHeader("location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirect missing Location header");
                    }
                    URI nextUri = current.resolve(location);
                    validateRedirect(current, nextUri, version, assetName, visited);
                    visited.add(nextUri);
                    current = nextUri;
                    continue;
                }

                if (status != 200) {
                    throw new IOException("Failed to download " + assetName + " (HTTP " + status + ")");
                }

                return resp.readAllBytesBounded(maxBytes);
            }
        }
    }

    public static void validateInitialDownloadUrl(URI uri, ClientVersion version, String assetName) {
        Objects.requireNonNull(uri, "uri cannot be null");
        Objects.requireNonNull(version, "version cannot be null");
        Objects.requireNonNull(assetName, "assetName cannot be null");

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Invalid scheme (must be https): " + uri);
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Non-443 port not allowed: " + uri);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("UserInfo not allowed in download URL: " + uri);
        }
        if (!"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("Initial download host must be github.com: " + uri.getHost());
        }

        String expectedPath = "/" + EXPECTED_REPOSITORY + "/releases/download/v" + version + "/" + assetName;
        if (!expectedPath.equals(uri.getPath())) {
            throw new IllegalArgumentException("Initial download path mismatch: expected " + expectedPath + " but got " + uri.getPath());
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Query and fragment not allowed in download URL: " + uri);
        }
    }

    public static void validateRedirect(URI current, URI next, ClientVersion version, String assetName, Set<URI> visited) {
        if (!"https".equalsIgnoreCase(next.getScheme())) {
            throw new IllegalArgumentException("HTTP downgrade not allowed: " + next);
        }
        if (next.getPort() != -1 && next.getPort() != 443) {
            throw new IllegalArgumentException("Non-443 port not allowed: " + next);
        }
        if (next.getUserInfo() != null) {
            throw new IllegalArgumentException("UserInfo not allowed in redirect URL: " + next);
        }

        String host = next.getHost();
        if (host == null || isIpLiteral(host) || !ALLOWED_STORAGE_HOSTS.contains(host.toLowerCase())) {
            throw new IllegalArgumentException("Unexpected redirect host: " + host);
        }

        if ("github.com".equalsIgnoreCase(host)) {
            String expectedPath = "/" + EXPECTED_REPOSITORY + "/releases/download/v" + version + "/" + assetName;
            if (!expectedPath.equals(next.getPath())) {
                throw new IllegalArgumentException("GitHub redirect path mismatch: expected " + expectedPath + " but got " + next.getPath());
            }
        }

        if (visited.contains(next)) {
            throw new IllegalArgumentException("Redirect loop detected: " + next);
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.contains(":")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            for (String p : parts) {
                try {
                    int val = Integer.parseInt(p);
                    if (val < 0 || val > 255) return false;
                } catch (NumberFormatException ex) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static String boundReleaseNotes(String body) {
        if (body == null) {
            return "";
        }
        byte[] utf8 = body.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= MAX_RELEASE_NOTES_BYTES) {
            return body;
        }
        int byteCount = 0;
        int charIndex = 0;
        while (charIndex < body.length()) {
            int codePoint = body.codePointAt(charIndex);
            int charCount = Character.charCount(codePoint);
            int cpBytes = Character.toString(codePoint).getBytes(StandardCharsets.UTF_8).length;
            if (byteCount + cpBytes > MAX_RELEASE_NOTES_BYTES) {
                break;
            }
            byteCount += cpBytes;
            charIndex += charCount;
        }
        return body.substring(0, charIndex) + "\n\n[Release notes truncated at 32 KiB]";
    }

    private record GitHubRelease(String tagName, boolean draft, boolean prerelease, String body, Map<String, GitHubAsset> assets) {}
    private record GitHubAsset(String name, long size, String state, String downloadUrl) {}

    private static GitHubRelease parseGitHubRelease(byte[] jsonBytes) throws IOException {
        JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(jsonBytes), StandardCharsets.UTF_8));
        reader.setStrictness(Strictness.STRICT);

        String tagName = null;
        Boolean draft = null;
        Boolean prerelease = null;
        String body = "";
        Map<String, GitHubAsset> assets = new HashMap<>();

        Set<String> topFields = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!topFields.add(name)) {
                throw new IllegalArgumentException("Duplicate field in GitHub release: " + name);
            }
            switch (name) {
                case "tag_name" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("Field tag_name must be a string");
                    }
                    tagName = reader.nextString();
                }
                case "draft" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) {
                        throw new IllegalArgumentException("Field draft must be a boolean");
                    }
                    draft = reader.nextBoolean();
                }
                case "prerelease" -> {
                    if (reader.peek() != JsonToken.BOOLEAN) {
                        throw new IllegalArgumentException("Field prerelease must be a boolean");
                    }
                    prerelease = reader.nextBoolean();
                }
                case "body" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                        body = "";
                    } else if (reader.peek() == JsonToken.STRING) {
                        body = reader.nextString();
                    } else {
                        throw new IllegalArgumentException("Field body must be a string or null");
                    }
                }
                case "assets" -> {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                        throw new IllegalArgumentException("Field assets must be an array");
                    }
                    reader.beginArray();
                    while (reader.hasNext()) {
                        GitHubAsset asset = parseAsset(reader);
                        if (assets.putIfAbsent(asset.name(), asset) != null) {
                            throw new IllegalArgumentException("Duplicate asset name in release: " + asset.name());
                        }
                    }
                    reader.endArray();
                }
                default -> skipValueBounded(reader, 1);
            }
        }
        reader.endObject();

        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IllegalArgumentException("Trailing data after GitHub release JSON");
        }

        if (tagName == null || draft == null || prerelease == null) {
            throw new IllegalArgumentException("GitHub release JSON missing required decision fields");
        }

        return new GitHubRelease(tagName, draft, prerelease, body, assets);
    }

    private static GitHubAsset parseAsset(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw new IllegalArgumentException("Asset must be an object");
        }
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        String name = null;
        Long size = null;
        String state = null;
        String downloadUrl = null;

        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) {
                throw new IllegalArgumentException("Duplicate field in asset: " + field);
            }
            switch (field) {
                case "name" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("Asset name must be a string");
                    }
                    name = reader.nextString();
                }
                case "size" -> size = ReleaseManifest.parseStrictLong(reader, "asset.size");
                case "state" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("Asset state must be a string");
                    }
                    state = reader.nextString();
                }
                case "browser_download_url" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("Asset browser_download_url must be a string");
                    }
                    downloadUrl = reader.nextString();
                }
                default -> skipValueBounded(reader, 1);
            }
        }
        reader.endObject();

        if (name == null || size == null || state == null || downloadUrl == null) {
            throw new IllegalArgumentException("Asset missing required fields");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Asset size must be positive: " + size);
        }
        if (!"uploaded".equals(state)) {
            throw new IllegalArgumentException("Asset state is not uploaded: " + state);
        }

        return new GitHubAsset(name, size, state, downloadUrl);
    }

    private static void skipValueBounded(JsonReader reader, int depth) throws IOException {
        if (depth > 32) {
            throw new IllegalArgumentException("JSON nesting depth exceeded maximum of 32");
        }
        switch (reader.peek()) {
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) {
                    skipValueBounded(reader, depth + 1);
                }
                reader.endArray();
            }
            case BEGIN_OBJECT -> {
                reader.beginObject();
                while (reader.hasNext()) {
                    reader.nextName();
                    skipValueBounded(reader, depth + 1);
                }
                reader.endObject();
            }
            default -> reader.skipValue();
        }
    }

    static class DefaultHttpTransport implements HttpTransport {
        private final HttpClient client;

        DefaultHttpTransport() {
            this(HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build());
        }

        DefaultHttpTransport(HttpClient client) {
            this.client = Objects.requireNonNull(client, "client cannot be null");
        }

        @Override
        public TransportResponse send(
                URI uri,
                String method,
                Map<String, String> headers,
                Duration timeout,
                Cancellation cancellation
        ) throws IOException, InterruptedException {
            if (cancellation != null && cancellation.isCancelled()) {
                throw new IOException("Request cancelled before send");
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .method(method, HttpRequest.BodyPublishers.noBody());

            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    builder.header(h.getKey(), h.getValue());
                }
            }

            CompletableFuture<HttpResponse<InputStream>> future =
                    client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (cancellation != null) {
                cancellation.onCancel(() -> future.cancel(true));
            }

            HttpResponse<InputStream> resp;
            try {
                resp = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                future.cancel(true);
                throw new IOException("HTTP request timed out after " + timeout, ex);
            } catch (InterruptedException ex) {
                future.cancel(true);
                throw ex;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof IOException ioEx) throw ioEx;
                if (cause instanceof InterruptedException intEx) throw intEx;
                throw new IOException("HTTP request failed: " + cause.getMessage(), cause);
            } catch (CancellationException ex) {
                throw new IOException("HTTP request cancelled", ex);
            }

            InputStream bodyStream = resp.body();
            if (cancellation != null) {
                cancellation.onCancel(() -> {
                    try {
                        bodyStream.close();
                    } catch (IOException ignored) {
                    }
                });
            }

            return new TransportResponse(resp.statusCode(), resp.headers().map(), bodyStream);
        }
    }
}
