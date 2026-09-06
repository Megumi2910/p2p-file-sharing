package vn.edu.p2p.common.model;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.TransferProtocol;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileMetadataTest {

    private static final String VALID_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String VALID_UUID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void testValidMetadataCreationAndNormalization() {
        FileMetadata meta = new FileMetadata(
                VALID_UUID,
                VALID_SHA.toUpperCase(), // Upper case input
                "sample.txt",
                100,
                50,
                2,
                VALID_SHA.toUpperCase(),
                "Alice"
        );
        assertEquals(VALID_SHA.toLowerCase(), meta.fileSha256());
        assertEquals(VALID_SHA.toLowerCase(), meta.fileId());
    }

    @Test
    void testNonCanonicalUuidRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FileMetadata(
                "not-a-uuid",
                VALID_SHA,
                "sample.txt",
                100,
                50,
                2,
                VALID_SHA,
                "Alice"
        ));
    }

    @Test
    void testChunkBoundsAndTotalChunksCalculation() {
        // Chunk size 0
        assertThrows(IllegalArgumentException.class, () -> new FileMetadata(
                VALID_UUID, VALID_SHA, "sample.txt", 100, 0, 1, VALID_SHA, "Alice"
        ));
        // Chunk size > MAX_CHUNK_BYTES (8 MiB + 1)
        assertThrows(IllegalArgumentException.class, () -> new FileMetadata(
                VALID_UUID, VALID_SHA, "sample.txt", 100, TransferProtocol.MAX_CHUNK_BYTES + 1, 1, VALID_SHA, "Alice"
        ));
        // Mismatched totalChunks
        assertThrows(IllegalArgumentException.class, () -> new FileMetadata(
                VALID_UUID, VALID_SHA, "sample.txt", 100, 50, 3, VALID_SHA, "Alice" // expected 2
        ));
        // Valid 8 MiB boundary
        assertDoesNotThrow(() -> new FileMetadata(
                VALID_UUID, VALID_SHA, "sample.txt", TransferProtocol.MAX_CHUNK_BYTES,
                TransferProtocol.MAX_CHUNK_BYTES, 1, VALID_SHA, "Alice"
        ));
    }

    @Test
    void testEmptyFileMetadataRequiresZeroTotalChunks() {
        assertDoesNotThrow(() -> new FileMetadata(
                VALID_UUID, VALID_SHA, "empty.txt", 0, 4096, 0, VALID_SHA, "Alice"
        ));
        assertThrows(IllegalArgumentException.class, () -> new FileMetadata(
                VALID_UUID, VALID_SHA, "empty.txt", 0, 4096, 1, VALID_SHA, "Alice"
        ));
    }

    @Test
    void testRejectControlCharactersInFileName() {
        assertThrows(IllegalArgumentException.class, () -> new FileMetadata(
                VALID_UUID, VALID_SHA, "bad\nname.txt", 100, 50, 2, VALID_SHA, "Alice"
        ));
        assertThrows(IllegalArgumentException.class, () -> new FileMetadata(
                VALID_UUID, VALID_SHA, "bad\0name.txt", 100, 50, 2, VALID_SHA, "Alice"
        ));
    }

    @Test
    void testLargeArithmeticWithoutOverflow() {
        // 1 TB file size: 1099511627776L
        long oneTb = 1099511627776L;
        int chunkSize = 1024 * 1024; // 1 MiB
        long expectedChunks = oneTb / chunkSize; // exactly 1,048,576 chunks
        assertDoesNotThrow(() -> new FileMetadata(
                VALID_UUID, VALID_SHA, "bigfile.iso", oneTb, chunkSize, expectedChunks, VALID_SHA, "Alice"
        ));
    }
}
