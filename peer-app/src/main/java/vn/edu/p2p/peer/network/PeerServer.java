package vn.edu.p2p.peer.network;

import vn.edu.p2p.peer.transfer.TransferManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

public final class PeerServer implements AutoCloseable {
    private final int port;
    private final TransferManager transferManager;
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;

    public PeerServer(int port, TransferManager transferManager) {
        this.port = port;
        this.transferManager = transferManager;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "peer-acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("[PEER] Listening for P2P transfers on 0.0.0.0:" + serverSocket.getLocalPort());
    }

    public int localPort() {
        ServerSocket ss = this.serverSocket;
        if (ss == null || !ss.isBound()) {
            throw new IllegalStateException("Peer server socket is not bound");
        }
        return ss.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                transferManager.handleIncoming(socket);
            } catch (IOException ex) {
                if (running) {
                    System.err.println("[PEER] Accept failed: " + ex.getMessage());
                }
            }
        }
    }

    public void shutdown() throws IOException {
        running = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    public void awaitTermination(long deadlineNanos) throws IOException, InterruptedException {
        if (acceptThread != null && acceptThread.isAlive()) {
            long remainingMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            if (remainingMillis > 0) {
                acceptThread.join(remainingMillis);
            }
        }
    }

    @Override
    public void close() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        shutdown();
        try {
            awaitTermination(deadline);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during PeerServer shutdown", ex);
        }
    }
}
