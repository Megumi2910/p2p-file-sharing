package vn.edu.p2p.tracker;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.PeerListCodec;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TrackerServer implements AutoCloseable {
    private final int port;
    private final PeerRegistry registry = new PeerRegistry();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;

    public TrackerServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("[TRACKER] Listening on 0.0.0.0:" + port);

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clients.submit(() -> handleClient(socket));
            } catch (IOException ex) {
                if (running) {
                    throw ex;
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        String peerId = null;
        try (socket) {
            System.out.println("[TRACKER] Connection from " + socket.getRemoteSocketAddress());

            while (true) {
                Frame frame = FrameIO.read(socket.getInputStream());

                switch (frame.type()) {
                    case TRACKER_REGISTER -> {
                        peerId = frame.requireHeader("peerId");
                        String displayName = frame.requireHeader("displayName");
                        int peerPort = Integer.parseInt(frame.requireHeader("peerPort"));

                        // The tracker uses the actual source IP of the TCP connection.
                        // This is usually the correct VM address on a host-only network.
                        String host = socket.getInetAddress().getHostAddress();
                        PeerInfo peer = new PeerInfo(peerId, displayName, host, peerPort);
                        registry.register(peer);

                        FrameIO.write(socket.getOutputStream(), new Frame(
                                MessageType.TRACKER_REGISTER_OK,
                                Map.of("host", host, "peerCount", Integer.toString(registry.size()))
                        ));
                        System.out.println("[TRACKER] Registered " + peer);
                    }
                    case TRACKER_LIST_PEERS -> {
                        if (peerId == null) {
                            sendError(socket, "Register first");
                            continue;
                        }
                        byte[] payload = PeerListCodec.encode(registry.listExcept(peerId));
                        FrameIO.write(socket.getOutputStream(), new Frame(
                                MessageType.TRACKER_PEER_LIST,
                                Map.of("count", Integer.toString(registry.listExcept(peerId).size())),
                                payload
                        ));
                    }
                    case TRACKER_DISCONNECT -> {
                        return;
                    }
                    default -> sendError(socket, "Unsupported tracker message: " + frame.type());
                }
            }
        } catch (EOFException ignored) {
            // Normal disconnect.
        } catch (Exception ex) {
            System.err.println("[TRACKER] Client error: " + ex.getMessage());
        } finally {
            registry.unregister(peerId);
            if (peerId != null) {
                System.out.println("[TRACKER] Unregistered " + peerId);
            }
        }
    }

    private static void sendError(Socket socket, String message) throws IOException {
        FrameIO.write(socket.getOutputStream(), new Frame(MessageType.ERROR, Map.of("message", message)));
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
        clients.shutdownNow();
    }
}
