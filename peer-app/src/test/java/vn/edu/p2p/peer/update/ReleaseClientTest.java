package vn.edu.p2p.peer.update;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseClientTest {

    private static KeyPair testKeyPair;
    private static String publicKeyBase64;

    @BeforeAll
    static void setupKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        testKeyPair = kpg.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(testKeyPair.getPublic().getEncoded());
    }

    private static BuildInfo buildInfo(String version) {
        return new BuildInfo(
                version,
                ClientVersion.parse(version),
                "Megumi2910/p2p-file-sharing",
                publicKeyBase64,
                testKeyPair.getPublic(),
                1,
                false
        );
    }

    private static Path createDummyJar(Path dir, String version, String mainClass) throws IOException {
        Path jarPath = dir.resolve("peer-app.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);

        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jarPath.toFile()), manifest)) {
            JarEntry propEntry = new JarEntry("vn/edu/p2p/peer/update/build.properties");
            out.putNextEntry(propEntry);
            String props = "version=" + version + "\nrepository=Megumi2910/p2p-file-sharing\npublicKey="
                    + publicKeyBase64 + "\ninstallerProtocol=1\n";
            out.write(props.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jarPath;
    }

    private static Path createDummyJarWithCustomProps(Path dir, String mainClass, String propsContent) throws IOException {
        Path jarPath = dir.resolve("peer-app-custom.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);

        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jarPath.toFile()), manifest)) {
            JarEntry propEntry = new JarEntry("vn/edu/p2p/peer/update/build.properties");
            out.putNextEntry(propEntry);
            out.write(propsContent.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jarPath;
    }

    @Test
    void test404ReturnsNoRelease() {
        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));

        ReleaseClient client = new ReleaseClient(buildInfo("1.0.0"), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult result = client.check();

        assertEquals(ReleaseClient.CheckStatus.NO_RELEASE, result.status());
        assertNull(result.candidate());
    }

    @Test
    void testRateLimitReturnsUnavailable() {
        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(403, Map.of("x-ratelimit-reset", List.of("1700000000")), new ByteArrayInputStream(new byte[0]));

        ReleaseClient client = new ReleaseClient(buildInfo("1.0.0"), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult result = client.check();

        assertEquals(ReleaseClient.CheckStatus.UNAVAILABLE, result.status());
        assertTrue(result.message().contains("1700000000"));
    }

    @Test
    void testDraftOrPrereleaseReturnsNoRelease() {
        String jsonDraft = """
                {
                  "tag_name": "v1.0.1",
                  "draft": true,
                  "prerelease": false,
                  "body": "draft notes",
                  "assets": []
                }
                """;
        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(jsonDraft.getBytes(StandardCharsets.UTF_8)));

        ReleaseClient client = new ReleaseClient(buildInfo("1.0.0"), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult result = client.check();

        assertEquals(ReleaseClient.CheckStatus.NO_RELEASE, result.status());
    }

    @Test
    void testUpToDateWhenInstalledVersionIsEqualOrNewer() {
        String json = """
                {
                  "tag_name": "v1.0.0",
                  "draft": false,
                  "prerelease": false,
                  "body": "notes",
                  "assets": []
                }
                """;
        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        ReleaseClient client = new ReleaseClient(buildInfo("1.0.0"), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult result = client.check();

        assertEquals(ReleaseClient.CheckStatus.UP_TO_DATE, result.status());
        assertEquals(ClientVersion.parse("1.0.0"), result.latestVersion());
    }

    @Test
    void testSuccessfulReleaseCheckAndDownload(@TempDir Path tempDir) throws Exception {
        Path dummyJar = createDummyJar(tempDir, "1.0.1", "vn.edu.p2p.peer.PeerApplication");
        byte[] jarBytes = Files.readAllBytes(dummyJar);
        String jarSha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(jarBytes));

        String manifestJson = """
                {
                  "schema": 1,
                  "repository": "Megumi2910/p2p-file-sharing",
                  "version": "1.0.1",
                  "artifact": "peer-app.jar",
                  "size": %d,
                  "sha256": "%s",
                  "minJava": 21,
                  "installerProtocol": 1,
                  "bundle": {
                    "name": "p2p-client-1.0.1.zip",
                    "size": 2048,
                    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                  }
                }
                """.formatted(jarBytes.length, jarSha);
        byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);

        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(manifestBytes);
        byte[] sigBytes = sig.sign();

        String releaseJson = """
                {
                  "tag_name": "v1.0.1",
                  "draft": false,
                  "prerelease": false,
                  "body": "v1.0.1 notes",
                  "assets": [
                    {
                      "name": "peer-app.jar",
                      "size": %d,
                      "state": "uploaded",
                      "browser_download_url": "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar"
                    },
                    {
                      "name": "p2p-client-1.0.1.zip",
                      "size": 2048,
                      "state": "uploaded",
                      "browser_download_url": "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/p2p-client-1.0.1.zip"
                    },
                    {
                      "name": "update-manifest.json",
                      "size": %d,
                      "state": "uploaded",
                      "browser_download_url": "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/update-manifest.json"
                    },
                    {
                      "name": "update-manifest.sig",
                      "size": 64,
                      "state": "uploaded",
                      "browser_download_url": "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/update-manifest.sig"
                    }
                  ]
                }
                """.formatted(jarBytes.length, manifestBytes.length);

        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancellation) -> {
            String path = uri.getPath();
            if (path.endsWith("/latest")) {
                return new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(releaseJson.getBytes(StandardCharsets.UTF_8)));
            }
            if (path.endsWith("update-manifest.json")) {
                return new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(manifestBytes));
            }
            if (path.endsWith("update-manifest.sig")) {
                return new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(sigBytes));
            }
            if (path.endsWith("peer-app.jar")) {
                return new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(jarBytes));
            }
            return new ReleaseClient.TransportResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));
        };

        ReleaseClient client = new ReleaseClient(buildInfo("1.0.0"), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult result = client.check();

        assertEquals(ReleaseClient.CheckStatus.AVAILABLE, result.status());
        assertNotNull(result.candidate());
        assertEquals(ClientVersion.parse("1.0.1"), result.latestVersion());

        Path destJar = tempDir.resolve("downloaded-peer-app.jar");
        client.downloadCandidateJar(result.candidate(), destJar, null, new ReleaseClient.Cancellation());

        assertTrue(Files.exists(destJar));
        assertEquals(jarBytes.length, Files.size(destJar));
    }

    @Test
    void testUrlAndRedirectValidation() {
        ClientVersion v101 = ClientVersion.parse("1.0.1");

        // Valid initial
        URI valid = URI.create("https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar");
        assertDoesNotThrow(() -> ReleaseClient.validateInitialDownloadUrl(valid, v101, "peer-app.jar"));

        // Reject HTTP
        URI httpUri = URI.create("http://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar");
        assertThrows(IllegalArgumentException.class, () -> ReleaseClient.validateInitialDownloadUrl(httpUri, v101, "peer-app.jar"));

        // Reject non-443 port
        URI portUri = URI.create("https://github.com:8443/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar");
        assertThrows(IllegalArgumentException.class, () -> ReleaseClient.validateInitialDownloadUrl(portUri, v101, "peer-app.jar"));

        // Reject IP literal
        URI ipUri = URI.create("https://140.82.121.4/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar");
        assertThrows(IllegalArgumentException.class, () -> ReleaseClient.validateInitialDownloadUrl(ipUri, v101, "peer-app.jar"));

        // Redirect validation
        URI storageRedirect = URI.create("https://objects.githubusercontent.com/github-production-release-asset-2e65be/123/456");
        assertDoesNotThrow(() -> ReleaseClient.validateRedirect(valid, storageRedirect, v101, "peer-app.jar", new HashSet<>()));

        // Reject redirect to unexpected host
        URI evilRedirect = URI.create("https://evil.com/fake.jar");
        assertThrows(IllegalArgumentException.class, () -> ReleaseClient.validateRedirect(valid, evilRedirect, v101, "peer-app.jar", new HashSet<>()));

        // Reject loop
        HashSet<URI> visited = new HashSet<>();
        visited.add(storageRedirect);
        assertThrows(IllegalArgumentException.class, () -> ReleaseClient.validateRedirect(valid, storageRedirect, v101, "peer-app.jar", visited));
    }

    @Test
    void testDownloadCancellationDeletesIncompleteFile(@TempDir Path tempDir) {
        Path destJar = tempDir.resolve("cancelled.jar");
        ReleaseClient.Cancellation cancellation = new ReleaseClient.Cancellation();
        cancellation.cancel();

        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancel) ->
                new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(new byte[1024]));

        ReleaseClient client = new ReleaseClient(buildInfo("1.0.0"), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseManifest manifest = new ReleaseManifest(
                1, "Megumi2910/p2p-file-sharing", ClientVersion.parse("1.0.1"), "peer-app.jar",
                1024, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                21, 1, new ReleaseManifest.BundleInfo("p2p-client-1.0.1.zip", 2048, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        );
        ReleaseClient.ValidatedCandidate candidate = new ReleaseClient.ValidatedCandidate(
                ClientVersion.parse("1.0.1"), manifest, new byte[10], new byte[64],
                "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar", 1024,
                "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/p2p-client-1.0.1.zip", 2048
        );

        assertThrows(IOException.class, () -> client.downloadCandidateJar(candidate, destJar, null, cancellation));
        assertFalse(Files.exists(destJar));
    }

    @Test
    void testStalledBodyStreamCancellationUnblocksAndCleansUp(@TempDir Path tempDir) throws Exception {
        Path destJar = tempDir.resolve("stalled.jar");
        ReleaseClient.Cancellation cancellation = new ReleaseClient.Cancellation();

        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut);
        CountDownLatch enteredReadLatch = new CountDownLatch(1);
        InputStream instrumentedStream = new InputStream() {
            @Override
            public int read() throws IOException {
                enteredReadLatch.countDown();
                return pipeIn.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                enteredReadLatch.countDown();
                return pipeIn.read(b, off, len);
            }

            @Override
            public void close() throws IOException {
                pipeIn.close();
            }
        };

        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancel) -> {
            cancel.onCancel(() -> {
                try {
                    pipeIn.close();
                    pipeOut.close();
                } catch (IOException ignored) {
                }
            });
            return new ReleaseClient.TransportResponse(200, Map.of(), instrumentedStream);
        };

        ReleaseClient client = new ReleaseClient(buildInfo("1.0.0"), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseManifest manifest = new ReleaseManifest(
                1, "Megumi2910/p2p-file-sharing", ClientVersion.parse("1.0.1"), "peer-app.jar",
                1024, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                21, 1, new ReleaseManifest.BundleInfo("p2p-client-1.0.1.zip", 2048, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        );
        ReleaseClient.ValidatedCandidate candidate = new ReleaseClient.ValidatedCandidate(
                ClientVersion.parse("1.0.1"), manifest, new byte[10], new byte[64],
                "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar", 1024,
                "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/p2p-client-1.0.1.zip", 2048
        );

        CountDownLatch startedLatch = new CountDownLatch(1);
        AtomicBoolean threwExpected = new AtomicBoolean(false);

        Thread downloadThread = new Thread(() -> {
            try {
                client.downloadCandidateJar(candidate, destJar, (read, total) -> {
                    startedLatch.countDown();
                }, cancellation);
            } catch (Exception ex) {
                threwExpected.set(true);
            }
        });

        downloadThread.start();
        assertTrue(enteredReadLatch.await(5, TimeUnit.SECONDS), "Download must enter blocking read");

        // Cancel while reading is stalled on empty pipe
        cancellation.cancel();

        downloadThread.join(2000);
        assertFalse(downloadThread.isAlive(), "Download thread must unblock and terminate within 2s of cancellation");
        assertTrue(threwExpected.get(), "Download must throw exception upon cancellation");
        assertFalse(Files.exists(destJar), "Partial destination must be deleted on cancellation");
    }

    @Test
    void testStrictGitHubReleaseJsonParsing() {
        // 1. Duplicate decision field 'draft'
        String dupFieldJson = """
                {
                  "tag_name": "v1.0.1",
                  "draft": false,
                  "draft": true,
                  "prerelease": false,
                  "assets": []
                }
                """;
        ReleaseClient.HttpTransport t1 = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(dupFieldJson.getBytes(StandardCharsets.UTF_8)));
        ReleaseClient c1 = new ReleaseClient(buildInfo("1.0.0"), t1, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult r1 = c1.check();
        assertEquals(ReleaseClient.CheckStatus.INVALID_RELEASE, r1.status());
        assertTrue(r1.message().contains("Duplicate field"));

        // 2. Trailing data after valid JSON root
        String validBaseJson = """
                {
                  "tag_name": "v1.0.0",
                  "draft": false,
                  "prerelease": false,
                  "body": "notes",
                  "assets": []
                }
                """;
        ReleaseClient.HttpTransport tBase = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(validBaseJson.getBytes(StandardCharsets.UTF_8)));
        ReleaseClient cBase = new ReleaseClient(buildInfo("1.0.0"), tBase, URI.create(ReleaseClient.GITHUB_API_LATEST));
        assertEquals(ReleaseClient.CheckStatus.UP_TO_DATE, cBase.check().status());

        String trailingJson = validBaseJson + "\n{\"extra\": \"trailing\"}";
        ReleaseClient.HttpTransport t2 = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(trailingJson.getBytes(StandardCharsets.UTF_8)));
        ReleaseClient c2 = new ReleaseClient(buildInfo("1.0.0"), t2, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult r2 = c2.check();
        assertEquals(ReleaseClient.CheckStatus.INVALID_RELEASE, r2.status());
        assertNotNull(r2.message());
        // 3. Duplicate asset name
        String dupAssetJson = """
                {
                  "tag_name": "v1.0.1",
                  "draft": false,
                  "prerelease": false,
                  "assets": [
                    { "name": "peer-app.jar", "size": 100, "state": "uploaded", "browser_download_url": "https://github.com" },
                    { "name": "peer-app.jar", "size": 200, "state": "uploaded", "browser_download_url": "https://github.com" }
                  ]
                }
                """;
        ReleaseClient.HttpTransport t3 = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(200, Map.of(), new ByteArrayInputStream(dupAssetJson.getBytes(StandardCharsets.UTF_8)));
        ReleaseClient c3 = new ReleaseClient(buildInfo("1.0.0"), t3, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult r3 = c3.check();
        assertEquals(ReleaseClient.CheckStatus.INVALID_RELEASE, r3.status());
        assertTrue(r3.message().contains("Duplicate asset name"));
    }

    @Test
    void testRetryResetHeaders() {
        // Valid retry-after
        ReleaseClient.HttpTransport t1 = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(429, Map.of("retry-after", List.of("60")), new ByteArrayInputStream(new byte[0]));
        ReleaseClient c1 = new ReleaseClient(buildInfo("1.0.0"), t1, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult r1 = c1.check();
        assertEquals(ReleaseClient.CheckStatus.UNAVAILABLE, r1.status());
        assertTrue(r1.message().contains("retry after 60s"));

        // Malformed retry-after falls back without fabricating time
        ReleaseClient.HttpTransport t2 = (uri, method, headers, timeout, cancellation) ->
                new ReleaseClient.TransportResponse(429, Map.of("retry-after", List.of("not-a-number")), new ByteArrayInputStream(new byte[0]));
        ReleaseClient c2 = new ReleaseClient(buildInfo("1.0.0"), t2, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult r2 = c2.check();
        assertEquals(ReleaseClient.CheckStatus.UNAVAILABLE, r2.status());
        assertTrue(r2.message().contains("rate limit exceeded"));
    }

    @Test
    void testBuildInfoReadJarDuplicatePropertiesRejection(@TempDir Path tempDir) throws Exception {
        String badProps = """
                version=1.0.1
                version=1.0.2
                repository=Megumi2910/p2p-file-sharing
                publicKey=%s
                installerProtocol=1
                """.formatted(publicKeyBase64);

        Path badJar = createDummyJarWithCustomProps(tempDir, "vn.edu.p2p.peer.PeerApplication", badProps);

        assertThrows(IOException.class, () -> BuildInfo.readJar(badJar));
    }

    @Test
    void testVerifyJarCatchesMismatchedVersionOrRepository(@TempDir Path tempDir) throws Exception {
        Path jar = createDummyJar(tempDir, "1.0.1", "vn.edu.p2p.peer.PeerApplication");
        byte[] jarBytes = Files.readAllBytes(jar);
        String jarSha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(jarBytes));

        // Manifest claiming version 1.0.2 for a 1.0.1 jar
        ReleaseManifest mismatchedManifest = new ReleaseManifest(
                1, "Megumi2910/p2p-file-sharing", ClientVersion.parse("1.0.2"), "peer-app.jar",
                jarBytes.length, jarSha, 21, 1,
                new ReleaseManifest.BundleInfo("p2p-client-1.0.2.zip", 2048, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        );

        assertThrows(SecurityException.class, () -> ReleaseManifest.verifyJar(jar, mismatchedManifest, testKeyPair.getPublic()));

        // Verification must never delete input jar
        assertTrue(Files.exists(jar));
    }
}
