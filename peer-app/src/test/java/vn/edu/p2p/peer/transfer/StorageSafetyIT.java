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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSafetyIT {

    @TempDir
    Path tempDir;

    private Path senderDir;
    private Path receiverDir;
    private TransferManager senderManager;
    private TransferManager receiverManager;
    private PeerServer receiverServer;
    private int receiverPort;
    private static final int C = 4096;

    @BeforeEach
    void setUp() throws IOException {
        senderDir = tempDir.resolve("sender");
        receiverDir = tempDir.resolve("receiver");
        Files.createDirectories(senderDir);
        Files.createDirectories(receiverDir);

        AppConfig receiverConfig = new AppConfig(
                "receiver-peer", "Receiver", 6001, "127.0.0.1", 5000, receiverDir, C, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        receiverManager = new TransferManager(receiverConfig);
        receiverServer = new PeerServer(0, receiverManager);
        receiverServer.start();
        receiverPort = receiverServer.localPort();

        AppConfig senderConfig = new AppConfig(
                "sender-peer", "Sender", 6002, "127.0.0.1", 5000, senderDir, C, false,
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
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testSentinelFileNotOverwritten() throws Exception {
        // Pre-create sentinel file in receiver directory
        Path sentinel = receiverDir.resolve("report.txt");
        String sentinelContent = "SENTINEL CONTENT THAT MUST NEVER BE OVERWRITTEN";
        Files.writeString(sentinel, sentinelContent);

        // Sender sends a different file also named report.txt
        Path source = senderDir.resolve("report.txt");
        String newContent = "NEW VERIFIED TRANSFERRED CONTENT";
        Files.writeString(source, newContent);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<TransferStatus> finalStatus = new AtomicReference<>();
        senderManager.setListener(update -> {
            if (update.direction() == TransferDirection.SEND &&
                    (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                finalStatus.set(update.status());
                done.countDown();
            }
        });

        PeerInfo target = new PeerInfo("receiver-peer", "Receiver", "127.0.0.1", receiverPort);
        senderManager.sendFile(target, source);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(TransferStatus.COMPLETED, finalStatus.get());

        // Assert sentinel content was preserved
        assertEquals(sentinelContent, Files.readString(sentinel));

        // Assert new file published as report (1).txt
        Path collisionFile = receiverDir.resolve("report (1).txt");
        assertTrue(Files.exists(collisionFile), "Expected published collision file report (1).txt");
        assertEquals(newContent, Files.readString(collisionFile));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testTwoConcurrentReceivesSameName() throws Exception {
        byte[] contentA = "Content A for concurrent receive".getBytes();
        byte[] contentB = "Content B for concurrent receive".getBytes();
        String hashA = HashUtil.sha256(contentA);
        String hashB = HashUtil.sha256(contentB);

        String tidA = UUID.randomUUID().toString();
        String tidB = UUID.randomUUID().toString();

        FileMetadata metaA = new FileMetadata(tidA, hashA, "shared.dat", contentA.length, C, 1, hashA, "SenderA");
        FileMetadata metaB = new FileMetadata(tidB, hashB, "shared.dat", contentB.length, C, 1, hashB, "SenderB");

        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch latch = new CountDownLatch(2);

        Runnable clientA = () -> {
            try (Socket s = new Socket("127.0.0.1", receiverPort)) {
                FrameIO.write(s.getOutputStream(), new Frame(MessageType.FILE_OFFER, metaA.toHeaders()));
                Frame accept = FrameIO.read(s.getInputStream(), 0);
                assertEquals(MessageType.FILE_ACCEPT, accept.type());

                // Synchronize before writing chunk and publishing
                barrier.await(5, TimeUnit.SECONDS);

                Map<String, String> h = new LinkedHashMap<>();
                h.put("transferId", tidA);
                h.put("chunkIndex", "0");
                h.put("offset", "0");
                h.put("chunkSha256", hashA);
                FrameIO.write(s.getOutputStream(), new Frame(MessageType.CHUNK_DATA, h, contentA));
                Frame ack = FrameIO.read(s.getInputStream(), 0);
                assertEquals("OK", ack.requireHeader("status"));

                FrameIO.write(s.getOutputStream(), new Frame(MessageType.TRANSFER_COMPLETE, Map.of("transferId", tidA)));
                Frame verify = FrameIO.read(s.getInputStream(), 0);
                assertEquals("OK", verify.requireHeader("status"));
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        Runnable clientB = () -> {
            try (Socket s = new Socket("127.0.0.1", receiverPort)) {
                FrameIO.write(s.getOutputStream(), new Frame(MessageType.FILE_OFFER, metaB.toHeaders()));
                Frame accept = FrameIO.read(s.getInputStream(), 0);
                assertEquals(MessageType.FILE_ACCEPT, accept.type());

                // Synchronize
                barrier.await(5, TimeUnit.SECONDS);

                Map<String, String> h = new LinkedHashMap<>();
                h.put("transferId", tidB);
                h.put("chunkIndex", "0");
                h.put("offset", "0");
                h.put("chunkSha256", hashB);
                FrameIO.write(s.getOutputStream(), new Frame(MessageType.CHUNK_DATA, h, contentB));
                Frame ack = FrameIO.read(s.getInputStream(), 0);
                assertEquals("OK", ack.requireHeader("status"));

                FrameIO.write(s.getOutputStream(), new Frame(MessageType.TRANSFER_COMPLETE, Map.of("transferId", tidB)));
                Frame verify = FrameIO.read(s.getInputStream(), 0);
                assertEquals("OK", verify.requireHeader("status"));
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        Thread tA = new Thread(clientA);
        Thread tB = new Thread(clientB);
        tA.start();
        tB.start();

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Both concurrent transfers should complete");

        // Two distinct files should exist: shared.dat and shared (1).dat
        Path file1 = receiverDir.resolve("shared.dat");
        Path file2 = receiverDir.resolve("shared (1).dat");
        assertTrue(Files.exists(file1));
        assertTrue(Files.exists(file2));

        byte[] f1Bytes = Files.readAllBytes(file1);
        byte[] f2Bytes = Files.readAllBytes(file2);

        // One must match A and the other must match B
        boolean aFirst = HashUtil.sha256(f1Bytes).equals(hashA);
        if (aFirst) {
            assertArrayEquals(contentA, f1Bytes);
            assertArrayEquals(contentB, f2Bytes);
        } else {
            assertArrayEquals(contentB, f1Bytes);
            assertArrayEquals(contentA, f2Bytes);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testWholeFileMismatchRetainsOwnedPart() throws Exception {
        byte[] actualData = "Real bytes sent".getBytes();
        // Offer claims hash of something else
        String fakeHash = HashUtil.sha256("Different content".getBytes());
        String tid = UUID.randomUUID().toString();

        FileMetadata meta = new FileMetadata(
                tid, fakeHash, "mismatch.dat", actualData.length, C, 1, fakeHash, "Sender"
        );

        CountDownLatch receiverFailed = new CountDownLatch(1);
        AtomicReference<TransferUpdate> failUpdate = new AtomicReference<>();
        receiverManager.setListener(update -> {
            if (update.direction() == TransferDirection.RECEIVE && update.status() == TransferStatus.FAILED) {
                failUpdate.set(update);
                receiverFailed.countDown();
            }
        });

        try (Socket s = new Socket("127.0.0.1", receiverPort)) {
            FrameIO.write(s.getOutputStream(), new Frame(MessageType.FILE_OFFER, meta.toHeaders()));
            Frame accept = FrameIO.read(s.getInputStream(), 0);
            assertEquals(MessageType.FILE_ACCEPT, accept.type());

            Map<String, String> h = new LinkedHashMap<>();
            h.put("transferId", tid);
            h.put("chunkIndex", "0");
            h.put("offset", "0");
            h.put("chunkSha256", HashUtil.sha256(actualData)); // Chunk hash matches actual data
            FrameIO.write(s.getOutputStream(), new Frame(MessageType.CHUNK_DATA, h, actualData));

            Frame ack = FrameIO.read(s.getInputStream(), 0);
            assertEquals("OK", ack.requireHeader("status"));

            // Request complete
            FrameIO.write(s.getOutputStream(), new Frame(MessageType.TRANSFER_COMPLETE, Map.of("transferId", tid)));
            Frame verify = FrameIO.read(s.getInputStream(), 0);
            assertEquals("MISMATCH", verify.requireHeader("status"));
        }

        assertTrue(receiverFailed.await(5, TimeUnit.SECONDS));
        assertNotNull(failUpdate.get());
        assertTrue(failUpdate.get().message().contains("partial:"));

        // No final file mismatch.dat
        assertTrue(Files.notExists(receiverDir.resolve("mismatch.dat")));

        // An owned .part file must remain
        List<Path> parts = listPartFiles(receiverDir);
        assertEquals(1, parts.size(), "Owned partial file must be retained on whole-file mismatch");
        assertTrue(parts.get(0).getFileName().toString().startsWith(".p2p-"));
        assertTrue(parts.get(0).getFileName().toString().endsWith(".part"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testInterruptedReceiveRetainsOwnedPart() throws Exception {
        byte[] chunk0 = new byte[C];
        new Random(1).nextBytes(chunk0);
        long fileSize = C * 2;
        String tid = UUID.randomUUID().toString();
        String fakeHash = HashUtil.sha256("some-file".getBytes());

        FileMetadata meta = new FileMetadata(
                tid, fakeHash, "interrupted.dat", fileSize, C, 2, fakeHash, "Sender"
        );

        CountDownLatch receiverFailed = new CountDownLatch(1);
        AtomicReference<TransferUpdate> failUpdate = new AtomicReference<>();
        receiverManager.setListener(update -> {
            if (update.direction() == TransferDirection.RECEIVE && update.status() == TransferStatus.FAILED) {
                failUpdate.set(update);
                receiverFailed.countDown();
            }
        });

        try (Socket s = new Socket("127.0.0.1", receiverPort)) {
            FrameIO.write(s.getOutputStream(), new Frame(MessageType.FILE_OFFER, meta.toHeaders()));
            Frame accept = FrameIO.read(s.getInputStream(), 0);
            assertEquals(MessageType.FILE_ACCEPT, accept.type());

            Map<String, String> h = new LinkedHashMap<>();
            h.put("transferId", tid);
            h.put("chunkIndex", "0");
            h.put("offset", "0");
            h.put("chunkSha256", HashUtil.sha256(chunk0));
            FrameIO.write(s.getOutputStream(), new Frame(MessageType.CHUNK_DATA, h, chunk0));

            Frame ack = FrameIO.read(s.getInputStream(), 0);
            assertEquals("OK", ack.requireHeader("status"));

            // Abruptly close socket without sending chunk 1 or complete
            s.close();
        }

        assertTrue(receiverFailed.await(5, TimeUnit.SECONDS));
        assertNotNull(failUpdate.get());

        // No final file
        assertTrue(Files.notExists(receiverDir.resolve("interrupted.dat")));

        // Owned .part file retained with received bytes
        List<Path> parts = listPartFiles(receiverDir);
        assertEquals(1, parts.size());
        assertEquals(C, Files.size(parts.get(0)));
    }

    private List<Path> listPartFiles(Path dir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.part")) {
            for (Path p : stream) {
                result.add(p);
            }
        }
        return result;
    }
}
