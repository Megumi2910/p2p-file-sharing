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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferStateInvariantsIT {

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
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testCompleteTransferSizeMatrix() throws Exception {
        int[] sizes = {0, 1, C, C + 1, 3 * C + 17};
        String[] fileNames = {"zero.txt", "one.bin", "exact_c.bin", "c_plus_1.bin", "tài_liệu_3c17.dat"};

        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            String name = fileNames[i];
            Path source = senderDir.resolve(name);
            byte[] data = new byte[size];
            new Random(i + 1).nextBytes(data);
            Files.write(source, data);
            String expectedHash = HashUtil.sha256(source);

            CountDownLatch senderDone = new CountDownLatch(1);
            CountDownLatch receiverDone = new CountDownLatch(1);
            AtomicReference<TransferStatus> senderFinal = new AtomicReference<>();
            AtomicReference<TransferStatus> receiverFinal = new AtomicReference<>();
            AtomicReference<TransferUpdate> receiverLastUpdate = new AtomicReference<>();

            senderManager.setListener(update -> {
                if (update.direction() == TransferDirection.SEND &&
                        (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                    senderFinal.set(update.status());
                    senderDone.countDown();
                }
            });

            receiverManager.setListener(update -> {
                if (update.direction() == TransferDirection.RECEIVE) {
                    if (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED) {
                        receiverFinal.set(update.status());
                        receiverLastUpdate.set(update);
                        receiverDone.countDown();
                    }
                }
            });

            PeerInfo target = new PeerInfo("receiver-peer", "Receiver", "127.0.0.1", receiverPort);
            senderManager.sendFile(target, source);

            assertTrue(senderDone.await(10, TimeUnit.SECONDS), "Sender timed out for " + name);
            assertTrue(receiverDone.await(10, TimeUnit.SECONDS), "Receiver timed out for " + name);

            assertEquals(TransferStatus.COMPLETED, senderFinal.get(), "Sender failed for " + name);
            assertEquals(TransferStatus.COMPLETED, receiverFinal.get(), "Receiver failed for " + name);

            TransferUpdate lastUpdate = receiverLastUpdate.get();
            assertEquals(100, lastUpdate.progressPercent(), "Completed transfer must be 100%");

            Path received = receiverDir.resolve(name);
            assertTrue(Files.exists(received), "Received file must exist: " + name);
            assertEquals(size, Files.size(received));
            assertEquals(expectedHash, HashUtil.sha256(received));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testReceiverRejectsEarlyCompletion() throws Exception {
        // Connect directly to receiver server and send offer for 2 chunks, then send TRANSFER_COMPLETE immediately
        String transferId = UUID.randomUUID().toString();
        String fileHash = HashUtil.sha256(new byte[100]);
        FileMetadata meta = new FileMetadata(
                transferId, fileHash, "early.bin", 100, 50, 2, fileHash, "Sender"
        );

        CountDownLatch receiverFailed = new CountDownLatch(1);
        AtomicReference<TransferStatus> receiverStatus = new AtomicReference<>();
        AtomicLong confirmedBytes = new AtomicLong(-1);

        receiverManager.setListener(update -> {
            if (update.direction() == TransferDirection.RECEIVE &&
                    (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                receiverStatus.set(update.status());
                confirmedBytes.set(update.bytesTransferred());
                receiverFailed.countDown();
            }
        });

        try (Socket socket = new Socket("127.0.0.1", receiverPort)) {
            FrameIO.write(socket.getOutputStream(), new Frame(MessageType.FILE_OFFER, meta.toHeaders()));
            Frame accept = FrameIO.read(socket.getInputStream(), 0);
            assertEquals(MessageType.FILE_ACCEPT, accept.type());

            // Send premature complete without any chunk
            FrameIO.write(socket.getOutputStream(), new Frame(
                    MessageType.TRANSFER_COMPLETE,
                    Map.of("transferId", transferId)
            ));

            assertTrue(receiverFailed.await(5, TimeUnit.SECONDS));
            assertEquals(TransferStatus.FAILED, receiverStatus.get());
            assertEquals(0, confirmedBytes.get());
            // Must not publish final file
            assertTrue(Files.notExists(receiverDir.resolve("early.bin")));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testReceiverHandlesRepeatAndConflictingRepeat() throws Exception {
        String transferId = UUID.randomUUID().toString();
        byte[] chunk0 = "Chunk zero content".getBytes();
        byte[] chunk1 = "Chunk one content!".getBytes();
        ByteArrayOutputStream allBytes = new ByteArrayOutputStream();
        allBytes.write(chunk0);
        allBytes.write(chunk1);
        byte[] fullPayload = allBytes.toByteArray();
        String fileHash = HashUtil.sha256(fullPayload);

        FileMetadata meta = new FileMetadata(
                transferId, fileHash, "repeat.bin", fullPayload.length, chunk0.length, 2, fileHash, "Sender"
        );

        try (Socket socket = new Socket("127.0.0.1", receiverPort)) {
            FrameIO.write(socket.getOutputStream(), new Frame(MessageType.FILE_OFFER, meta.toHeaders()));
            Frame accept = FrameIO.read(socket.getInputStream(), 0);
            assertEquals(MessageType.FILE_ACCEPT, accept.type());

            // Send chunk 0
            Map<String, String> h0 = new LinkedHashMap<>();
            h0.put("transferId", transferId);
            h0.put("chunkIndex", "0");
            h0.put("offset", "0");
            h0.put("chunkSha256", HashUtil.sha256(chunk0));
            FrameIO.write(socket.getOutputStream(), new Frame(MessageType.CHUNK_DATA, h0, chunk0));

            Frame ack0 = FrameIO.read(socket.getInputStream(), 0);
            assertEquals(MessageType.CHUNK_ACK, ack0.type());
            assertEquals("OK", ack0.requireHeader("status"));

            // Repeat chunk 0 with identical payload -> should re-ACK OK
            FrameIO.write(socket.getOutputStream(), new Frame(MessageType.CHUNK_DATA, h0, chunk0));
            Frame ackRepeat = FrameIO.read(socket.getInputStream(), 0);
            assertEquals(MessageType.CHUNK_ACK, ackRepeat.type());
            assertEquals("OK", ackRepeat.requireHeader("status"));

            // Conflicting repeat: same index 0 but different payload
            byte[] corrupt0 = new byte[chunk0.length];
            System.arraycopy(chunk0, 0, corrupt0, 0, chunk0.length);
            corrupt0[0] ^= 0xFF;
            Map<String, String> hCorrupt = new LinkedHashMap<>(h0);
            hCorrupt.put("chunkSha256", HashUtil.sha256(corrupt0));
            FrameIO.write(socket.getOutputStream(), new Frame(MessageType.CHUNK_DATA, hCorrupt, corrupt0));

            // Socket should be closed or session failed
            assertThrows(IOException.class, () -> FrameIO.read(socket.getInputStream(), 0));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testSenderHandlesCorruptChunkAndRetry() throws Exception {
        // Test that if receiver asks for RETRY on first attempt, sender re-sends and succeeds when receiver ACKs OK
        // We use a custom fake receiver socket to test sender's 3-retry loop
        try (ServerSocket fakeReceiver = new ServerSocket(0)) {
            int fakePort = fakeReceiver.getLocalPort();
            Path source = senderDir.resolve("retry_test.txt");
            Files.writeString(source, "data to be retried");

            CountDownLatch senderDone = new CountDownLatch(1);
            AtomicReference<TransferStatus> senderFinal = new AtomicReference<>();
            senderManager.setListener(update -> {
                if (update.direction() == TransferDirection.SEND &&
                        (update.status() == TransferStatus.COMPLETED || update.status() == TransferStatus.FAILED)) {
                    senderFinal.set(update.status());
                    senderDone.countDown();
                }
            });

            Thread fakeThread = new Thread(() -> {
                try (Socket client = fakeReceiver.accept()) {
                    Frame offer = FrameIO.read(client.getInputStream(), 0);
                    String tid = offer.requireHeader("transferId");
                    FrameIO.write(client.getOutputStream(), new Frame(MessageType.FILE_ACCEPT, Map.of("transferId", tid)));

                    // Read chunk 0 attempt 1 -> respond RETRY
                    Frame chunkAttempt1 = FrameIO.read(client.getInputStream(), C);
                    assertEquals(MessageType.CHUNK_DATA, chunkAttempt1.type());
                    FrameIO.write(client.getOutputStream(), new Frame(
                            MessageType.CHUNK_ACK,
                            Map.of("transferId", tid, "chunkIndex", "0", "status", "RETRY")
                    ));

                    // Read chunk 0 attempt 2 -> respond OK
                    Frame chunkAttempt2 = FrameIO.read(client.getInputStream(), C);
                    assertEquals(MessageType.CHUNK_DATA, chunkAttempt2.type());
                    FrameIO.write(client.getOutputStream(), new Frame(
                            MessageType.CHUNK_ACK,
                            Map.of("transferId", tid, "chunkIndex", "0", "status", "OK")
                    ));

                    // Read TRANSFER_COMPLETE -> respond OK
                    Frame complete = FrameIO.read(client.getInputStream(), 0);
                    assertEquals(MessageType.TRANSFER_COMPLETE, complete.type());
                    FrameIO.write(client.getOutputStream(), new Frame(
                            MessageType.VERIFY_RESULT,
                            Map.of("transferId", tid, "status", "OK")
                    ));
                } catch (IOException ignored) {}
            });
            fakeThread.setDaemon(true);
            fakeThread.start();

            PeerInfo target = new PeerInfo("fake-peer", "Fake", "127.0.0.1", fakePort);
            senderManager.sendFile(target, source);

            assertTrue(senderDone.await(5, TimeUnit.SECONDS));
            assertEquals(TransferStatus.COMPLETED, senderFinal.get());
        }
    }
}
