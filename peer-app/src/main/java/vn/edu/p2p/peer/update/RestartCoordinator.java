package vn.edu.p2p.peer.update;

import vn.edu.p2p.peer.PeerApplication;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RestartCoordinator {

    private static final AtomicBoolean OPERATION_ACTIVE = new AtomicBoolean(false);

    private final Path installRoot;
    private final Path updateDir;

    public static class RestartFailure extends IOException {
        private final boolean runtimeStopped;

        public RestartFailure(String message, boolean runtimeStopped, Throwable cause) {
            super(message, cause);
            this.runtimeStopped = runtimeStopped;
        }

        public boolean runtimeStopped() {
            return runtimeStopped;
        }
    }

    public RestartCoordinator() {
        this(resolveInstallRoot());
    }

    public RestartCoordinator(Path installRoot) {
        this.installRoot = installRoot != null ? installRoot.toAbsolutePath().normalize() : null;
        this.updateDir = this.installRoot != null ? this.installRoot.resolve(".p2p-update") : null;
    }

    public Path installRoot() {
        return installRoot;
    }

    public Path updateDir() {
        return updateDir;
    }

    public boolean isAutomatedRestartSupported() {
        return getUnsupportedRestartReason() == null;
    }

    public String getUnsupportedRestartReason() {
        if (installRoot == null || updateDir == null) {
            return "Application is not running from canonical peer-app.jar";
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win") && !os.contains("linux")) {
            return "Automated restart is only supported on Windows and Linux (current OS: " + os + ")";
        }

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        if (!Files.isRegularFile(peerAppJar)) {
            return "Application is not running from canonical peer-app.jar (location: " + installRoot + ")";
        }
        if (Files.isSymbolicLink(peerAppJar) || Files.isSymbolicLink(installRoot)) {
            return "Symlinks are not supported for canonical installation: " + installRoot;
        }

        String rootStr = installRoot.toString();
        if (rootStr.startsWith("\\\\") || rootStr.startsWith("//")) {
            return "UNC/remote paths are not supported: " + installRoot;
        }

        if (!Files.isWritable(installRoot)) {
            return "Installation directory is not writable: " + installRoot;
        }

        BuildInfo buildInfo = BuildInfo.load();
        if (!buildInfo.canInstall()) {
            return "Automated restart/update disabled: application identity or public key is not installable";
        }

        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : jvmArgs) {
            if (!isSupportedJvmArg(arg)) {
                return "Unsupported JVM arguments present (" + arg + "). Please relaunch manually.";
            }
        }

        return null;
    }

    public void preflightScratchProbe() throws Exception {
        String reason = getUnsupportedRestartReason();
        if (reason != null) {
            throw new IllegalStateException(reason);
        }

        UpdateJournal.getOrCreateInstallId(updateDir, ReleaseClient.EXPECTED_REPOSITORY, installRoot);

        Path test1 = Files.createTempFile(updateDir, "probe1.", ".tmp");
        Path test2 = updateDir.resolve("probe2.tmp");
        try {
            Files.move(test1, test2, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(test1);
            Files.deleteIfExists(test2);
        }
    }

    public static boolean isSupportedJvmArg(String arg) {
        if (arg == null || arg.isBlank()) return false;
        if (arg.toLowerCase().startsWith("-djava.awt.headless=")) {
            String val = arg.substring(arg.indexOf('=') + 1);
            return "false".equalsIgnoreCase(val);
        }
        if (arg.toLowerCase().startsWith("-dfile.encoding=")) {
            String val = arg.substring(arg.indexOf('=') + 1);
            return "UTF-8".equals(val);
        }
        return false;
    }

    public void restartForSettings(
            PeerRuntime runtime,
            ConfigStore configStore,
            Consumer<String> statusConsumer
    ) throws Exception {
        Objects.requireNonNull(runtime, "runtime cannot be null");
        Objects.requireNonNull(configStore, "configStore cannot be null");
        executeOperation(runtime, configStore, UpdateJournal.Operation.RESTART, null, statusConsumer);
    }

    public void installPreparedUpdate(
            PeerRuntime runtime,
            ConfigStore configStore,
            UpdateService updateService,
            Consumer<String> statusConsumer
    ) throws Exception {
        Objects.requireNonNull(runtime, "runtime cannot be null");
        Objects.requireNonNull(configStore, "configStore cannot be null");
        Objects.requireNonNull(updateService, "updateService cannot be null");
        executeOperation(runtime, configStore, UpdateJournal.Operation.UPDATE, updateService, statusConsumer);
    }

    private void executeOperation(
            PeerRuntime runtime,
            ConfigStore configStore,
            UpdateJournal.Operation op,
            UpdateService updateService,
            Consumer<String> statusConsumer
    ) throws Exception {
        if (!OPERATION_ACTIVE.compareAndSet(false, true)) {
            throw new IllegalStateException("Another restart or update operation is already active");
        }

        UpdateService.UpdateSnapshot previousSnapshot = null;
        Process helper = null;
        boolean completedSuccessfully = false;

        try {
            if (op == UpdateJournal.Operation.UPDATE && updateService != null) {
                previousSnapshot = updateService.beginRestart();
            }

            String reason = getUnsupportedRestartReason();
            if (reason != null) {
                throw new IllegalStateException(reason);
            }
            if (statusConsumer != null) {
                statusConsumer.accept("Preparing restart helper...");
            }

            preflightScratchProbe();

            Path peerAppJar = installRoot.resolve("peer-app.jar");
            String currentSha256 = HashUtil.sha256(peerAppJar);

            byte[] nonce = new byte[32];
            new SecureRandom().nextBytes(nonce);
            String transactionId = HexFormat.of().formatHex(nonce);

            String installId = UpdateJournal.getOrCreateInstallId(updateDir, ReleaseClient.EXPECTED_REPOSITORY, installRoot);
            Path helperJar = updateDir.resolve("helper.jar");

            String currentVersion = BuildInfo.load().displayVersion();
            String targetVersion;
            String candidateSha256;
            if (op == UpdateJournal.Operation.UPDATE && updateService != null) {
                targetVersion = previousSnapshot.candidate().version().toString();
                candidateSha256 = previousSnapshot.candidate().manifest().sha256();
            } else {
                targetVersion = currentVersion;
                candidateSha256 = "";
            }

            String allowedJvmArgs = getAllowedJvmArgs();
            String javaPath = resolveJavaPath();
            String configPath = configStore.targetPath().toAbsolutePath().normalize().toString();
            String cwd = configStore.workingDirectory().toAbsolutePath().normalize().toString();

            ProcessHandle currentProcess = ProcessHandle.current();
            Instant currentStart = currentProcess.info().startInstant().orElse(null);
            if (currentStart == null) {
                throw new IllegalStateException("Process start instant could not be determined; automatic restart refused");
            }

            // Copy helper, force, verify, write journal, and spawn under parent operation lock
            try (UpdateLocks.FileLockHandle parentOpLock = UpdateLocks.acquireOperationLock(updateDir, Duration.ofSeconds(5))) {
                Files.copy(peerAppJar, helperJar, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel fc = FileChannel.open(helperJar, StandardOpenOption.WRITE)) {
                    fc.force(true);
                }
                String helperSha = HashUtil.sha256(helperJar);
                if (!helperSha.equalsIgnoreCase(currentSha256)) {
                    throw new SecurityException("Helper JAR verification failed: hash mismatch");
                }

                UpdateJournal journal = new UpdateJournal(
                        UpdateJournal.SCHEMA_VERSION,
                        ReleaseClient.EXPECTED_REPOSITORY,
                        installId,
                        op,
                        transactionId,
                        UpdateJournal.Phase.PREPARED,
                        currentProcess.pid(),
                        currentStart,
                        null, null,
                        null, null,
                        configPath,
                        cwd,
                        javaPath,
                        allowedJvmArgs,
                        currentVersion,
                        targetVersion,
                        currentSha256,
                        candidateSha256,
                        ""
                );
                journal.writeAtomic(updateDir);

                // Spawn helper process
                List<String> helperCmd = new ArrayList<>();
                helperCmd.add(javaPath);
                if (!allowedJvmArgs.isBlank()) {
                    for (String arg : allowedJvmArgs.split(" ")) {
                        if (!arg.isBlank()) helperCmd.add(arg);
                    }
                }
                helperCmd.add("-cp");
                helperCmd.add(helperJar.toString());
                helperCmd.add(UpdateInstaller.class.getName());
                helperCmd.add(installRoot.toString());
                helperCmd.add(transactionId);

                ProcessBuilder pb = new ProcessBuilder(helperCmd);
                pb.directory(installRoot.toFile());
                pb.redirectError(updateDir.resolve("helper.err.log").toFile());
                helper = pb.start();
            } // parentOpLock released here so helper can acquire operation.lock

            // Bounded pipe reader for exact helper readiness signal
            ProcessLineReader lineReader = new ProcessLineReader(helper);
            String readyLine = lineReader.readLineWithDeadline(Duration.ofSeconds(5));
            String expectedReady = UpdateInstaller.READY_PREFIX + transactionId;
            if (!expectedReady.equals(readyLine)) {
                helper.destroyForcibly();
                helper.waitFor(2, TimeUnit.SECONDS);
                throw new IOException("Helper failed to signal readiness: expected exact '" + expectedReady + "' but got '" + readyLine + "'");
            }

            // Reserve idle restart on TransferManager
            if (statusConsumer != null) {
                statusConsumer.accept("Reserving idle transfer status...");
            }

            boolean reserved = runtime.transferManager().tryReserveRestart();
            if (!reserved) {
                helper.destroyForcibly();
                helper.waitFor(2, TimeUnit.SECONDS);
                if (previousSnapshot != null && updateService != null) {
                    updateService.restoreReady(previousSnapshot, "Active transfers or prompt decisions are in progress. Restart refused.");
                }
                throw new IllegalStateException("Active transfers or prompt decisions are in progress. Restart refused.");
            }

            // Close runtime off-EDT
            if (statusConsumer != null) {
                statusConsumer.accept("Shutting down running services...");
            }

            try {
                runtime.close();
            } catch (Exception ex) {
                helper.destroyForcibly();
                helper.waitFor(2, TimeUnit.SECONDS);
                throw new RestartFailure("Runtime shutdown failed: " + ex.getMessage(), true, ex);
            }

            // Send GO to helper
            try {
                OutputStream helperIn = helper.getOutputStream();
                helperIn.write((UpdateInstaller.GO_PREFIX + transactionId + "\n").getBytes(StandardCharsets.UTF_8));
                helperIn.flush();
            } catch (Exception ex) {
                helper.destroyForcibly();
                helper.waitFor(2, TimeUnit.SECONDS);
                throw new RestartFailure("Failed to deliver GO to restart helper: " + ex.getMessage(), true, ex);
            }

            if (statusConsumer != null) {
                statusConsumer.accept("Exiting application for restart...");
            }

            completedSuccessfully = true;
            System.exit(0);
        } catch (RestartFailure rf) {
            throw rf;
        } catch (Exception ex) {
            if (helper != null && helper.isAlive()) {
                helper.destroyForcibly();
                try {
                    helper.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
            if (previousSnapshot != null && updateService != null) {
                updateService.restoreReady(previousSnapshot, ex.getMessage());
            }
            throw ex;
        } finally {
            if (!completedSuccessfully) {
                OPERATION_ACTIVE.set(false);
            }
        }
    }

    private static String getAllowedJvmArgs() {
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        List<String> allowed = new ArrayList<>();
        for (String arg : jvmArgs) {
            if (isSupportedJvmArg(arg)) {
                allowed.add(arg);
            }
        }
        return String.join(" ", allowed);
    }

    private static String resolveJavaPath() {
        ProcessHandle current = ProcessHandle.current();
        if (current.info().command().isPresent()) {
            return current.info().command().get();
        }
        String ext = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", "java" + ext).toString();
    }

    public static Path resolveInstallRoot() {
        try {
            var cs = PeerApplication.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                URI uri = cs.getLocation().toURI();
                Path path = Path.of(uri).toAbsolutePath().normalize();
                if (Files.isRegularFile(path) && "peer-app.jar".equals(path.getFileName().toString())) {
                    return path.getParent();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static class ProcessLineReader {
        private final Process process;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<String> errorLines = new CopyOnWriteArrayList<>();

        public ProcessLineReader(Process process) {
            this.process = process;
            Thread stdoutThread = new Thread(() -> readStream(process.getInputStream(), lines), "proc-stdout-reader");
            stdoutThread.setDaemon(true);
            stdoutThread.start();

            Thread stderrThread = new Thread(() -> readStream(process.getErrorStream(), errorLines), "proc-stderr-reader");
            stderrThread.setDaemon(true);
            stderrThread.start();
        }

        private void readStream(InputStream in, Object sink) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                int c;
                while ((c = reader.read()) != -1) {
                    if (c == '\n') {
                        String line = sb.toString().trim();
                        sb.setLength(0);
                        if (sink instanceof BlockingQueue q) {
                            q.offer(line);
                        } else if (sink instanceof List l) {
                            l.add(line);
                        }
                    } else if (c != '\r') {
                        if (sb.length() < 4096) {
                            sb.append((char) c);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        public String readLineWithDeadline(Duration timeout) throws IOException, InterruptedException {
            String line = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (line == null) {
                if (!process.isAlive()) {
                    throw new IOException("Process terminated without output. Stderr: " + String.join("\n", errorLines));
                }
                throw new IOException("Timed out waiting for process message (" + timeout.toSeconds() + "s)");
            }
            return line;
        }
    }
}
