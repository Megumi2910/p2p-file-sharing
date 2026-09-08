package vn.edu.p2p.peer.update;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseManifestTest {

    private static KeyPair testKeyPair;
    private static KeyPair otherKeyPair;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        testKeyPair = kpg.generateKeyPair();
        otherKeyPair = kpg.generateKeyPair();
    }

    private static byte[] sign(byte[] data, KeyPair kp) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(data);
        return sig.sign();
    }

    private static String validManifestJson(String version) {
        return """
                {
                  "schema": 1,
                  "repository": "Megumi2910/p2p-file-sharing",
                  "version": "%s",
                  "artifact": "peer-app.jar",
                  "size": 1234567,
                  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "minJava": 21,
                  "installerProtocol": 1,
                  "bundle": {
                    "name": "p2p-client-%s.zip",
                    "size": 2345678,
                    "sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                  }
                }
                """.formatted(version, version);
    }

    @Test
    void testValidManifestAndSignatureSucceeds() throws Exception {
        byte[] manifestBytes = validManifestJson("1.0.1").getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = sign(manifestBytes, testKeyPair);

        ReleaseManifest manifest = ReleaseManifest.parseAndVerify(
                manifestBytes,
                sigBytes,
                testKeyPair.getPublic(),
                "Megumi2910/p2p-file-sharing"
        );

        assertNotNull(manifest);
        assertEquals(new ClientVersion(1, 0, 1), manifest.version());
        assertEquals("peer-app.jar", manifest.artifact());
        assertEquals(1234567, manifest.size());
        assertEquals(21, manifest.minJava());
        assertEquals(1, manifest.installerProtocol());
        assertEquals("p2p-client-1.0.1.zip", manifest.bundle().name());
    }

    @Test
    void testTamperedByteFailsSignatureVerification() throws Exception {
        byte[] manifestBytes = validManifestJson("1.0.1").getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = sign(manifestBytes, testKeyPair);

        // Tamper with one byte
        manifestBytes[manifestBytes.length - 2] = ' ';

        assertThrows(SecurityException.class, () ->
                ReleaseManifest.parseAndVerify(manifestBytes, sigBytes, testKeyPair.getPublic(), "Megumi2910/p2p-file-sharing"));
    }

    @Test
    void testWrongKeyFailsSignatureVerification() throws Exception {
        byte[] manifestBytes = validManifestJson("1.0.1").getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = sign(manifestBytes, otherKeyPair);

        assertThrows(SecurityException.class, () ->
                ReleaseManifest.parseAndVerify(manifestBytes, sigBytes, testKeyPair.getPublic(), "Megumi2910/p2p-file-sharing"));
    }

    @Test
    void testInvalidSignatureLength() {
        byte[] manifestBytes = validManifestJson("1.0.1").getBytes(StandardCharsets.UTF_8);
        byte[] shortSig = new byte[63];

        assertThrows(SecurityException.class, () ->
                ReleaseManifest.parseAndVerify(manifestBytes, shortSig, testKeyPair.getPublic(), "Megumi2910/p2p-file-sharing"));
    }

    @Test
    void testUnknownFieldRejected() throws Exception {
        String json = """
                {
                  "schema": 1,
                  "repository": "Megumi2910/p2p-file-sharing",
                  "version": "1.0.1",
                  "artifact": "peer-app.jar",
                  "size": 1234567,
                  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "minJava": 21,
                  "installerProtocol": 1,
                  "unknownField": "bad",
                  "bundle": {
                    "name": "p2p-client-1.0.1.zip",
                    "size": 2345678,
                    "sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                  }
                }
                """;
        byte[] manifestBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = sign(manifestBytes, testKeyPair);

        assertThrows(IllegalArgumentException.class, () ->
                ReleaseManifest.parseAndVerify(manifestBytes, sigBytes, testKeyPair.getPublic(), "Megumi2910/p2p-file-sharing"));
    }

    @Test
    void testDuplicateFieldRejected() throws Exception {
        String json = """
                {
                  "schema": 1,
                  "schema": 1,
                  "repository": "Megumi2910/p2p-file-sharing",
                  "version": "1.0.1",
                  "artifact": "peer-app.jar",
                  "size": 1234567,
                  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "minJava": 21,
                  "installerProtocol": 1,
                  "bundle": {
                    "name": "p2p-client-1.0.1.zip",
                    "size": 2345678,
                    "sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                  }
                }
                """;
        byte[] manifestBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = sign(manifestBytes, testKeyPair);

        assertThrows(IllegalArgumentException.class, () ->
                ReleaseManifest.parseAndVerify(manifestBytes, sigBytes, testKeyPair.getPublic(), "Megumi2910/p2p-file-sharing"));
    }

    @Test
    void testMissingFieldRejected() throws Exception {
        String json = """
                {
                  "schema": 1,
                  "repository": "Megumi2910/p2p-file-sharing",
                  "version": "1.0.1",
                  "artifact": "peer-app.jar",
                  "size": 1234567,
                  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "minJava": 21,
                  "installerProtocol": 1
                }
                """;
        byte[] manifestBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = sign(manifestBytes, testKeyPair);

        assertThrows(IllegalArgumentException.class, () ->
                ReleaseManifest.parseAndVerify(manifestBytes, sigBytes, testKeyPair.getPublic(), "Megumi2910/p2p-file-sharing"));
    }

    @Test
    void testUnsupportedRequirementsRejected() throws Exception {
        // minJava != 21
        String jsonWrongJava = """
                {
                  "schema": 1,
                  "repository": "Megumi2910/p2p-file-sharing",
                  "version": "1.0.1",
                  "artifact": "peer-app.jar",
                  "size": 1234567,
                  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "minJava": 25,
                  "installerProtocol": 1,
                  "bundle": {
                    "name": "p2p-client-1.0.1.zip",
                    "size": 2345678,
                    "sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                  }
                }
                """;
        byte[] b1 = jsonWrongJava.getBytes(StandardCharsets.UTF_8);
        byte[] s1 = sign(b1, testKeyPair);
        assertThrows(IllegalArgumentException.class, () ->
                ReleaseManifest.parseAndVerify(b1, s1, testKeyPair.getPublic(), "Megumi2910/p2p-file-sharing"));

        // version starting with 'v' in manifest JSON
        String jsonVVersion = """
                {
                  "schema": 1,
                  "repository": "Megumi2910/p2p-file-sharing",
                  "version": "v1.0.1",
                  "artifact": "peer-app.jar",
                  "size": 1234567,
                  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "minJava": 21,
                  "installerProtocol": 1,
                  "bundle": {
                    "name": "p2p-client-v1.0.1.zip",
                    "size": 2345678,
                    "sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                  }
                }
                """;
        byte[] b2 = jsonVVersion.getBytes(StandardCharsets.UTF_8);
        byte[] s2 = sign(b2, testKeyPair);
        assertThrows(IllegalArgumentException.class, () ->
                ReleaseManifest.parseAndVerify(b2, s2, testKeyPair.getPublic(), "Megumi2910/p2p-file-sharing"));
    }

    @Test
    void testTrailingTokensRejected() throws Exception {
        String json = validManifestJson("1.0.1") + " extra";
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        byte[] s = sign(b, testKeyPair);
        assertThrows(Exception.class, () ->
                ReleaseManifest.parseAndVerify(b, s, testKeyPair.getPublic(), "Megumi2910/p2p-file-sharing"));
    }

    @Test
    void testClientVersionGrammarAndOrdering() {
        // Numeric ordering: 1.10.0 vs 1.9.0
        ClientVersion v1_10 = ClientVersion.parse("1.10.0");
        ClientVersion v1_9 = ClientVersion.parse("1.9.0");
        assertTrue(v1_10.compareTo(v1_9) > 0);
        assertTrue(v1_9.compareTo(v1_10) < 0);
        assertEquals(0, v1_10.compareTo(ClientVersion.parse("v1.10.0")));

        // Leading zeros rejected
        assertThrows(IllegalArgumentException.class, () -> ClientVersion.parse("01.0.0"));
        assertThrows(IllegalArgumentException.class, () -> ClientVersion.parse("1.02.0"));
        assertThrows(IllegalArgumentException.class, () -> ClientVersion.parse("1.0.03"));

        // Single zero allowed
        assertDoesNotThrow(() -> ClientVersion.parse("0.0.0"));
        assertDoesNotThrow(() -> ClientVersion.parse("1.0.0"));

        // Non-digits rejected
        assertThrows(IllegalArgumentException.class, () -> ClientVersion.parse("1.0.0-beta"));
        assertThrows(IllegalArgumentException.class, () -> ClientVersion.parse("1.0"));
        assertThrows(IllegalArgumentException.class, () -> ClientVersion.parse("1.0.0.0"));
    }
}
