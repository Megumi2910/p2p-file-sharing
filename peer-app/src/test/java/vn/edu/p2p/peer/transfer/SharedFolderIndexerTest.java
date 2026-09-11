package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedFolderIndexerTest {

    private AppConfig createConfig(Path shared, Path downloads) {
        return new AppConfig(
                "peer-1", "Peer 1", 6001, "127.0.0.1", 5000, downloads, shared, 4096, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
    }

    @Test
    void testExclusionsAndUnicode(@TempDir Path tempDir) throws Exception {
        Path shared = tempDir.resolve("shared");
        Path downloads = tempDir.resolve("downloads");
        Files.createDirectories(shared);
        Files.createDirectories(downloads);

        Files.writeString(shared.resolve("regular.txt"), "hello world");
        Files.writeString(shared.resolve("tập_tin_tiếng_việt.txt"), "nội dung unicode");
        Files.write(shared.resolve("empty.dat"), new byte[0]);

        // Excluded files
        Files.writeString(shared.resolve(".p2p-internal.meta"), "internal meta");
        Files.writeString(shared.resolve("download.part"), "partial");
        Files.writeString(shared.resolve("checksum.meta"), "meta");

        AppConfig config = createConfig(shared, downloads);
        AtomicLong clock = new AtomicLong(100_000_000L);
        SharedFolderIndexer indexer = new SharedFolderIndexer(config, HashUtil::sha256, clock::get);

        // First observation
        SharedFolderIndexer.ScanResult scan1 = indexer.scan(false);
        assertEquals(0, scan1.files().size());
        assertEquals(3, scan1.pendingFiles(), "3 eligible files should be pending initial observation");

        // Advance clock past 1 second
        clock.addAndGet(1_100_000_000L);

        SharedFolderIndexer.ScanResult scan2 = indexer.scan(false);
        assertEquals(3, scan2.files().size());
        assertEquals(0, scan2.pendingFiles());

        var fileNames = scan2.files().stream().map(FileRecord::fileName).toList();
        assertTrue(fileNames.contains("regular.txt"));
        assertTrue(fileNames.contains("tập_tin_tiếng_việt.txt"));
        assertTrue(fileNames.contains("empty.dat"));

        assertFalse(fileNames.contains(".p2p-internal.meta"));
        assertFalse(fileNames.contains("download.part"));
        assertFalse(fileNames.contains("checksum.meta"));
    }

    @Test
    void testDeterministicTwoObservationStability(@TempDir Path tempDir) throws Exception {
        Path shared = tempDir.resolve("shared");
        Path downloads = tempDir.resolve("downloads");
        Files.createDirectories(shared);
        Files.createDirectories(downloads);

        Path file = shared.resolve("stable_test.bin");
        Files.writeString(file, "data data data");

        AppConfig config = createConfig(shared, downloads);

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        SharedFolderIndexer indexer = new SharedFolderIndexer(config, HashUtil::sha256, clock::get);

        // Scan 1: initial observation -> pending
        SharedFolderIndexer.ScanResult r1 = indexer.scan(false);
        assertEquals(0, r1.files().size());
        assertEquals(1, r1.pendingFiles());

        // Advance by only 500ms -> still pending
        clock.addAndGet(500_000_000L);
        SharedFolderIndexer.ScanResult r2 = indexer.scan(false);
        assertEquals(0, r2.files().size());
        assertEquals(1, r2.pendingFiles());

        // Advance by another 600ms (total 1.1s) -> stable, hashed
        clock.addAndGet(600_000_000L);
        SharedFolderIndexer.ScanResult r3 = indexer.scan(false);
        assertEquals(1, r3.files().size());
        assertEquals(0, r3.pendingFiles());
        assertEquals("stable_test.bin", r3.files().get(0).fileName());
    }

    @Test
    void testSameSizeModificationRequiresRehash(@TempDir Path tempDir) throws Exception {
        Path shared = tempDir.resolve("shared");
        Path downloads = tempDir.resolve("downloads");
        Files.createDirectories(shared);
        Files.createDirectories(downloads);

        Path file = shared.resolve("edit.txt");
        Files.writeString(file, "AAAA", StandardCharsets.UTF_8);

        AppConfig config = createConfig(shared, downloads);

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        SharedFolderIndexer indexer = new SharedFolderIndexer(config, HashUtil::sha256, clock::get);

        // Stabilize initial file
        indexer.scan(false);
        clock.addAndGet(1_100_000_000L);
        SharedFolderIndexer.ScanResult initRes = indexer.scan(false);
        assertEquals(1, initRes.files().size());
        String initialHash = initRes.files().get(0).fileId();

        // Overwrite with same size ("BBBB"), mark dirty
        Files.writeString(file, "BBBB", StandardCharsets.UTF_8);
        indexer.markDirty(file);

        // Scan before 1 second -> pending
        clock.addAndGet(200_000_000L);
        SharedFolderIndexer.ScanResult rPending = indexer.scan(false);
        assertEquals(0, rPending.files().size());
        assertEquals(1, rPending.pendingFiles());

        // Wait past 1 second -> hashed with new hash
        clock.addAndGet(1_000_000_000L);
        SharedFolderIndexer.ScanResult rUpdated = indexer.scan(false);
        assertEquals(1, rUpdated.files().size());
        assertEquals(0, rUpdated.pendingFiles());
        String updatedHash = rUpdated.files().get(0).fileId();
        assertFalse(initialHash.equals(updatedHash), "Same-size content change must produce new hash");
    }

    @Test
    void testMutationDuringHashingAbortsPublication(@TempDir Path tempDir) throws Exception {
        Path shared = tempDir.resolve("shared");
        Path downloads = tempDir.resolve("downloads");
        Files.createDirectories(shared);
        Files.createDirectories(downloads);

        Path file = shared.resolve("mutating.txt");
        Files.writeString(file, "original content");

        AppConfig config = createConfig(shared, downloads);

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        SharedFolderIndexer[] ref = new SharedFolderIndexer[1];

        SharedFolderIndexer indexer = new SharedFolderIndexer(config, path -> {
            // Seam: modify the file while hashing is in progress!
            Files.writeString(path, "modified content while hashing");
            ref[0].markDirty(path);
            return HashUtil.sha256(path);
        }, clock::get);
        ref[0] = indexer;

        // First observation
        indexer.scan(false);
        clock.addAndGet(1_100_000_000L);

        // Second scan attempts to hash, but mutation occurs during hash
        SharedFolderIndexer.ScanResult mutatedResult = indexer.scan(false);
        assertEquals(0, mutatedResult.files().size(), "File modified mid-hash must not be published");
        assertEquals(1, mutatedResult.pendingFiles());
        assertTrue(mutatedResult.warnings().stream().anyMatch(w -> w.contains("modified while hashing")));

        // Next pass after stabilizing:
        SharedFolderIndexer normalIndexer = new SharedFolderIndexer(config, HashUtil::sha256, clock::get);
        normalIndexer.scan(false);
        clock.addAndGet(1_100_000_000L);
        SharedFolderIndexer.ScanResult finalRes = normalIndexer.scan(false);
        assertEquals(1, finalRes.files().size());
        assertEquals(0, finalRes.pendingFiles());
    }

    @Test
    void testDirectoryMissingAfterStartupReturnsEmptyWithWarning(@TempDir Path tempDir) throws Exception {
        Path shared = tempDir.resolve("shared");
        Path downloads = tempDir.resolve("downloads");
        Files.createDirectories(shared);
        Files.createDirectories(downloads);

        Files.writeString(shared.resolve("file.txt"), "hello");
        AppConfig config = createConfig(shared, downloads);

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        SharedFolderIndexer indexer = new SharedFolderIndexer(config, HashUtil::sha256, clock::get);

        indexer.scan(false);
        clock.addAndGet(1_100_000_000L);
        SharedFolderIndexer.ScanResult res1 = indexer.scan(false);
        assertEquals(1, res1.files().size());

        // Delete shared directory
        Files.delete(shared.resolve("file.txt"));
        Files.delete(shared);

        SharedFolderIndexer.ScanResult res2 = indexer.scan(false);
        assertEquals(0, res2.files().size());
        assertEquals(0, res2.pendingFiles());
        assertTrue(res2.warnings().stream().anyMatch(w -> w.contains("does not exist")));
    }

    @Test
    void testUnchangedScanReusesCacheWithoutRehashing(@TempDir Path tempDir) throws Exception {
        Path shared = tempDir.resolve("shared");
        Path downloads = tempDir.resolve("downloads");
        Files.createDirectories(shared);
        Files.createDirectories(downloads);

        Files.writeString(shared.resolve("cache_test.txt"), "content");
        AppConfig config = createConfig(shared, downloads);

        AtomicLong clock = new AtomicLong(1_000_000_000L);
        AtomicInteger hashInvocations = new AtomicInteger(0);

        SharedFolderIndexer indexer = new SharedFolderIndexer(config, path -> {
            hashInvocations.incrementAndGet();
            return HashUtil.sha256(path);
        }, clock::get);

        indexer.scan(false);
        clock.addAndGet(1_100_000_000L);
        SharedFolderIndexer.ScanResult res1 = indexer.scan(false);
        assertEquals(1, res1.files().size());
        assertEquals(1, hashInvocations.get());

        // Subsequent scan without changes must reuse cache
        SharedFolderIndexer.ScanResult res2 = indexer.scan(false);
        assertEquals(1, res2.files().size());
        assertEquals(1, hashInvocations.get(), "Clean file should not be rehashed");

        // Explicit forceRehash must rehash
        SharedFolderIndexer.ScanResult res3 = indexer.scan(true);
        assertEquals(1, res3.files().size());
        assertEquals(2, hashInvocations.get(), "forceRehash must rehash stable cached file");
    }
}
