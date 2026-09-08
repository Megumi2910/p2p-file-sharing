package vn.edu.p2p.peer.update;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
    static void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        testKeyPair = kpg.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(testKeyPair.getPublic().getEncoded());
    }

    private static byte[] sign(byte[] data) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(data);
        return sig.sign();
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
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }

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

    @Test
    void test404ReturnsNoRelease() {
        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout) ->
                new ReleaseClient.TransportResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));

        ReleaseClient client = new ReleaseClient(buildInfo("1.0.0"), transport, URI.create(ReleaseClient.GITHUB_API_LATEST));
        ReleaseClient.CheckResult result = client.check();

        assertEquals(ReleaseClient.CheckStatus.NO_RELEASE, result.status());
        assertNull(result.candidate());
    }

    @Test
    void testRateLimitReturnsUnavailable() {
        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout) ->
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
        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout) ->
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
        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout) ->
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
                    "size": 5000,
                    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                  }
                }
                """.formatted(jarBytes.length, jarSha);
        byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = sign(manifestBytes);

        String releaseJson = """
                {
                  "tag_name": "v1.0.1",
                  "draft": false,
                  "prerelease": false,
                  "body": "Release 1.0.1 notes",
                  "assets": [
                    {
                      "name": "peer-app.jar",
                      "size": %d,
                      "state": "uploaded",
                      "browser_download_url": "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar"
                    },
                    {
                      "name": "p2p-client-1.0.1.zip",
                      "size": 5000,
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

        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout) -> {
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
        client.downloadCandidateJar(result.candidate(), destJar, null, new AtomicBoolean(false));

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
        AtomicBoolean cancelled = new AtomicBoolean(true);

        ReleaseClient.HttpTransport transport = (uri, method, headers, timeout) ->
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

        assertThrows(IOException.class, () -> client.downloadCandidateJar(candidate, destJar, null, cancelled));
        assertFalse(Files.exists(destJar));
    }
}
