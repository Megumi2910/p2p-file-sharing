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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogueSearchIT {

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
    }

    @AfterEach
    void tearDown() {
        if (runtimeAlice != null) {
            try { runtimeAlice.close(); } catch (Exception ignored) {}
        }
        if (runtimeBob != null) {
            try { runtimeBob.close(); } catch (Exception ignored) {}
        }
        if (tracker != null) {
            try { tracker.close(); } catch (IOException ignored) {}
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testSearchDownloadAndStalePruning() throws Exception {
        // Place shared files in Alice's shared folder before starting
        Path sharedPdf = aliceShared.resolve("distributed_systems.pdf");
        byte[] pdfData = new byte[8192]; // 2 chunks of 4096
        new Random(42).nextBytes(pdfData);
        Files.write(sharedPdf, pdfData);
        String expectedSha256 = HashUtil.sha256(sharedPdf);

        Path sharedTxt = aliceShared.resolve("notes.txt");
        Files.writeString(sharedTxt, "Some distributed notes");

        // Start Alice and Bob
        runtimeAlice.start();
        runtimeBob.start();

        // 1. Bob searches for "distributed"
        List<SearchResult> searchResults = runtimeBob.searchFiles("distributed");
        assertEquals(1, searchResults.size());
        SearchResult result = searchResults.get(0);
        assertEquals("distributed_systems.pdf", result.file().fileName());
        assertEquals(8192, result.file().fileSize());
        assertEquals(expectedSha256, result.file().fileId());
        assertEquals(1, result.providers().size());
        assertEquals("Alice", result.providers().get(0).displayName());

        // 2. Bob downloads the file directly from the search result!
        CountDownLatch downloadLatch = new CountDownLatch(1);
        AtomicReference<TransferStatus> bobDownloadStatus = new AtomicReference<>();
        runtimeBob.transferManager().setListener(u -> {
            if (u.direction() == TransferDirection.RECEIVE &&
                    (u.status() == TransferStatus.COMPLETED || u.status() == TransferStatus.FAILED)) {
                bobDownloadStatus.set(u.status());
                downloadLatch.countDown();
            }
        });

        runtimeBob.downloadFile(result);

        assertTrue(downloadLatch.await(10, TimeUnit.SECONDS), "Download timed out");
        assertEquals(TransferStatus.COMPLETED, bobDownloadStatus.get());

        Path downloadedFile = bobDownloads.resolve("distributed_systems.pdf");
        assertTrue(Files.exists(downloadedFile));
        assertEquals(expectedSha256, HashUtil.sha256(downloadedFile));
        assertArrayEquals(pdfData, Files.readAllBytes(downloadedFile));

        // 3. Alice disconnects -> Bob searches again -> Alice's files are pruned!
        runtimeAlice.close();
        Thread.sleep(200);

        List<SearchResult> postDisconnectSearch = runtimeBob.searchFiles("distributed");
        assertTrue(postDisconnectSearch.isEmpty(), "Stale files must be pruned when provider disconnects");
    }
}
