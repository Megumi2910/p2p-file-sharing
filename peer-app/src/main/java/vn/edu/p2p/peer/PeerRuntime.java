package vn.edu.p2p.peer;

import vn.edu.p2p.common.model.CatalogueCodec;
import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.SearchResult;
import vn.edu.p2p.common.protocol.TrackerProtocol;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.network.PeerServer;
import vn.edu.p2p.peer.network.TrackerClient;
import vn.edu.p2p.peer.transfer.SharedFolderIndexer;
import vn.edu.p2p.peer.transfer.SharedFolderMonitor;
import vn.edu.p2p.peer.transfer.TransferManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class PeerRuntime implements AutoCloseable {

    public enum TrackerState { CONNECTED, RECONNECTING, OFFLINE, CLOSED }

    public enum SharingState { SCANNING, SYNCED, PENDING_PUBLISH, ERROR }

    public record RuntimeSnapshot(
            long revision,
            TrackerState trackerState,
            List<PeerInfo> peers,
            Instant peersUpdatedAt,
            SharingState sharingState,
            int sharedFileCount,
            int pendingFileCount,
            String trackerDetail,
            String sharingDetail
    ) {
        public RuntimeSnapshot {
            peers = peers == null ? List.of() : List.copyOf(peers);
            trackerDetail = trackerDetail == null ? "" : trackerDetail;
            sharingDetail = sharingDetail == null ? "" : sharingDetail;
        }
    }

    private final AppConfig config;
    private final TrackerClient trackerClient;
    private final TransferManager transferManager;
    private final PeerServer peerServer;
    private final SharedFolderMonitor monitor;
    private final ScheduledExecutorService controlExecutor;
    private final AtomicReference<Thread> controlThreadRef = new AtomicReference<>();

    private final Object stateLock = new Object();
    private final AtomicReference<List<FileRecord>> sharedFiles = new AtomicReference<>(List.of());
    private final CopyOnWriteArrayList<Consumer<RuntimeSnapshot>> stateListeners = new CopyOnWriteArrayList<>();

    private volatile boolean started = false;
    private volatile boolean closed = false;
    private String trackerObservedHost;

    private volatile long snapshotRevision = 1L;
    private volatile TrackerState trackerState = TrackerState.OFFLINE;
    private volatile List<PeerInfo> cachedPeers = List.of();
    private volatile Instant peersUpdatedAt = null;
    private volatile SharingState sharingState = SharingState.SCANNING;
    private volatile int pendingFileCount = 0;
    private volatile String trackerDetail = "Initializing...";
    private volatile String sharingDetail = "Initializing shared folder...";
    private volatile String localScanError = null;
    private volatile String publishError = null;
    private volatile boolean isScanning = false;
    private volatile boolean isPolling = false;
    private volatile RuntimeSnapshot currentSnapshot;
    private final java.util.Set<Future<?>> activeControlFutures = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final int[] BACKOFF_DELAYS_SEC = {1, 2, 4, 8, 15};
    private int backoffIndex = 0;
    private ScheduledFuture<?> recoveryTask;
    private ScheduledFuture<?> periodicDiscoveryTask;
    private boolean recoveryActive = false;

    private volatile long localCatalogueRevision = 0L;
    private volatile long lastAcknowledgedRevision = -1L;
    private boolean publishQueued = false;

    public PeerRuntime(AppConfig config) {
        this.config = config;
        this.trackerClient = new TrackerClient(config);
        this.transferManager = new TransferManager(config);
        this.peerServer = new PeerServer(config.peerPort(), transferManager);
        this.controlExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "peer-control-worker");
            t.setDaemon(true);
            return t;
        });
        this.controlExecutor.execute(() -> controlThreadRef.set(Thread.currentThread()));

        this.monitor = new SharedFolderMonitor(config, new SharedFolderMonitor.Listener() {
            @Override
            public void onScanStarted() {
                handleScanStarted();
            }

            @Override
            public void onScanCompleted(SharedFolderIndexer.ScanResult result) {
                handleScanCompleted(result);
            }

            @Override
            public void onScanFailed(IOException failure) {
                handleScanFailed(failure);
            }

            @Override
            public void onModeChanged(boolean polling) {
                isPolling = polling;
                updateSharingStateAndSnapshot();
            }
        });
        updateSnapshot();
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
            }

            // Perform initial local folder reconciliation
            SharedFolderIndexer.ScanResult initialScan = monitor.start();
            sharedFiles.set(initialScan.files());
            localCatalogueRevision = 1L;
            pendingFileCount = initialScan.pendingFiles();

            // Publish initial catalogue to tracker
            List<FileRecord> initialFiles = initialScan.files();
            if (initialFiles.size() <= CatalogueCodec.MAX_CATALOGUE_ITEMS) {
                byte[] payload = CatalogueCodec.encodeFiles(initialFiles);
                if (payload.length <= TrackerProtocol.MAX_PEER_LIST_PAYLOAD_BYTES) {
                    trackerClient.publishSharedFiles(initialFiles);
                    lastAcknowledgedRevision = 1L;
                } else {
                    localScanError = "Catalogue payload exceeds maximum limit (" + payload.length + " bytes)";
                }
            } else {
                localScanError = "Catalogue item count exceeds maximum limit (" + initialFiles.size() + " items)";
            }

            // Perform initial peer list
            List<PeerInfo> peers = trackerClient.listPeers();
            cachedPeers = List.copyOf(peers);
            peersUpdatedAt = Instant.now();

            trackerState = TrackerState.CONNECTED;
            trackerDetail = "Connected to tracker";
            synchronized (stateLock) {
                started = true;
            }
            updateSharingStateAndSnapshot();
            if (localCatalogueRevision > lastAcknowledgedRevision) {
                queuePublication();
            }

            // Start periodic discovery (fixed delay 5 seconds)
            periodicDiscoveryTask = controlExecutor.scheduleWithFixedDelay(this::periodicDiscovery, 5, 5, TimeUnit.SECONDS);
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

    private void handleScanStarted() {
        isScanning = true;
        updateSharingStateAndSnapshot();
    }

    private void handleScanCompleted(SharedFolderIndexer.ScanResult result) {
        isScanning = false;
        pendingFileCount = result.pendingFiles();
        localScanError = result.warnings().isEmpty() ? null : String.join("; ", result.warnings());

        List<FileRecord> oldFiles = sharedFiles.get();
        List<FileRecord> newFiles = result.files();
        boolean metadataChanged = !oldFiles.equals(newFiles);

        if (metadataChanged) {
            sharedFiles.set(newFiles);
            localCatalogueRevision++;
        }

        updateSharingStateAndSnapshot();

        if (metadataChanged || localCatalogueRevision > lastAcknowledgedRevision) {
            queuePublication();
        }
    }

    private void handleScanFailed(IOException failure) {
        isScanning = false;
        localScanError = failure.getMessage();
        updateSharingStateAndSnapshot();
    }

    private synchronized void queuePublication() {
        if (closed || publishQueued) {
            return;
        }
        publishQueued = true;
        controlExecutor.execute(this::executePublication);
    }

    private void executePublication() {
        synchronized (this) {
            publishQueued = false;
        }
        if (closed) {
            return;
        }
        if (trackerState != TrackerState.CONNECTED) {
            updateSharingStateAndSnapshot();
            return;
        }

        long revToSend = localCatalogueRevision;
        List<FileRecord> filesToSend = sharedFiles.get();

        if (filesToSend.size() > CatalogueCodec.MAX_CATALOGUE_ITEMS) {
            publishError = "Catalogue item count (" + filesToSend.size() + ") exceeds maximum " + CatalogueCodec.MAX_CATALOGUE_ITEMS;
            updateSharingStateAndSnapshot();
            return;
        }
        byte[] payload;
        try {
            payload = CatalogueCodec.encodeFiles(filesToSend);
        } catch (IOException ex) {
            publishError = "Failed to encode catalogue: " + ex.getMessage();
            updateSharingStateAndSnapshot();
            return;
        }
        if (payload.length > TrackerProtocol.MAX_PEER_LIST_PAYLOAD_BYTES) {
            publishError = "Catalogue payload (" + payload.length + " bytes) exceeds maximum " + TrackerProtocol.MAX_PEER_LIST_PAYLOAD_BYTES + " bytes";
            updateSharingStateAndSnapshot();
            return;
        }

        try {
            trackerClient.publishSharedFiles(filesToSend);
            lastAcknowledgedRevision = revToSend;
            publishError = null;
            updateSharingStateAndSnapshot();
        } catch (IOException ex) {
            handleTrackerOutage(ex);
        }
    }

    private void periodicDiscovery() {
        if (closed || trackerState != TrackerState.CONNECTED) {
            return;
        }
        try {
            List<PeerInfo> live = trackerClient.listPeers();
            cachedPeers = List.copyOf(live);
            peersUpdatedAt = Instant.now();
            updateSnapshot();
        } catch (IOException ex) {
            handleTrackerOutage(ex);
        }
    }

    private void handleTrackerOutage(IOException failure) {
        if (closed) {
            return;
        }
        if (trackerState == TrackerState.CONNECTED || trackerState == TrackerState.RECONNECTING) {
            trackerState = TrackerState.OFFLINE;
            trackerDetail = "Tracker unavailable: " + failure.getMessage();
            lastAcknowledgedRevision = -1L;
            updateSharingStateAndSnapshot();
            scheduleRecovery();
        }
    }

    private synchronized void scheduleRecovery() {
        if (closed || recoveryActive || trackerState == TrackerState.CONNECTED) {
            return;
        }
        recoveryActive = true;
        int delaySec = BACKOFF_DELAYS_SEC[Math.min(backoffIndex, BACKOFF_DELAYS_SEC.length - 1)];
        if (recoveryTask != null && !recoveryTask.isDone()) {
            recoveryTask.cancel(false);
        }
        recoveryTask = controlExecutor.schedule(this::attemptRecovery, delaySec, TimeUnit.SECONDS);
    }

    private void attemptRecovery() {
        synchronized (this) {
            recoveryActive = false;
        }
        if (closed || trackerState == TrackerState.CONNECTED) {
            return;
        }

        trackerState = TrackerState.RECONNECTING;
        trackerDetail = "Reconnecting to tracker...";
        updateSnapshot();

        try {
            String host = trackerClient.connectAndRegister();
            if (closed) return;
            synchronized (stateLock) {
                trackerObservedHost = host;
            }

            lastAcknowledgedRevision = -1L;

            // Preflight & publish current snapshot
            List<FileRecord> currentFiles = sharedFiles.get();
            long rev = localCatalogueRevision;
            boolean canPublish = true;

            if (currentFiles.size() > CatalogueCodec.MAX_CATALOGUE_ITEMS) {
                publishError = "Catalogue item count (" + currentFiles.size() + ") exceeds maximum " + CatalogueCodec.MAX_CATALOGUE_ITEMS;
                canPublish = false;
            } else {
                byte[] payload = CatalogueCodec.encodeFiles(currentFiles);
                if (payload.length > TrackerProtocol.MAX_PEER_LIST_PAYLOAD_BYTES) {
                    publishError = "Catalogue payload (" + payload.length + " bytes) exceeds maximum " + TrackerProtocol.MAX_PEER_LIST_PAYLOAD_BYTES + " bytes";
                    canPublish = false;
                }
            }

            if (canPublish) {
                trackerClient.publishSharedFiles(currentFiles);
                lastAcknowledgedRevision = rev;
                publishError = null;
            }

            if (closed) return;
            List<PeerInfo> peers = trackerClient.listPeers();
            if (closed) return;

            cachedPeers = List.copyOf(peers);
            peersUpdatedAt = Instant.now();

            trackerState = TrackerState.CONNECTED;
            trackerDetail = "Connected to tracker";
            backoffIndex = 0;
            updateSharingStateAndSnapshot();
        } catch (Exception ex) {
            if (closed) return;
            trackerState = TrackerState.OFFLINE;
            trackerDetail = "Tracker reconnection failed: " + ex.getMessage();
            backoffIndex++;
            updateSharingStateAndSnapshot();
            scheduleRecovery();
        }
    }

    public void requestImmediateRecovery() {
        controlExecutor.execute(() -> {
            if (closed || trackerState == TrackerState.CONNECTED) {
                return;
            }
            synchronized (this) {
                if (recoveryTask != null && !recoveryTask.isDone()) {
                    recoveryTask.cancel(false);
                }
                recoveryActive = false;
            }
            attemptRecovery();
        });
    }

    private void updateSharingStateAndSnapshot() {
        SharingState state;
        String detail;

        if (localScanError != null) {
            state = SharingState.ERROR;
            detail = localScanError;
        } else if (publishError != null) {
            state = SharingState.ERROR;
            detail = publishError;
        } else if (isScanning || pendingFileCount > 0) {
            state = SharingState.SCANNING;
            detail = pendingFileCount > 0
                    ? "Scanning shared folder (" + pendingFileCount + " pending)..."
                    : "Scanning shared folder...";
        } else if (localCatalogueRevision > lastAcknowledgedRevision) {
            state = SharingState.PENDING_PUBLISH;
            detail = trackerState == TrackerState.CONNECTED
                    ? "Publishing catalogue to tracker..."
                    : "Local index updated — waiting for tracker";
        } else {
            state = SharingState.SYNCED;
            detail = "Shared files up to date (" + sharedFiles.get().size() + ")" + (isPolling ? " (monitoring via polling)" : "");
        }
        this.sharingState = state;
        this.sharingDetail = detail;
        updateSnapshot();
    }

    private void updateSnapshot() {
        RuntimeSnapshot snap;
        synchronized (this) {
            long rev = snapshotRevision++;
            snap = new RuntimeSnapshot(
                    rev,
                    trackerState,
                    cachedPeers,
                    peersUpdatedAt,
                    sharingState,
                    sharedFiles.get().size(),
                    pendingFileCount,
                    trackerDetail,
                    sharingDetail
            );
            this.currentSnapshot = snap;
        }
        for (Consumer<RuntimeSnapshot> listener : stateListeners) {
            try {
                listener.accept(snap);
            } catch (Throwable t) {
                System.err.println("[RUNTIME] Error in state listener: " + t.getMessage());
            }
        }
    }

    public RuntimeSnapshot snapshot() {
        RuntimeSnapshot snap = this.currentSnapshot;
        if (snap != null) {
            return snap;
        }
        return new RuntimeSnapshot(
                snapshotRevision,
                trackerState,
                cachedPeers,
                peersUpdatedAt,
                sharingState,
                sharedFiles.get().size(),
                pendingFileCount,
                trackerDetail,
                sharingDetail
        );
    }

    public void addStateListener(Consumer<RuntimeSnapshot> listener) {
        if (listener != null) {
            stateListeners.add(listener);
            listener.accept(snapshot());
        }
    }

    public void removeStateListener(Consumer<RuntimeSnapshot> listener) {
        if (listener != null) {
            stateListeners.remove(listener);
        }
    }

    public List<FileRecord> sharedFiles() {
        return sharedFiles.get();
    }

    public List<SearchResult> searchFiles(String query) throws IOException {
        if (closed) {
            throw new IOException("PeerRuntime is closed");
        }
        if (trackerState != TrackerState.CONNECTED) {
            throw new IOException("Tracker is unavailable (currently " + trackerState + ")");
        }
        return runOnControlThread(() -> {
            try {
                return trackerClient.searchFiles(query);
            } catch (IOException ex) {
                handleTrackerOutage(ex);
                throw ex;
            }
        });
    }

    public void refreshSharedFiles() throws IOException {
        if (closed) {
            throw new IOException("PeerRuntime is closed");
        }
        CompletableFuture<SharedFolderIndexer.ScanResult> future = monitor.rescan();
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Rescan interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("Rescan failed: " + (cause != null ? cause.getMessage() : ex.getMessage()), cause);
        } catch (TimeoutException ex) {
            throw new IOException("Rescan timed out", ex);
        }
    }

    public List<PeerInfo> listPeers() throws IOException {
        if (closed) {
            throw new IOException("PeerRuntime is closed");
        }
        return runOnControlThread(() -> {
            try {
                List<PeerInfo> live = trackerClient.listPeers();
                cachedPeers = List.copyOf(live);
                peersUpdatedAt = Instant.now();
                updateSnapshot();
                return live;
            } catch (IOException ex) {
                handleTrackerOutage(ex);
                throw ex;
            }
        });
    }

    public void sendFile(PeerInfo target, Path file) {
        transferManager.sendFile(target, file);
    }

    public void downloadFile(SearchResult result) {
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

    public SharedFolderMonitor monitor() {
        return monitor;
    }

    private <T> T runOnControlThread(Callable<T> task) throws IOException {
        if (closed) {
            throw new IOException("PeerRuntime is closed");
        }
        if (Thread.currentThread() == controlThreadRef.get()) {
            try {
                return task.call();
            } catch (IOException ioEx) {
                throw ioEx;
            } catch (Exception ex) {
                throw new IOException(ex.getMessage(), ex);
            }
        }
        Future<T> future = controlExecutor.submit(task);
        activeControlFutures.add(future);
        try {
            return future.get();
        } catch (java.util.concurrent.CancellationException ex) {
            throw new IOException("Operation cancelled: runtime closed", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException(cause != null ? cause.getMessage() : ex.getMessage(), cause);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Operation interrupted", ex);
        } finally {
            activeControlFutures.remove(future);
        }
    }

    @Override
    public void close() throws Exception {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        trackerState = TrackerState.CLOSED;
        trackerDetail = "Peer closed";
        updateSnapshot();

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Throwable> failures = new ArrayList<>();

        if (recoveryTask != null) {
            recoveryTask.cancel(true);
        }
        if (periodicDiscoveryTask != null) {
            periodicDiscoveryTask.cancel(true);
        }
        for (Future<?> f : activeControlFutures) {
            f.cancel(true);
        }
        activeControlFutures.clear();
        // Phase 1: close admission, sockets, monitor and cancel tasks immediately
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
            monitor.shutdown();
        } catch (Throwable t) {
            failures.add(t);
        }
        try {
            controlExecutor.shutdownNow();
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
            monitor.awaitTermination(deadlineNanos);
        } catch (Throwable t) {
            failures.add(t);
        }
        try {
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            controlExecutor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
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
