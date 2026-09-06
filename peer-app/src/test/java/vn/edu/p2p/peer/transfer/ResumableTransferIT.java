package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.network.PeerServer;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumableTransferIT {

    @TempDir
    Path tempDir;

    private Path senderDir;
    private Path receiverDir;
    private TransferManager senderManager;
    private TransferManager receiverManager;
    private PeerServer receiverServer;
    private int receiverPort;
    private static final int CHUNK_SIZE = 1024;

    @BeforeEach
    void setUp() throws IOException {
        senderDir = tempDir.resolve("sender");
        receiverDir = tempDir.resolve("receiver");
        Files.createDirectories(senderDir);
        Files.createDirectories(receiverDir);

        AppConfig receiverConfig = new AppConfig(
                "receiver", "Receiver", 6001, "127.0.0.1", 5000, receiverDir, CHUNK_SIZE, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        receiverManager = new TransferManager(receiverConfig);
        receiverServer = new PeerServer(0, receiverManager);
        receiverServer.start();
        receiverPort = receiverServer.localPort();

        AppConfig senderConfig = new AppConfig(
                "sender", "Sender", 6002, "127.0.0.1", 5000, senderDir, CHUNK_SIZE, false,
                15000, 15000, 120000, 135000, 300000, 4
        );
        senderManager = new TransferManager(senderConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (senderManager != null) senderManager.close();
        if (receiverServer != null) {
            try { receiverServer.close(); } catch (IOException ignored) {}
        }
        if (receiverManager != null) receiverManager.close();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testInterruptedTransferResumesAndCompletes() throws Exception {
        // Create 10-chunk file (10,240 bytes)
        int totalBytes = 10 * CHUNK_SIZE;
        Path sourceFile = senderDir.resolve("resumable_book.bin");
        byte[] content = new byte[totalBytes];
        new Random(42).nextBytes(content);
        Files.write(sourceFile, content);
        String expectedHash = HashUtil.sha256(sourceFile);

        String initialTransferId = UUID.randomUUID().toString();
        FileMetadata meta = new FileMetadata(
                initialTransferId, expectedHash, "resumable_book.bin", totalBytes, CHUNK_SIZE, 10, expectedHash, "Sender"
        );

        // Step 1: Simulate interrupted transfer by sending offer and only first 4 chunks (0, 1, 2, 3), then disconnecting
        try (Socket s = new Socket("127.0.0.1", receiverPort)) {
            FrameIO.write(s.getOutputStream(), new Frame(MessageType.FILE_OFFER, meta.toHeaders()));
            Frame accept = FrameIO.read(s.getInputStream(), 0);
            assertEquals(MessageType.FILE_ACCEPT, accept.type());

            for (int i = 0; i < 4; i++) {
                byte[] chunkBytes = new byte[CHUNK_SIZE];
                System.arraycopy(content, i * CHUNK_SIZE, chunkBytes, 0, CHUNK_SIZE);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("transferId", initialTransferId);
                headers.put("chunkIndex", Integer.toString(i));
                headers.put("offset", Integer.toString(i * CHUNK_SIZE));
                headers.put("chunkSha256", HashUtil.sha256(chunkBytes));

                FrameIO.write(s.getOutputStream(), new Frame(MessageType.CHUNK_DATA, headers, chunkBytes));
                Frame ack = FrameIO.read(s.getInputStream(), 0);
                assertEquals("OK", ack.requireHeader("status"));
            }
            // Abrupt disconnect after chunk 3
        }

        // Verify that .part and .part.meta were retained and have prefix = 4
        List<Path> metaFiles = listFiles(receiverDir, "*.part.meta");
        assertEquals(1, metaFiles.size(), "Expected exactly one .part.meta file retained");
        TransferMeta savedMeta = TransferMeta.load(metaFiles.get(0));
        assertEquals(4, savedMeta.contiguousReceivedPrefix());

        // Step 2: Now send the same file using the production senderManager
        CountDownLatch senderLatch = new CountDownLatch(1);
        CountDownLatch receiverLatch = new CountDownLatch(1);
        AtomicReference<TransferStatus> senderFinal = new AtomicReference<>();
        AtomicReference<TransferStatus> receiverFinal = new AtomicReference<>();

        senderManager.setListener(u -> {
            if (u.direction() == TransferDirection.SEND &&
                    (u.status() == TransferStatus.COMPLETED || u.status() == TransferStatus.FAILED)) {
                senderFinal.set(u.status());
                senderLatch.countDown();
            }
        });

        receiverManager.setListener(u -> {
            if (u.direction() == TransferDirection.RECEIVE &&
                    (u.status() == TransferStatus.COMPLETED || u.status() == TransferStatus.FAILED)) {
                receiverFinal.set(u.status());
                receiverLatch.countDown();
            }
        });

        PeerInfo target = new PeerInfo("receiver", "Receiver", "127.0.0.1", receiverPort);
        senderManager.sendFile(target, sourceFile);

        assertTrue(senderLatch.await(10, TimeUnit.SECONDS), "Sender did not complete");
        assertTrue(receiverLatch.await(10, TimeUnit.SECONDS), "Receiver did not complete");

        assertEquals(TransferStatus.COMPLETED, senderFinal.get());
        assertEquals(TransferStatus.COMPLETED, receiverFinal.get());

        // Step 3: Verify destination file is published, full size, matching SHA-256
        Path publishedFile = receiverDir.resolve("resumable_book.bin");
        assertTrue(Files.exists(publishedFile));
        assertEquals(totalBytes, Files.size(publishedFile));
        assertEquals(expectedHash, HashUtil.sha256(publishedFile));

        // Step 4: Verify BOTH .part and .part.meta were cleaned up
        assertEquals(0, listFiles(receiverDir, "*.part").size(), "No .part files should remain after publication");
        assertEquals(0, listFiles(receiverDir, "*.part.meta").size(), "No .part.meta files should remain after publication");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCorruptedMetadataFallsBackToFreshTransfer() throws Exception {
        int totalBytes = 2 * CHUNK_SIZE;
        Path sourceFile = senderDir.resolve("corrupt_meta_test.bin");
        byte[] content = new byte[totalBytes];
        new Random(7).nextBytes(content);
        Files.write(sourceFile, content);
        String expectedHash = HashUtil.sha256(sourceFile);

        // Pre-create a corrupted .part.meta in receiverDir
        Path dummyPart = Files.createTempFile(receiverDir, ".p2p-", ".part");
        Path dummyMeta = Path.of(dummyPart + ".meta");
        Files.writeString(dummyMeta, "NOT VALID BINARY CONTENT");

        CountDownLatch senderLatch = new CountDownLatch(1);
        CountDownLatch receiverLatch = new CountDownLatch(1);

        senderManager.setListener(u -> {
            if (u.direction() == TransferDirection.SEND && u.status() == TransferStatus.COMPLETED) {
                senderLatch.countDown();
            }
        });
        receiverManager.setListener(u -> {
            if (u.direction() == TransferDirection.RECEIVE && u.status() == TransferStatus.COMPLETED) {
                receiverLatch.countDown();
            }
        });

        PeerInfo target = new PeerInfo("receiver", "Receiver", "127.0.0.1", receiverPort);
        senderManager.sendFile(target, sourceFile);

        assertTrue(senderLatch.await(5, TimeUnit.SECONDS));
        assertTrue(receiverLatch.await(5, TimeUnit.SECONDS));

        Path published = receiverDir.resolve("corrupt_meta_test.bin");
        assertTrue(Files.exists(published));
        assertEquals(expectedHash, HashUtil.sha256(published));
    }

    private List<Path> listFiles(Path dir, String glob) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path p : stream) {
                result.add(p);
            }
        }
        return result;
    }
}
