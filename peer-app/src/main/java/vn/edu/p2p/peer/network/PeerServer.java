package vn.edu.p2p.peer.network;

import vn.edu.p2p.peer.transfer.TransferManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public final class PeerServer implements AutoCloseable {
    private final int port;
    private final TransferManager transferManager;
    private volatile boolean running;
    private ServerSocket serverSocket;
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
        System.out.println("[PEER] Listening for P2P transfers on 0.0.0.0:" + port);
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

    @Override
    public void close() throws IOException {
        running = false;
        if (serverSocket != null) {
            serverSocket.close();
        }
    }
}
