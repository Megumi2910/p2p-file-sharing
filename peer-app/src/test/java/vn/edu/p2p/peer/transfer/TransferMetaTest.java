package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferMetaTest {

    private static final String VALID_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void testSaveAndLoadRoundtrip(@TempDir Path tempDir) throws IOException {
        Path metaPath = tempDir.resolve(".p2p-test.part.meta");
        TransferMeta meta = new TransferMeta(VALID_SHA, 2500000, 1048576, 3);

        meta.markChunkReceived(0);
        meta.markChunkReceived(1);
        meta.save(metaPath);

        TransferMeta loaded = TransferMeta.load(metaPath);
        assertEquals(VALID_SHA, loaded.fileSha256());
        assertEquals(2500000, loaded.fileSize());
        assertEquals(1048576, loaded.chunkSizeBytes());
        assertEquals(3, loaded.totalChunks());
        assertTrue(loaded.isChunkReceived(0));
        assertTrue(loaded.isChunkReceived(1));
        assertFalse(loaded.isChunkReceived(2));
        assertEquals(2, loaded.contiguousReceivedPrefix());
        assertFalse(loaded.allChunksReceived());

        // Mark last chunk and verify allChunksReceived
        loaded.markChunkReceived(2);
        assertTrue(loaded.allChunksReceived());
        assertEquals(3, loaded.contiguousReceivedPrefix());
    }

    @Test
    void testContiguousPrefixWithGaps() {
        TransferMeta meta = new TransferMeta(VALID_SHA, 5000, 1000, 5);
        meta.markChunkReceived(0);
        meta.markChunkReceived(1);
        meta.markChunkReceived(3); // Gap at index 2

        assertEquals(2, meta.contiguousReceivedPrefix());
        assertEquals(3, meta.countReceivedChunks());
    }

    @Test
    void testCorruptedChecksumRejection(@TempDir Path tempDir) throws IOException {
        Path metaPath = tempDir.resolve(".p2p-corrupt.part.meta");
        TransferMeta meta = new TransferMeta(VALID_SHA, 1000, 500, 2);
        meta.markChunkReceived(0);
        meta.save(metaPath);

        // Corrupt one byte in payload
        byte[] bytes = Files.readAllBytes(metaPath);
        bytes[10] ^= 0x55;
        Files.write(metaPath, bytes);

        IOException ex = assertThrows(IOException.class, () -> TransferMeta.load(metaPath));
        assertTrue(ex.getMessage().contains("checksum mismatch"));
    }

    @Test
    void testCorruptedMagicRejection(@TempDir Path tempDir) throws IOException {
        Path metaPath = tempDir.resolve(".p2p-badmagic.part.meta");
        TransferMeta meta = new TransferMeta(VALID_SHA, 1000, 500, 2);
        meta.save(metaPath);

        byte[] bytes = Files.readAllBytes(metaPath);
        bytes[0] = 0x00; // Alter magic
        // Recompute trailing checksum to isolate magic check
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        byte[] payload = java.util.Arrays.copyOfRange(bytes, 0, bytes.length - 32);
        byte[] newChecksum = md.digest(payload);
        System.arraycopy(newChecksum, 0, bytes, bytes.length - 32, 32);
        Files.write(metaPath, bytes);

        IOException ex = assertThrows(IOException.class, () -> TransferMeta.load(metaPath));
        assertTrue(ex.getMessage().contains("Invalid metadata magic"));
    }
}
