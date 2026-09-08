package vn.edu.p2p.peer.update;

import vn.edu.p2p.peer.PeerApplication;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class UpdateService implements AutoCloseable {

    public enum UpdateState {
        NOT_CHECKED,
        CHECKING,
        UP_TO_DATE,
        NO_RELEASE,
        UPDATE_AVAILABLE,
        DOWNLOADING,
        VERIFYING,
        READY_TO_RESTART,
        RESTARTING,
        UNSUPPORTED,
        FAILED
    }

    public record UpdateSnapshot(
            UpdateState state,
            ClientVersion installedVersion,
            ClientVersion availableVersion,
            long bytesDownloaded,
            long totalBytes,
            String detail,
            String releaseNotes,
            ReleaseClient.ValidatedCandidate candidate,
            Path downloadedCandidateJar
    ) {}

    private final ReleaseClient client;
    private final Path installRoot;
    private final Path updateDir;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final List<Consumer<UpdateSnapshot>> listeners = new CopyOnWriteArrayList<>();

    private final Object stateLock = new Object();
    private UpdateSnapshot currentSnapshot;
    private AtomicBoolean downloadCancelled;
    private volatile boolean closed = false;

    public UpdateService(ReleaseClient client) {
        this(client, resolveInstallRoot(), null);
    }

    public UpdateService(ReleaseClient client, Path installRoot) {
        this(client, installRoot, null);
    }

    UpdateService(ReleaseClient client, Path installRoot, ExecutorService executor) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.installRoot = Objects.requireNonNull(installRoot, "installRoot cannot be null");
        this.updateDir = installRoot.resolve(".p2p-update");

        if (executor != null) {
            this.executor = executor;
            this.ownsExecutor = false;
        } else {
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "p2p-update-worker");
                t.setDaemon(true);
                return t;
            });
            this.ownsExecutor = true;
        }

        ClientVersion installed = client.buildInfo().version();
        this.currentSnapshot = new UpdateSnapshot(
                UpdateState.NOT_CHECKED,
                installed,
                null,
                0,
                0,
                "Updates not checked yet",
                "",
                null,
                null
        );
    }

    public Path installRoot() {
        return installRoot;
    }

    public Path updateDir() {
        return updateDir;
    }

    public ReleaseClient releaseClient() {
        return client;
    }

    public UpdateSnapshot snapshot() {
        synchronized (stateLock) {
            return currentSnapshot;
        }
    }

    public void addListener(Consumer<UpdateSnapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener cannot be null"));
        listener.accept(snapshot());
    }

    public void removeListener(Consumer<UpdateSnapshot> listener) {
        listeners.remove(listener);
    }

    private void updateSnapshot(UpdateSnapshot newSnapshot) {
        synchronized (stateLock) {
            this.currentSnapshot = newSnapshot;
        }
        notifyListeners(newSnapshot);
    }

    private void notifyListeners(UpdateSnapshot snapshot) {
        Runnable notifyTask = () -> {
            for (Consumer<UpdateSnapshot> l : listeners) {
                try {
                    l.accept(snapshot);
                } catch (Exception ignored) {
                }
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            notifyTask.run();
        } else {
            try {
                SwingUtilities.invokeLater(notifyTask);
            } catch (Exception ex) {
                notifyTask.run();
            }
        }
    }

    public void checkForUpdates() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            if (currentSnapshot.state() == UpdateState.CHECKING || currentSnapshot.state() == UpdateState.DOWNLOADING) {
                return;
            }
            updateSnapshot(new UpdateSnapshot(
                    UpdateState.CHECKING,
                    client.buildInfo().version(),
                    null,
                    0,
                    0,
                    "Checking for updates...",
                    "",
                    null,
                    null
            ));
        }

        executor.submit(() -> {
            try {
                ReleaseClient.CheckResult result = client.check();
                handleCheckResult(result);
            } catch (Exception ex) {
                synchronized (stateLock) {
                    updateSnapshot(new UpdateSnapshot(
                            UpdateState.FAILED,
                            client.buildInfo().version(),
                            null,
                            0,
                            0,
                            "Check failed: " + ex.getMessage(),
                            "",
                            null,
                            null
                    ));
                }
            }
        });
    }

    private void handleCheckResult(ReleaseClient.CheckResult result) {
        synchronized (stateLock) {
            if (closed) return;
            switch (result.status()) {
                case AVAILABLE -> updateSnapshot(new UpdateSnapshot(
                        UpdateState.UPDATE_AVAILABLE,
                        client.buildInfo().version(),
                        result.latestVersion(),
                        0,
                        result.candidate().jarSize(),
                        result.message(),
                        result.releaseNotes(),
                        result.candidate(),
                        null
                ));
                case UP_TO_DATE -> updateSnapshot(new UpdateSnapshot(
                        UpdateState.UP_TO_DATE,
                        client.buildInfo().version(),
                        result.latestVersion(),
                        0,
                        0,
                        result.message(),
                        result.releaseNotes(),
                        null,
                        null
                ));
                case NO_RELEASE -> updateSnapshot(new UpdateSnapshot(
                        UpdateState.NO_RELEASE,
                        client.buildInfo().version(),
                        null,
                        0,
                        0,
                        result.message(),
                        "",
                        null,
                        null
                ));
                case UNAVAILABLE -> updateSnapshot(new UpdateSnapshot(
                        UpdateState.FAILED,
                        client.buildInfo().version(),
                        null,
                        0,
                        0,
                        result.message(),
                        "",
                        null,
                        null
                ));
                case INVALID_RELEASE -> updateSnapshot(new UpdateSnapshot(
                        UpdateState.FAILED,
                        client.buildInfo().version(),
                        result.latestVersion(),
                        0,
                        0,
                        "Invalid release: " + result.message(),
                        result.releaseNotes(),
                        null,
                        null
                ));
                case UNSUPPORTED -> updateSnapshot(new UpdateSnapshot(
                        UpdateState.UNSUPPORTED,
                        client.buildInfo().version(),
                        result.latestVersion(),
                        0,
                        0,
                        result.message(),
                        result.releaseNotes(),
                        null,
                        null
                ));
            }
        }
    }

    public void downloadAvailableUpdate() {
        ReleaseClient.ValidatedCandidate candidate;
        synchronized (stateLock) {
            if (closed) return;
            if (currentSnapshot.state() != UpdateState.UPDATE_AVAILABLE || currentSnapshot.candidate() == null) {
                return;
            }
            candidate = currentSnapshot.candidate();
            downloadCancelled = new AtomicBoolean(false);
            updateSnapshot(new UpdateSnapshot(
                    UpdateState.DOWNLOADING,
                    currentSnapshot.installedVersion(),
                    candidate.version(),
                    0,
                    candidate.jarSize(),
                    "Downloading update...",
                    currentSnapshot.releaseNotes(),
                    candidate,
                    null
            ));
        }

        executor.submit(() -> {
            AtomicBoolean cancelled = downloadCancelled;
            Path partFile = updateDir.resolve("candidate.jar.part");
            Path targetFile = updateDir.resolve("candidate.jar");

            try {
                Files.createDirectories(updateDir);

                client.downloadCandidateJar(candidate, partFile, (bytesRead, totalBytes) -> {
                    synchronized (stateLock) {
                        if (currentSnapshot.state() == UpdateState.DOWNLOADING) {
                            updateSnapshot(new UpdateSnapshot(
                                    UpdateState.DOWNLOADING,
                                    currentSnapshot.installedVersion(),
                                    candidate.version(),
                                    bytesRead,
                                    totalBytes,
                                    "Downloading: " + (bytesRead * 100 / Math.max(1, totalBytes)) + "%",
                                    currentSnapshot.releaseNotes(),
                                    candidate,
                                    null
                            ));
                        }
                    }
                }, cancelled);

                synchronized (stateLock) {
                    updateSnapshot(new UpdateSnapshot(
                            UpdateState.VERIFYING,
                            currentSnapshot.installedVersion(),
                            candidate.version(),
                            candidate.jarSize(),
                            candidate.jarSize(),
                            "Verifying downloaded update...",
                            currentSnapshot.releaseNotes(),
                            candidate,
                            null
                    ));
                }

                Files.move(partFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

                // Write update-manifest.json and update-manifest.sig beside candidate
                Files.write(updateDir.resolve("update-manifest.json"), candidate.manifestBytes());
                Files.write(updateDir.resolve("update-manifest.sig"), candidate.signatureBytes());

                synchronized (stateLock) {
                    updateSnapshot(new UpdateSnapshot(
                            UpdateState.READY_TO_RESTART,
                            currentSnapshot.installedVersion(),
                            candidate.version(),
                            candidate.jarSize(),
                            candidate.jarSize(),
                            "Update ready to restart and install",
                            currentSnapshot.releaseNotes(),
                            candidate,
                            targetFile
                    ));
                }
            } catch (Exception ex) {
                try {
                    Files.deleteIfExists(partFile);
                } catch (IOException ignored) {
                }

                synchronized (stateLock) {
                    boolean wasCancelled = cancelled != null && cancelled.get();
                    if (wasCancelled) {
                        updateSnapshot(new UpdateSnapshot(
                                UpdateState.UPDATE_AVAILABLE,
                                currentSnapshot.installedVersion(),
                                candidate.version(),
                                0,
                                candidate.jarSize(),
                                "Download cancelled",
                                currentSnapshot.releaseNotes(),
                                candidate,
                                null
                        ));
                    } else {
                        updateSnapshot(new UpdateSnapshot(
                                UpdateState.FAILED,
                                currentSnapshot.installedVersion(),
                                candidate.version(),
                                0,
                                candidate.jarSize(),
                                "Download failed: " + ex.getMessage(),
                                currentSnapshot.releaseNotes(),
                                candidate,
                                null
                        ));
                    }
                }
            }
        });
    }

    public void cancelDownload() {
        synchronized (stateLock) {
            if (downloadCancelled != null) {
                downloadCancelled.set(true);
            }
        }
    }

    public void markRestarting() {
        synchronized (stateLock) {
            updateSnapshot(new UpdateSnapshot(
                    UpdateState.RESTARTING,
                    currentSnapshot.installedVersion(),
                    currentSnapshot.availableVersion(),
                    currentSnapshot.bytesDownloaded(),
                    currentSnapshot.totalBytes(),
                    "Restarting application...",
                    currentSnapshot.releaseNotes(),
                    currentSnapshot.candidate(),
                    currentSnapshot.downloadedCandidateJar()
            ));
        }
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            closed = true;
            cancelDownload();
        }
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    public static Path resolveInstallRoot() {
        try {
            var cs = PeerApplication.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                URI uri = cs.getLocation().toURI();
                Path path = Path.of(uri).toAbsolutePath().normalize();
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                    return path.getParent();
                }
            }
        } catch (Exception ignored) {
        }
        return Path.of(".").toAbsolutePath().normalize();
    }
}
