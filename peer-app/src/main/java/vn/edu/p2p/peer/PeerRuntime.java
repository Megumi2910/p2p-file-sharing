package vn.edu.p2p.peer;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.network.PeerServer;
import vn.edu.p2p.peer.network.TrackerClient;
import vn.edu.p2p.peer.transfer.TransferManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class PeerRuntime implements AutoCloseable {
    private final AppConfig config;
    private final TrackerClient trackerClient;
    private final TransferManager transferManager;
    private final PeerServer peerServer;
    private String trackerObservedHost;

    public PeerRuntime(AppConfig config) {
        this.config = config;
        this.trackerClient = new TrackerClient(config);
        this.transferManager = new TransferManager(config);
        this.peerServer = new PeerServer(config.peerPort(), transferManager);
    }

    public void start() throws IOException {
        peerServer.start();
        trackerObservedHost = trackerClient.connectAndRegister();
        System.out.println("[PEER] Tracker sees this peer as " + trackerObservedHost + ":" + config.peerPort());
    }

    public List<PeerInfo> listPeers() throws IOException {
        return trackerClient.listPeers();
    }

    public void sendFile(PeerInfo target, Path file) {
        transferManager.sendFile(target, file);
    }

    public AppConfig config() {
        return config;
    }

    public TransferManager transferManager() {
        return transferManager;
    }

    public String trackerObservedHost() {
        return trackerObservedHost;
    }

    @Override
    public void close() throws Exception {
        trackerClient.close();
        peerServer.close();
        transferManager.close();
    }
}
