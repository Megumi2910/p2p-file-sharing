package vn.edu.p2p.peer;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.network.PeerServer;
import vn.edu.p2p.peer.network.TrackerClient;
import vn.edu.p2p.peer.transfer.TransferManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class PeerRuntime implements AutoCloseable {
    private final AppConfig config;
    private final TrackerClient trackerClient;
    private final TransferManager transferManager;
    private final PeerServer peerServer;
    private final Object stateLock = new Object();
    private final List<vn.edu.p2p.common.model.FileRecord> sharedFiles = new java.util.concurrent.CopyOnWriteArrayList<>();
    private String trackerObservedHost;
    private volatile boolean started = false;
    private volatile boolean closed = false;

    public PeerRuntime(AppConfig config) {
        this.config = config;
        this.trackerClient = new TrackerClient(config);
        this.transferManager = new TransferManager(config);
        this.peerServer = new PeerServer(config.peerPort(), transferManager);
    }

    public void start() throws Exception {
        synchronized (stateLock) {
            if (closed) {
                throw new IllegalStateException("PeerRuntime is closed");
            }
            if (started) {
                return;
            }
        }

        Path downloadDir = config.downloadDir();
        Files.createDirectories(downloadDir);
        if (!Files.isDirectory(downloadDir)) {
            throw new IOException("Download path is not a directory: " + downloadDir);
        }
        if (!Files.isWritable(downloadDir)) {
            throw new IOException("Download directory is not writable: " + downloadDir);
        }
        Path sharedDir = config.sharedDir();
        Files.createDirectories(sharedDir);

        try {
            peerServer.start();
            if (closed) {
                throw new IOException("PeerRuntime closed during startup");
            }
            String host = trackerClient.connectAndRegister();
            synchronized (stateLock) {
                if (closed) {
                    throw new IOException("PeerRuntime closed during startup");
                }
                trackerObservedHost = host;
                started = true;
            }
            // Index and publish shared folder to tracker
            List<vn.edu.p2p.common.model.FileRecord> indexed = vn.edu.p2p.peer.transfer.SharedFolderIndexer.index(config);
            sharedFiles.clear();
            sharedFiles.addAll(indexed);
            trackerClient.publishSharedFiles(indexed);
            System.out.println("[PEER] Tracker sees this peer as " + host + ":" + config.peerPort());
        } catch (Exception ex) {
            try {
                close();
            } catch (Exception rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            throw ex;
        }
    }

    public List<vn.edu.p2p.common.model.FileRecord> sharedFiles() {
        return List.copyOf(sharedFiles);
    }

    public List<vn.edu.p2p.common.model.SearchResult> searchFiles(String query) throws IOException {
        return trackerClient.searchFiles(query);
    }

    public void refreshSharedFiles() throws IOException {
        List<vn.edu.p2p.common.model.FileRecord> indexed = vn.edu.p2p.peer.transfer.SharedFolderIndexer.index(config);
        sharedFiles.clear();
        sharedFiles.addAll(indexed);
        trackerClient.publishSharedFiles(indexed);
    }
    public List<PeerInfo> listPeers() throws IOException {
        return trackerClient.listPeers();
    }

    public void sendFile(PeerInfo target, Path file) {
        transferManager.sendFile(target, file);
    }
    public void downloadFile(vn.edu.p2p.common.model.SearchResult result) {
        if (result == null || result.providers().isEmpty()) {
            throw new IllegalArgumentException("No live providers available for file: " + (result != null ? result.file().fileName() : "null"));
        }
        transferManager.downloadMultiSource(result.providers(), result.file());
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
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Throwable> failures = new ArrayList<>();

        // Phase 1: close admission and active sockets immediately
        try {
            peerServer.shutdown();
        } catch (Throwable t) {
            failures.add(t);
        }
        try {
            trackerClient.close();
        } catch (Throwable t) {
            failures.add(t);
        }
        try {
            transferManager.shutdown();
        } catch (Throwable t) {
            failures.add(t);
        }

        // Phase 2: await termination sharing the single monotonic deadline
        try {
            peerServer.awaitTermination(deadlineNanos);
        } catch (Throwable t) {
            failures.add(t);
        }
        try {
            transferManager.awaitTermination(deadlineNanos);
        } catch (Throwable t) {
            failures.add(t);
        }

        if (!failures.isEmpty()) {
            Exception composite = new Exception("Failures during PeerRuntime close");
            failures.forEach(composite::addSuppressed);
            throw composite;
        }
    }
}
