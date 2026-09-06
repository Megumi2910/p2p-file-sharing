package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.FileMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferStorageTest {

    private static final String VALID_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void testCreateAndFindResumableStaging(@TempDir Path tempDir) throws IOException {
        String tid = UUID.randomUUID().toString();
        FileMetadata meta = new FileMetadata(tid, VALID_SHA, "sample.bin", 2000, 1000, 2, VALID_SHA, "Sender");

        TransferStorage.StagedTransfer staged = TransferStorage.createStaging(tempDir, meta);
        assertNotNull(staged);
        assertTrue(Files.exists(staged.partPath()));
        assertTrue(Files.exists(staged.metaPath()));

        // Mark chunk 0 as received, write 1000 bytes into part
        staged.meta().markChunkReceived(0);
        staged.meta().save(staged.metaPath());
        Files.write(staged.partPath(), new byte[1000]);

        // Query discovery with same metadata (even with a different transferId)
        String newTid = UUID.randomUUID().toString();
        FileMetadata queryMeta = new FileMetadata(newTid, VALID_SHA, "sample.bin", 2000, 1000, 2, VALID_SHA, "Sender");

        TransferStorage.StagedTransfer found = TransferStorage.findResumableStaging(tempDir, queryMeta);
        assertNotNull(found);
        assertEquals(staged.partPath(), found.partPath());
        assertEquals(1, found.meta().contiguousReceivedPrefix());

        // Cleanup
        TransferStorage.cleanupStaging(found.partPath(), found.metaPath());
        assertTrue(Files.notExists(found.partPath()));
        assertTrue(Files.notExists(found.metaPath()));
    }

    @Test
    void testTruncatedPartFileRejectedFromResumption(@TempDir Path tempDir) throws IOException {
        String tid = UUID.randomUUID().toString();
        FileMetadata meta = new FileMetadata(tid, VALID_SHA, "truncated.bin", 2000, 1000, 2, VALID_SHA, "Sender");

        TransferStorage.StagedTransfer staged = TransferStorage.createStaging(tempDir, meta);
        staged.meta().markChunkReceived(0);
        staged.meta().save(staged.metaPath());
        // Write only 500 bytes (less than expected chunk 0 size 1000)
        Files.write(staged.partPath(), new byte[500]);

        TransferStorage.StagedTransfer found = TransferStorage.findResumableStaging(tempDir, meta);
        assertNull(found, "Truncated partial file must not be admitted for resumption");
    }

    @Test
    void testCorruptMetaIgnored(@TempDir Path tempDir) throws IOException {
        String tid = UUID.randomUUID().toString();
        FileMetadata meta = new FileMetadata(tid, VALID_SHA, "corrupt.bin", 1000, 1000, 1, VALID_SHA, "Sender");

        TransferStorage.StagedTransfer staged = TransferStorage.createStaging(tempDir, meta);
        // Corrupt meta file
        Files.writeString(staged.metaPath(), "GARBAGE NOT P2PR FORMAT");

        TransferStorage.StagedTransfer found = TransferStorage.findResumableStaging(tempDir, meta);
        assertNull(found, "Corrupted metadata file must be skipped gracefully");
    }
}
