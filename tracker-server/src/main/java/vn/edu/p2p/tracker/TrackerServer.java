package vn.edu.p2p.tracker;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.PeerListCodec;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.TrackerProtocol;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TrackerServer implements AutoCloseable {
    private final int port;
    private final PeerRegistry registry = new PeerRegistry();
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private final ThreadPoolExecutor clients;
    private volatile boolean running;
    private volatile ServerSocket serverSocket;

    public TrackerServer(int port) {
        this.port = port;
        this.clients = new ThreadPoolExecutor(
                0,
                TrackerProtocol.MAX_ACTIVE_PEERS,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "tracker-worker-" + counter.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                }
        );
    }

    public void start() throws IOException {
        synchronized (lifecycleLock) {
            serverSocket = new ServerSocket(port);
            running = true;
        }
        System.out.println("[TRACKER] Listening on 0.0.0.0:" + serverSocket.getLocalPort());

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                boolean admitted = false;
                synchronized (lifecycleLock) {
                    if (running) {
                        activeSockets.add(socket);
                        admitted = true;
                    }
                }
                if (!admitted) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                    continue;
                }

                try {
                    clients.submit(() -> {
                        try {
                            handleClient(socket);
                        } finally {
                            synchronized (lifecycleLock) {
                                activeSockets.remove(socket);
                            }
                        }
                    });
                } catch (RejectedExecutionException ex) {
                    synchronized (lifecycleLock) {
                        activeSockets.remove(socket);
                    }
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                }
            } catch (IOException ex) {
                if (running) {
                    System.err.println("[TRACKER] Accept failed: " + ex.getMessage());
                }
            }
        }
    }

    public int localPort() {
        ServerSocket ss = this.serverSocket;
        if (ss == null || !ss.isBound()) {
            throw new IllegalStateException("Tracker server socket is not bound");
        }
        return ss.getLocalPort();
    }

    private void handleClient(Socket socket) {
        PeerInfo ownedPeer = null;
        try (socket) {
            System.out.println("[TRACKER] Connection from " + socket.getRemoteSocketAddress());
            socket.setSoTimeout(15_000); // 15s timeout until registration

            while (true) {
                Frame frame = FrameIO.read(socket.getInputStream(), 0);

                switch (frame.type()) {
                    case TRACKER_REGISTER -> {
                        if (ownedPeer != null) {
                            sendError(socket, "Already registered");
                            return; // Session terminates and releases original registration
                        }
                        String peerId = frame.requireHeader("peerId");
                        String displayName = frame.requireHeader("displayName");
                        int peerPort = Integer.parseInt(frame.requireHeader("peerPort"));

                        String host = socket.getInetAddress().getHostAddress();
                        PeerInfo peer = new PeerInfo(peerId, displayName, host, peerPort);
                        boolean registered = registry.register(peer);
                        if (!registered) {
                            sendError(socket, "Peer ID already registered");
                            return; // Closes rejected session without unregistering existing peer
                        }

                        ownedPeer = peer;
                        socket.setSoTimeout(0); // Remove timeout for registered peer

                        FrameIO.write(socket.getOutputStream(), new Frame(
                                MessageType.TRACKER_REGISTER_OK,
                                Map.of("host", host, "peerCount", Integer.toString(registry.size()))
                        ));
                        System.out.println("[TRACKER] Registered " + peer);
                    }
                    case TRACKER_LIST_PEERS -> {
                        if (ownedPeer == null) {
                            sendError(socket, "Register first");
                            continue;
                        }
                        var snapshot = registry.listExcept(ownedPeer.peerId());
                        byte[] payload = PeerListCodec.encode(snapshot);
                        FrameIO.write(socket.getOutputStream(), new Frame(
                                MessageType.TRACKER_PEER_LIST,
                                Map.of("count", Integer.toString(snapshot.size())),
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
            // Normal disconnect
        } catch (Exception ex) {
            System.err.println("[TRACKER] Client error: " + ex.getMessage());
        } finally {
            if (ownedPeer != null) {
                registry.unregister(ownedPeer);
                System.out.println("[TRACKER] Unregistered " + ownedPeer);
            }
        }
    }

    private static void sendError(Socket socket, String message) throws IOException {
        FrameIO.write(socket.getOutputStream(), new Frame(MessageType.ERROR, Map.of("message", message)));
    }

    @Override
    public void close() throws IOException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        synchronized (lifecycleLock) {
            running = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {}
            }

            for (Socket s : activeSockets) {
                try {
                    s.close();
                } catch (IOException ignored) {}
            }
            activeSockets.clear();
        }

        clients.shutdownNow();

        long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
        try {
            if (!clients.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                System.err.println("[TRACKER] Warning: clients executor did not terminate within 5s deadline");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during tracker shutdown", ex);
        }
    }
}
