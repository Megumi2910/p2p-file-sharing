package vn.edu.p2p.tracker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.PeerListCodec;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerServerIT {

    private TrackerServer trackerServer;
    private Thread serverThread;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        trackerServer = new TrackerServer(0);
        CountDownLatch readyLatch = new CountDownLatch(1);
        serverThread = new Thread(() -> {
            try {
                readyLatch.countDown();
                trackerServer.start();
            } catch (IOException ignored) {
            }
        }, "tracker-test-thread");
        serverThread.setDaemon(true);
        serverThread.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                port = trackerServer.localPort();
                if (port > 0) {
                    break;
                }
            } catch (IllegalStateException e) {
                Thread.sleep(20);
            }
        }
        assertTrue(port > 0, "Tracker server failed to bind ephemeral port");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (trackerServer != null) {
            trackerServer.close();
        }
        if (serverThread != null) {
            serverThread.join(2000);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testRegisterListAndDisconnect() throws Exception {
        // Connect peer 1 (Alice)
        try (Socket socket1 = new Socket("127.0.0.1", port)) {
            Frame register1 = new Frame(MessageType.TRACKER_REGISTER, Map.of(
                    "peerId", "peer-alice",
                    "displayName", "Alice",
                    "peerPort", "7001"
            ));
            FrameIO.write(socket1.getOutputStream(), register1);

            Frame resp1 = FrameIO.read(socket1.getInputStream(), 0);
            assertEquals(MessageType.TRACKER_REGISTER_OK, resp1.type());
            assertEquals("1", resp1.requireHeader("peerCount"));

            // Connect peer 2 (Bob)
            try (Socket socket2 = new Socket("127.0.0.1", port)) {
                Frame register2 = new Frame(MessageType.TRACKER_REGISTER, Map.of(
                        "peerId", "peer-bob",
                        "displayName", "Bob",
                        "peerPort", "7002"
                ));
                FrameIO.write(socket2.getOutputStream(), register2);

                Frame resp2 = FrameIO.read(socket2.getInputStream(), 0);
                assertEquals(MessageType.TRACKER_REGISTER_OK, resp2.type());
                assertEquals("2", resp2.requireHeader("peerCount"));

                // Bob requests peer list (should see Alice, but not Bob)
                Frame listRequest = new Frame(MessageType.TRACKER_LIST_PEERS);
                FrameIO.write(socket2.getOutputStream(), listRequest);

                Frame listResp = FrameIO.read(socket2.getInputStream(), 1024 * 1024);
                assertEquals(MessageType.TRACKER_PEER_LIST, listResp.type());
                assertEquals("1", listResp.requireHeader("count"));

                List<PeerInfo> peers = PeerListCodec.decode(listResp.payload());
                assertEquals(1, peers.size());
                PeerInfo peer = peers.get(0);
                assertEquals("peer-alice", peer.peerId());
                assertEquals("Alice", peer.displayName());
                assertEquals(7001, peer.port());

                // Bob disconnects cleanly
                Frame disconnect = new Frame(MessageType.TRACKER_DISCONNECT);
                FrameIO.write(socket2.getOutputStream(), disconnect);
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDuplicateIdRejectedAndCanBeReusedAfterDisconnect() throws Exception {
        // Peer A registers
        try (Socket socketA = new Socket("127.0.0.1", port)) {
            Frame registerA = new Frame(MessageType.TRACKER_REGISTER, Map.of(
                    "peerId", "shared-id",
                    "displayName", "Peer A",
                    "peerPort", "7001"
            ));
            FrameIO.write(socketA.getOutputStream(), registerA);
            Frame respA = FrameIO.read(socketA.getInputStream(), 0);
            assertEquals(MessageType.TRACKER_REGISTER_OK, respA.type());

            // Peer B attempts duplicate ID
            try (Socket socketB = new Socket("127.0.0.1", port)) {
                Frame registerB = new Frame(MessageType.TRACKER_REGISTER, Map.of(
                        "peerId", "shared-id",
                        "displayName", "Peer B",
                        "peerPort", "7002"
                ));
                FrameIO.write(socketB.getOutputStream(), registerB);
                Frame respB = FrameIO.read(socketB.getInputStream(), 0);
                assertEquals(MessageType.ERROR, respB.type());
                assertTrue(respB.requireHeader("message").contains("Peer ID already registered"));
            }

            // Verify Peer A is still registered and was not evicted by B's failure
            Frame listReq = new Frame(MessageType.TRACKER_LIST_PEERS);
            FrameIO.write(socketA.getOutputStream(), listReq);
            Frame listResp = FrameIO.read(socketA.getInputStream(), 1024 * 1024);
            assertEquals(MessageType.TRACKER_PEER_LIST, listResp.type());
            // Requester A excluded -> list size 0
            assertEquals("0", listResp.requireHeader("count"));

            // Disconnect A
            FrameIO.write(socketA.getOutputStream(), new Frame(MessageType.TRACKER_DISCONNECT));
        }

        // Wait brief interval for unregister to take effect
        Thread.sleep(100);

        // Peer C can now register using the released ID
        try (Socket socketC = new Socket("127.0.0.1", port)) {
            Frame registerC = new Frame(MessageType.TRACKER_REGISTER, Map.of(
                    "peerId", "shared-id",
                    "displayName", "Peer C",
                    "peerPort", "7003"
            ));
            FrameIO.write(socketC.getOutputStream(), registerC);
            Frame respC = FrameIO.read(socketC.getInputStream(), 0);
            assertEquals(MessageType.TRACKER_REGISTER_OK, respC.type());
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testSecondRegistrationOnSameSocketRejected() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            Frame reg1 = new Frame(MessageType.TRACKER_REGISTER, Map.of(
                    "peerId", "first-id",
                    "displayName", "First",
                    "peerPort", "7001"
            ));
            FrameIO.write(socket.getOutputStream(), reg1);
            Frame resp1 = FrameIO.read(socket.getInputStream(), 0);
            assertEquals(MessageType.TRACKER_REGISTER_OK, resp1.type());

            // Attempt second registration on same socket
            Frame reg2 = new Frame(MessageType.TRACKER_REGISTER, Map.of(
                    "peerId", "second-id",
                    "displayName", "Second",
                    "peerPort", "7002"
            ));
            FrameIO.write(socket.getOutputStream(), reg2);
            Frame resp2 = FrameIO.read(socket.getInputStream(), 0);
            assertEquals(MessageType.ERROR, resp2.type());
            assertTrue(resp2.requireHeader("message").contains("Already registered"));
        }
    }
}
