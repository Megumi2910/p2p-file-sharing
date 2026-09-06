package vn.edu.p2p.peer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.protocol.TransferProtocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    private static final Path DIR = Path.of("downloads");

    private AppConfig create(int peerPort, int chunkSize, int promptTimeout, int responseTimeout, int maxConcurrent) {
        return new AppConfig(
                "id", "Name", peerPort, "127.0.0.1", 5000, DIR, chunkSize, false,
                15000, 15000, promptTimeout, responseTimeout, 300000, maxConcurrent
        );
    }

    @Test
    void testPortBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> create(0, 1024, 1000, 5000, 4));
        assertThrows(IllegalArgumentException.class, () -> create(65536, 1024, 1000, 5000, 4));
        assertDoesNotThrow(() -> create(1, 1024, 1000, 5000, 4));
        assertDoesNotThrow(() -> create(65535, 1024, 1000, 5000, 4));
    }

    @Test
    void testChunkSizeBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> create(6001, 0, 1000, 5000, 4));
        assertThrows(IllegalArgumentException.class, () -> create(6001, TransferProtocol.MAX_CHUNK_BYTES + 1, 1000, 5000, 4));
        assertDoesNotThrow(() -> create(6001, TransferProtocol.MAX_CHUNK_BYTES, 1000, 5000, 4));
        assertDoesNotThrow(() -> create(6001, TransferProtocol.DEFAULT_CHUNK_BYTES, 1000, 5000, 4));
    }

    @Test
    void testTimeoutOrderingAndBounds() {
        // Prompt equal to response
        assertThrows(IllegalArgumentException.class, () -> create(6001, 1024, 1000, 1000, 4));
        // Prompt greater than response
        assertThrows(IllegalArgumentException.class, () -> create(6001, 1024, 1000, 999, 4));
        // Zero or negative
        assertThrows(IllegalArgumentException.class, () ->
                new AppConfig("id", "Name", 6001, "127.0.0.1", 5000, DIR, 1024, false,
                        0, 15000, 1000, 5000, 300000, 4));
        // Prompt at Integer.MAX_VALUE cannot have larger response
        assertThrows(IllegalArgumentException.class, () -> create(6001, 1024, Integer.MAX_VALUE, Integer.MAX_VALUE, 4));

        // Valid pairs
        assertDoesNotThrow(() -> create(6001, 1024, 120000, 135000, 4));
        assertDoesNotThrow(() -> create(6001, 1024, 1000, 5000, 4));
    }

    @Test
    void testMaxConcurrentBounds() {
        assertThrows(IllegalArgumentException.class, () -> create(6001, 1024, 1000, 5000, 0));
        assertThrows(IllegalArgumentException.class, () -> create(6001, 1024, 1000, 5000, 65));
        assertDoesNotThrow(() -> create(6001, 1024, 1000, 5000, 1));
        assertDoesNotThrow(() -> create(6001, 1024, 1000, 5000, 64));
    }

    @Test
    void testLoadRejectsTrailingTabBeforeTrim(@TempDir Path tempDir) throws IOException {
        Path propsFile = tempDir.resolve("peer.properties");
        String content = """
                peer.id=peer-123\t
                peer.name=Alice
                peer.port=6001
                tracker.host=127.0.0.1
                tracker.port=5000
                """;
        Files.writeString(propsFile, content);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> AppConfig.load(propsFile));
        assertTrue(ex.getMessage().contains("peer.id"));
        assertTrue(ex.getMessage().contains("ISO control"));
    }

    @Test
    void testLoadRejectsInvalidBoolean(@TempDir Path tempDir) throws IOException {
        Path propsFile = tempDir.resolve("peer.properties");
        String content = """
                peer.id=peer-123
                peer.name=Alice
                peer.port=6001
                tracker.host=127.0.0.1
                tracker.port=5000
                transfer.autoAccept=maybe
                """;
        Files.writeString(propsFile, content);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> AppConfig.load(propsFile));
        assertTrue(ex.getMessage().contains("transfer.autoAccept"));
    }

    @Test
    void testLoadValidUnicodeAndDefaults(@TempDir Path tempDir) throws IOException {
        Path propsFile = tempDir.resolve("peer.properties");
        String content = """
                peer.id=peer-nguyen
                peer.name=Nguy\\u1ec5n V\\u0103n A
                """;
        Files.writeString(propsFile, content);

        AppConfig config = assertDoesNotThrow(() -> AppConfig.load(propsFile));
        assertEquals("peer-nguyen", config.peerId());
        assertEquals("Nguyễn Văn A", config.displayName());
        assertEquals(6001, config.peerPort());
        assertEquals(1048576, config.chunkSizeBytes());
        assertFalse(config.autoAccept());
        assertEquals(15000, config.trackerReadTimeoutMillis());
        assertEquals(15000, config.transferReadTimeoutMillis());
        assertEquals(120000, config.transferPromptTimeoutMillis());
        assertEquals(135000, config.transferOfferResponseTimeoutMillis());
        assertEquals(300000, config.transferVerifyTimeoutMillis());
        assertEquals(4, config.maxConcurrentTransfers());
    }
}
