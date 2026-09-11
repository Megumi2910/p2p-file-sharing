package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.peer.config.AppConfig;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SharedFolderMonitor {

    public interface Listener {
        void onScanStarted();
        void onScanCompleted(SharedFolderIndexer.ScanResult result);
        void onScanFailed(IOException failure);
        default void onModeChanged(boolean polling) {}
    }

    private volatile boolean isPolling = false;

    private final AppConfig config;
    private final Listener listener;
    private final SharedFolderIndexer indexer;

    private final ScheduledExecutorService scanExecutor;
    private final Object scheduleLock = new Object();
    private final Object rescanLock = new Object();

    private volatile boolean closed = false;
    private WatchService watchService;
    private WatchKey watchKey;
    private Thread watcherThread;
    private ScheduledFuture<?> scheduledReconcile;
    private ScheduledFuture<?> pollingTask;
    private long batchStartNanos = 0L;

    private CompletableFuture<SharedFolderIndexer.ScanResult> activeRescan;
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

    public SharedFolderMonitor(AppConfig config, Listener listener) {
        this(config, listener, new SharedFolderIndexer(config));
    }

    SharedFolderMonitor(AppConfig config, Listener listener, SharedFolderIndexer indexer) {
        this.config = Objects.requireNonNull(config, "config");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.indexer = Objects.requireNonNull(indexer, "indexer");
        this.scanExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shared-folder-scan-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public SharedFolderIndexer.ScanResult start() throws IOException {
        Path sharedDir = config.sharedDir().toAbsolutePath().normalize();
        Files.createDirectories(sharedDir);

        tryRegisterWatch(sharedDir);

        drainAndResetWatchKeys();

        // Initial reconciliation: pass 1 to record initial observations
        indexer.scan(false);

        // Pause 1 second for attribute stability check
        try {
            Thread.sleep(1050);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during initial folder stabilization", ex);
        }

        drainAndResetWatchKeys();
        SharedFolderIndexer.ScanResult result = indexer.scan(false);
        notifyScanCompleted(result);

        if (result.pendingFiles() > 0) {
            synchronized (scheduleLock) {
                if (!closed) {
                    scheduledReconcile = scanExecutor.schedule(() -> runReconciliation(false, null), 1, TimeUnit.SECONDS);
                }
            }
        }

        return result;
    }

    private synchronized void tryRegisterWatch(Path sharedDir) {
        if (closed) {
            return;
        }
        try {
            if (watchService == null) {
                watchService = FileSystems.getDefault().newWatchService();
            }
            if (Files.exists(sharedDir)) {
                watchKey = sharedDir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.OVERFLOW);

                if (watcherThread == null || !watcherThread.isAlive()) {
                    watcherThread = new Thread(this::runWatcher, "shared-folder-watcher");
                    watcherThread.setDaemon(true);
                    watcherThread.start();
                }

                // Stop polling if watching succeeded
                if (pollingTask != null) {
                    pollingTask.cancel(false);
                    pollingTask = null;
                }
                isPolling = false;
                listener.onModeChanged(false);
                return;
            }
        } catch (Exception ex) {
            System.err.println("[MONITOR] Watch registration failed, falling back to polling: " + ex.getMessage());
        }

        isPolling = true;
        listener.onModeChanged(true);
        startPolling();
    }

    private void runWatcher() {
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException ex) {
                break;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    indexer.markDirty(null);
                } else if (event.context() instanceof Path eventPath) {
                    indexer.markDirty(eventPath);
                }
                scheduleReconciliation();
            }

            boolean valid = key.reset();
            if (!valid) {
                System.err.println("[MONITOR] WatchKey invalid (directory missing or unmounted); switching to polling");
                startPolling();
                break;
            }
        }
    }

    private synchronized void startPolling() {
        if (closed || (pollingTask != null && !pollingTask.isDone())) {
            return;
        }
        pollingTask = scanExecutor.scheduleWithFixedDelay(this::pollReconciliation, 5, 5, TimeUnit.SECONDS);
    }

    private void pollReconciliation() {
        if (closed) {
            return;
        }
        Path sharedDir = config.sharedDir().toAbsolutePath().normalize();
        if (Files.exists(sharedDir) && (watchKey == null || !watchKey.isValid())) {
            tryRegisterWatch(sharedDir);
        }
        runReconciliation(false, null);
    }

    private void drainAndResetWatchKeys() {
        if (watchService != null) {
            WatchKey key;
            while ((key = watchService.poll()) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        indexer.markDirty(null);
                    } else if (event.context() instanceof Path p) {
                        indexer.markDirty(p);
                    }
                }
                key.reset();
            }
        }
    }

    private void scheduleReconciliation() {
        synchronized (scheduleLock) {
            if (closed) {
                return;
            }
            long now = System.nanoTime();
            if (batchStartNanos == 0L) {
                batchStartNanos = now;
            }
            long elapsed = now - batchStartNanos;
            long maxRemainingNanos = TimeUnit.SECONDS.toNanos(5) - elapsed;
            long quietNanos = TimeUnit.SECONDS.toNanos(1);
            long delayNanos = Math.min(quietNanos, Math.max(0L, maxRemainingNanos));

            if (scheduledReconcile != null && !scheduledReconcile.isDone()) {
                scheduledReconcile.cancel(false);
            }
            scheduledReconcile = scanExecutor.schedule(() -> runReconciliation(false, null), delayNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void runReconciliation(boolean forceRehash, CompletableFuture<SharedFolderIndexer.ScanResult> rescanFuture) {
        synchronized (scheduleLock) {
            batchStartNanos = 0L;
            scheduledReconcile = null;
        }
        if (closed) {
            if (rescanFuture != null) {
                rescanFuture.completeExceptionally(new IOException("SharedFolderMonitor is closed"));
            }
            return;
        }

        scanInProgress.set(true);
        notifyScanStarted();
        try {
            SharedFolderIndexer.ScanResult result = indexer.scan(forceRehash);
            notifyScanCompleted(result);
            if (rescanFuture != null) {
                rescanFuture.complete(result);
            }

            if (result.pendingFiles() > 0 && !closed) {
                synchronized (scheduleLock) {
                    if (scheduledReconcile == null) {
                        scheduledReconcile = scanExecutor.schedule(() -> runReconciliation(false, null), 1, TimeUnit.SECONDS);
                    }
                }
            }
        } catch (IOException ex) {
            notifyScanFailed(ex);
            if (rescanFuture != null) {
                rescanFuture.completeExceptionally(ex);
            }
            synchronized (scheduleLock) {
                if (!closed && scheduledReconcile == null) {
                    scheduledReconcile = scanExecutor.schedule(() -> runReconciliation(false, null), 5, TimeUnit.SECONDS);
                }
            }
        } catch (Throwable t) {
            IOException ioEx = new IOException("Unexpected scan error: " + t.getMessage(), t);
            notifyScanFailed(ioEx);
            if (rescanFuture != null) {
                rescanFuture.completeExceptionally(ioEx);
            }
        } finally {
            scanInProgress.set(false);
        }
    }

    public CompletableFuture<SharedFolderIndexer.ScanResult> rescan() {
        synchronized (rescanLock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IOException("SharedFolderMonitor is closed"));
            }
            if (activeRescan != null && !activeRescan.isDone()) {
                return activeRescan;
            }
            CompletableFuture<SharedFolderIndexer.ScanResult> future = new CompletableFuture<>();
            activeRescan = future;
            scanExecutor.execute(() -> runReconciliation(true, future));
            return future;
        }
    }

    private void notifyScanStarted() {
        try {
            listener.onScanStarted();
        } catch (Throwable t) {
            System.err.println("[MONITOR] Listener error onScanStarted: " + t.getMessage());
        }
    }

    private void notifyScanCompleted(SharedFolderIndexer.ScanResult result) {
        try {
            listener.onScanCompleted(result);
        } catch (Throwable t) {
            System.err.println("[MONITOR] Listener error onScanCompleted: " + t.getMessage());
        }
    }

    private void notifyScanFailed(IOException failure) {
        try {
            listener.onScanFailed(failure);
        } catch (Throwable t) {
            System.err.println("[MONITOR] Listener error onScanFailed: " + t.getMessage());
        }
    }

    public void shutdown() throws IOException {
        closed = true;
        synchronized (scheduleLock) {
            if (scheduledReconcile != null) {
                scheduledReconcile.cancel(false);
            }
            if (pollingTask != null) {
                pollingTask.cancel(false);
            }
        }
        synchronized (rescanLock) {
            if (activeRescan != null && !activeRescan.isDone()) {
                activeRescan.completeExceptionally(new IOException("SharedFolderMonitor is closed"));
            }
        }
        if (watchKey != null) {
            watchKey.cancel();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {}
        }
        scanExecutor.shutdownNow();
    }

    public boolean isPolling() {
        return isPolling;
    }

    public void awaitTermination(long deadlineNanos) throws IOException, InterruptedException {
        long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
        if (!scanExecutor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
            scanExecutor.shutdownNow();
        }
        if (watcherThread != null) {
            long remainingMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            watcherThread.join(remainingMs);
        }
    }
}
