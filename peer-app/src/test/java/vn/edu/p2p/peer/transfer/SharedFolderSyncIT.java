package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.SearchResult;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.util.HashUtil;
import vn.edu.p2p.tracker.TrackerServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedFolderSyncIT {

    @TempDir
    Path tempDir;

    private TrackerServer tracker;
    private int trackerPort;
    private Thread trackerThread;

    private PeerRuntime runtimeAlice;
    private PeerRuntime runtimeBob;
    private Path aliceShared;
    private Path aliceDownloads;
    private Path bobShared;
    private Path bobDownloads;

    @BeforeEach
    void setUp() throws Exception {
        aliceShared = tempDir.resolve("alice_shared");
        aliceDownloads = tempDir.resolve("alice_downloads");
        bobShared = tempDir.resolve("bob_shared");
        bobDownloads = tempDir.resolve("bob_downloads");
        Files.createDirectories(aliceShared);
        Files.createDirectories(aliceDownloads);
        Files.createDirectories(bobShared);
        Files.createDirectories(bobDownloads);

        tracker = new TrackerServer(0);
        trackerThread = new Thread(() -> {
            try {
                tracker.start();
            } catch (IOException ignored) {}
        });
        trackerThread.setDaemon(true);
        trackerThread.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                trackerPort = tracker.localPort();
                if (trackerPort > 0) break;
            } catch (Exception e) {
                Thread.sleep(20);
            }
        }
        assertTrue(trackerPort > 0);

        int alicePort;
        try (ServerSocket probe = new ServerSocket(0)) { alicePort = probe.getLocalPort(); }
        int bobPort;
        try (ServerSocket probe = new ServerSocket(0)) { bobPort = probe.getLocalPort(); }

        AppConfig configAlice = new AppConfig(
                "alice", "Alice", alicePort, "127.0.0.1", trackerPort, aliceDownloads, aliceShared, 4096, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        runtimeAlice = new PeerRuntime(configAlice);

        AppConfig configBob = new AppConfig(
                "bob", "Bob", bobPort, "127.0.0.1", trackerPort, bobDownloads, bobShared, 4096, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        runtimeBob = new PeerRuntime(configBob);

        runtimeAlice.start();
        runtimeBob.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (runtimeAlice != null) {
            try { runtimeAlice.close(); } catch (Exception ignored) {}
        }
        if (runtimeBob != null) {
            try { runtimeBob.close(); } catch (Exception ignored) {}
        }
        if (tracker != null) {
            try { tracker.close(); } catch (Exception ignored) {}
        }
        if (trackerThread != null) {
            trackerThread.join(2000);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testAutomaticFileChangesPropagateWithoutRestart() throws Exception {
        // 1. Alice adds a 1-KiB file
        String initialContent = "A".repeat(1024);
        Path autoDoc = aliceShared.resolve("auto_doc.txt");
        Files.writeString(autoDoc, initialContent);
        String expectedHashA = HashUtil.sha256(autoDoc);

        // Bob searches until file is visible (within 10s)
        long deadline = System.currentTimeMillis() + 10_000;
        List<SearchResult> results = List.of();
        while (System.currentTimeMillis() < deadline) {
            results = runtimeBob.searchFiles("auto_doc");
            if (!results.isEmpty() && results.get(0).file().fileId().equals(expectedHashA)) {
                break;
            }
            Thread.sleep(200);
        }
        assertEquals(1, results.size(), "Bob should find Alice's automatically published file");
        assertEquals("auto_doc.txt", results.get(0).file().fileName());
        assertEquals(expectedHashA, results.get(0).file().fileId());
        assertEquals(1024, results.get(0).file().fileSize());

        // 2. Alice modifies file with same length content
        String updatedContent = "B".repeat(1024);
        Files.writeString(autoDoc, updatedContent);
        String expectedHashB = HashUtil.sha256(autoDoc);
        assertFalse(expectedHashA.equals(expectedHashB));

        deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            results = runtimeBob.searchFiles("auto_doc");
            if (!results.isEmpty() && results.get(0).file().fileId().equals(expectedHashB)) {
                break;
            }
            Thread.sleep(200);
        }
        assertEquals(1, results.size());
        assertEquals(expectedHashB, results.get(0).file().fileId());

        // 3. Alice renames the file
        Path renamedDoc = aliceShared.resolve("renamed_doc.txt");
        Files.move(autoDoc, renamedDoc);

        deadline = System.currentTimeMillis() + 10_000;
        boolean oldDisappeared = false;
        boolean newAppeared = false;
        while (System.currentTimeMillis() < deadline) {
            var oldResults = runtimeBob.searchFiles("auto_doc");
            var newResults = runtimeBob.searchFiles("renamed_doc");
            if (oldResults.isEmpty()) {
                oldDisappeared = true;
            }
            if (!newResults.isEmpty() && newResults.get(0).file().fileName().equals("renamed_doc.txt")) {
                newAppeared = true;
            }
            if (oldDisappeared && newAppeared) {
                break;
            }
            Thread.sleep(200);
        }
        assertTrue(oldDisappeared, "Old filename should disappear after rename");
        assertTrue(newAppeared, "New filename should appear after rename");

        // 4. Alice deletes the file
        Files.delete(renamedDoc);

        deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            results = runtimeBob.searchFiles("renamed_doc");
            if (results.isEmpty()) {
                break;
            }
            Thread.sleep(200);
        }
        assertTrue(results.isEmpty(), "Deleted file should be removed from catalogue search");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testManualRescanWorksAndReflectsInCatalogue() throws Exception {
        Path manualDoc = aliceShared.resolve("manual_doc.txt");
        Files.writeString(manualDoc, "manual content");

        // Trigger manual rescan
        runtimeAlice.refreshSharedFiles();

        long deadline = System.currentTimeMillis() + 5000;
        List<SearchResult> results = List.of();
        while (System.currentTimeMillis() < deadline) {
            results = runtimeBob.searchFiles("manual_doc");
            if (!results.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(1, results.size());
        assertEquals("manual_doc.txt", results.get(0).file().fileName());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testMissingDirectoryWithdrawsFiles() throws Exception {
        Path initDoc = aliceShared.resolve("init.txt");
        Files.writeString(initDoc, "init");
        runtimeAlice.refreshSharedFiles();
        long waitDeadline = System.currentTimeMillis() + 5000;
        List<SearchResult> initResults = List.of();
        while (System.currentTimeMillis() < waitDeadline) {
            initResults = runtimeBob.searchFiles("init");
            if (!initResults.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(1, initResults.size());

        // Remove file and directory
        Files.delete(initDoc);
        Files.delete(aliceShared);

        runtimeAlice.refreshSharedFiles();

        // Snapshot should show 0 files and warning
        PeerRuntime.RuntimeSnapshot snap = runtimeAlice.snapshot();
        assertEquals(0, snap.sharedFileCount());
        assertTrue(snap.sharingDetail().contains("does not exist") || snap.sharingState() == PeerRuntime.SharingState.ERROR);

        // Bob should see 0 files
        long deadline = System.currentTimeMillis() + 5000;
        List<SearchResult> results = List.of();
        while (System.currentTimeMillis() < deadline) {
            results = runtimeBob.searchFiles("init");
            if (results.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(results.isEmpty(), "Files should be withdrawn when shared directory is missing");
    }
}
