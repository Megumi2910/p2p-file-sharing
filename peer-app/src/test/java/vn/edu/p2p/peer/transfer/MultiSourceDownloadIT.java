package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.network.PeerServer;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSourceDownloadIT {

    @TempDir
    Path tempDir;

    private Path aliceShared;
    private Path bobShared;
    private Path charlieDownloads;

    private TransferManager aliceManager;
    private TransferManager bobManager;
    private TransferManager charlieManager;

    private PeerServer aliceServer;
    private PeerServer bobServer;
    private int alicePort;
    private int bobPort;

    private static final int C = 1024;

    @BeforeEach
    void setUp() throws IOException {
        aliceShared = tempDir.resolve("alice_shared");
        bobShared = tempDir.resolve("bob_shared");
        charlieDownloads = tempDir.resolve("charlie_downloads");
        Files.createDirectories(aliceShared);
        Files.createDirectories(bobShared);
        Files.createDirectories(charlieDownloads);

        AppConfig aliceConfig = new AppConfig(
                "alice", "Alice", 6001, "127.0.0.1", 5000, tempDir.resolve("alice_down"), aliceShared, C, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        aliceManager = new TransferManager(aliceConfig);
        aliceServer = new PeerServer(0, aliceManager);
        aliceServer.start();
        alicePort = aliceServer.localPort();

        AppConfig bobConfig = new AppConfig(
                "bob", "Bob", 6002, "127.0.0.1", 5000, tempDir.resolve("bob_down"), bobShared, C, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        bobManager = new TransferManager(bobConfig);
        bobServer = new PeerServer(0, bobManager);
        bobServer.start();
        bobPort = bobServer.localPort();

        AppConfig charlieConfig = new AppConfig(
                "charlie", "Charlie", 6003, "127.0.0.1", 5000, charlieDownloads, tempDir.resolve("charlie_shared"), C, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        charlieManager = new TransferManager(charlieConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (charlieManager != null) charlieManager.close();
        if (aliceServer != null) {
            try { aliceServer.close(); } catch (IOException ignored) {}
        }
        if (bobServer != null) {
            try { bobServer.close(); } catch (IOException ignored) {}
        }
        if (aliceManager != null) aliceManager.close();
        if (bobManager != null) bobManager.close();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testConcurrentMultiSourceDownload() throws Exception {
        // Create an 8-chunk file (8,192 bytes)
        int size = 8 * C;
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);

        // Both Alice and Bob have the file in their shared folders
        Path aliceFile = aliceShared.resolve("shared_book.bin");
        Path bobFile = bobShared.resolve("shared_book.bin");
        Files.write(aliceFile, data);
        Files.write(bobFile, data);
        String sha = HashUtil.sha256(aliceFile);

        FileRecord targetFile = new FileRecord(sha, "shared_book.bin", size, C, 8);
        List<PeerInfo> providers = List.of(
                new PeerInfo("alice", "Alice", "127.0.0.1", alicePort),
                new PeerInfo("bob", "Bob", "127.0.0.1", bobPort)
        );

        AtomicInteger aliceChunksServed = new AtomicInteger(0);
        AtomicInteger bobChunksServed = new AtomicInteger(0);

        aliceManager.setListener(u -> {
            if (u.direction() == TransferDirection.SEND && u.status() == TransferStatus.TRANSFERRING) {
                aliceChunksServed.incrementAndGet();
            }
        });
        bobManager.setListener(u -> {
            if (u.direction() == TransferDirection.SEND && u.status() == TransferStatus.TRANSFERRING) {
                bobChunksServed.incrementAndGet();
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TransferStatus> finalStatus = new AtomicReference<>();

        charlieManager.setListener(u -> {
            if (u.direction() == TransferDirection.RECEIVE &&
                    (u.status() == TransferStatus.COMPLETED || u.status() == TransferStatus.FAILED)) {
                finalStatus.set(u.status());
                latch.countDown();
            }
        });

        charlieManager.downloadMultiSource(providers, targetFile);

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Download timed out");
        assertEquals(TransferStatus.COMPLETED, finalStatus.get());

        // Verify both providers contributed in parallel
        assertTrue(aliceChunksServed.get() > 0, "Alice must serve at least 1 chunk (served: " + aliceChunksServed.get() + ")");
        assertTrue(bobChunksServed.get() > 0, "Bob must serve at least 1 chunk (served: " + bobChunksServed.get() + ")");
        assertEquals(8, aliceChunksServed.get() + bobChunksServed.get(), "Total chunks served must equal 8");

        Path downloaded = charlieDownloads.resolve("shared_book.bin");
        assertTrue(Files.exists(downloaded));
        assertEquals(size, Files.size(downloaded));
        assertEquals(sha, HashUtil.sha256(downloaded));
        assertArrayEquals(data, Files.readAllBytes(downloaded));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testFailoverWhenOnePeerFailsMidTransfer() throws Exception {
        // Create an 8-chunk file (8,192 bytes)
        int size = 8 * C;
        byte[] data = new byte[size];
        new Random(99).nextBytes(data);

        // Both Alice and Bob have the file initially
        Path aliceFile = aliceShared.resolve("failover_test.bin");
        Path bobFile = bobShared.resolve("failover_test.bin");
        Files.write(aliceFile, data);
        Files.write(bobFile, data);
        String sha = HashUtil.sha256(bobFile);

        FileRecord targetFile = new FileRecord(sha, "failover_test.bin", size, C, 8);
        List<PeerInfo> providers = List.of(
                new PeerInfo("alice", "Alice", "127.0.0.1", alicePort),
                new PeerInfo("bob", "Bob", "127.0.0.1", bobPort)
        );

        // Intercept Alice: after serving 1 chunk, abruptly shut down Alice's server
        AtomicInteger aliceServed = new AtomicInteger(0);
        aliceManager.setListener(u -> {
            if (u.direction() == TransferDirection.SEND && u.status() == TransferStatus.TRANSFERRING) {
                if (aliceServed.incrementAndGet() == 1) {
                    try {
                        aliceServer.close();
                        aliceManager.close();
                    } catch (Exception ignored) {}
                }
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TransferStatus> finalStatus = new AtomicReference<>();

        charlieManager.setListener(u -> {
            if (u.direction() == TransferDirection.RECEIVE &&
                    (u.status() == TransferStatus.COMPLETED || u.status() == TransferStatus.FAILED)) {
                finalStatus.set(u.status());
                latch.countDown();
            }
        });

        // Charlie downloads from [Alice, Bob]. Alice drops mid-transfer, Bob recovers and serves the rest!
        charlieManager.downloadMultiSource(providers, targetFile);

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Failover download timed out");
        assertEquals(TransferStatus.COMPLETED, finalStatus.get());

        Path downloaded = charlieDownloads.resolve("failover_test.bin");
        assertTrue(Files.exists(downloaded));
        assertEquals(size, Files.size(downloaded));
        assertEquals(sha, HashUtil.sha256(downloaded));
        assertArrayEquals(data, Files.readAllBytes(downloaded));
    }
}
