package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.PeerListCodec;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.peer.config.AppConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerClientBoundaryTest {

    private ServerSocket fakeTracker;
    private int trackerPort;
    private AppConfig config;

    @BeforeEach
    void setUp() throws IOException {
        fakeTracker = new ServerSocket(0);
        trackerPort = fakeTracker.getLocalPort();
        config = new AppConfig(
                "test-peer",
                "Tester",
                6001,
                "127.0.0.1",
                trackerPort,
                Path.of("downloads"),
                1048576,
                false,
                15000,
                15000,
                120000,
                135000,
                300000,
                4
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        if (fakeTracker != null && !fakeTracker.isClosed()) {
            fakeTracker.close();
        }
    }

    @Test
    void testRejectsTrackerDeclaredCountAbove64() throws Exception {
        Thread serverThread = new Thread(() -> {
            try (Socket client = fakeTracker.accept()) {
                // Read register
                Frame reg = FrameIO.read(client.getInputStream(), 0);
                FrameIO.write(client.getOutputStream(), new Frame(MessageType.TRACKER_REGISTER_OK, Map.of("host", "127.0.0.1")));

                // Read list request
                Frame listReq = FrameIO.read(client.getInputStream(), 0);

                // Return crafted response claiming count = 65
                List<PeerInfo> peers = new ArrayList<>(65);
                for (int i = 0; i < 65; i++) {
                    peers.add(new PeerInfo("p" + i, "Peer " + i, "10.0.0.1", 7000 + i));
                }
                byte[] payload = PeerListCodec.encode(peers);
                FrameIO.write(client.getOutputStream(), new Frame(
                        MessageType.TRACKER_PEER_LIST,
                        Map.of("count", "65"),
                        payload
                ));
            } catch (IOException ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try (TrackerClient client = new TrackerClient(config)) {
            client.connectAndRegister();
            IOException ex = assertThrows(IOException.class, client::listPeers);
            assertTrue(ex.getMessage().contains("Tracker declared count outside protocol bounds: 65"));
        }
    }

    @Test
    void testRejectsTrackerCountMismatch() throws Exception {
        Thread serverThread = new Thread(() -> {
            try (Socket client = fakeTracker.accept()) {
                Frame reg = FrameIO.read(client.getInputStream(), 0);
                FrameIO.write(client.getOutputStream(), new Frame(MessageType.TRACKER_REGISTER_OK, Map.of("host", "127.0.0.1")));

                Frame listReq = FrameIO.read(client.getInputStream(), 0);

                // 2 decoded records but outer count header says 1
                List<PeerInfo> peers = List.of(
                        new PeerInfo("p1", "Alice", "10.0.0.1", 7001),
                        new PeerInfo("p2", "Bob", "10.0.0.2", 7002)
                );
                byte[] payload = PeerListCodec.encode(peers);
                FrameIO.write(client.getOutputStream(), new Frame(
                        MessageType.TRACKER_PEER_LIST,
                        Map.of("count", "1"),
                        payload
                ));
            } catch (IOException ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        try (TrackerClient client = new TrackerClient(config)) {
            client.connectAndRegister();
            IOException ex = assertThrows(IOException.class, client::listPeers);
            assertTrue(ex.getMessage().contains("Mismatched peer count: header=1, decoded=2"));
        }
    }
}
