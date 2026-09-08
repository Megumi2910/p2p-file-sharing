package vn.edu.p2p.peer.update;

import vn.edu.p2p.peer.PeerApplication;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class RestartCoordinator {

    private final Path installRoot;
    private final Path updateDir;

    public RestartCoordinator() {
        this(resolveInstallRoot());
    }

    public RestartCoordinator(Path installRoot) {
        this.installRoot = Objects.requireNonNull(installRoot, "installRoot cannot be null").toAbsolutePath().normalize();
        this.updateDir = this.installRoot.resolve(".p2p-update");
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
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win") && !os.contains("linux")) {
            return "Automated restart is only supported on Windows and Linux (current OS: " + os + ")";
        }

        Path peerAppJar = installRoot.resolve("peer-app.jar");
        if (!Files.isRegularFile(peerAppJar)) {
            return "Application is not running from canonical peer-app.jar (location: " + installRoot + ")";
        }

        if (!Files.isWritable(installRoot)) {
            return "Installation directory is not writable: " + installRoot;
        }

        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : jvmArgs) {
            if (!arg.startsWith("-Djava.awt.headless=") && !arg.toLowerCase().startsWith("-Dfile.encoding=")) {
                return "Unsupported JVM arguments present (" + arg + "). Please relaunch manually.";
            }
        }

        try {
            Files.createDirectories(updateDir);
            Path test1 = Files.createTempFile(updateDir, "test1.", ".tmp");
            Path test2 = updateDir.resolve("test2.tmp");
            try {
                Files.move(test1, test2, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(test1);
                Files.deleteIfExists(test2);
            }
        } catch (Exception ex) {
            return "Filesystem does not support atomic replacement: " + ex.getMessage();
        }

        return null;
    }

    public void restartForSettings(
            PeerRuntime runtime,
            ConfigStore configStore,
            Consumer<String> statusConsumer
    ) throws Exception {
        executeOperation(runtime, UpdateJournal.Operation.RESTART, configStore.targetPath(), null, statusConsumer);
    }

    public void installPreparedUpdate(
            PeerRuntime runtime,
            UpdateService updateService,
            Consumer<String> statusConsumer
    ) throws Exception {
        UpdateService.UpdateSnapshot snapshot = updateService.snapshot();
        if (snapshot.state() != UpdateService.UpdateState.READY_TO_RESTART || snapshot.candidate() == null) {
            throw new IllegalStateException("No verified update candidate is ready for installation");
        }
        executeOperation(runtime, UpdateJournal.Operation.UPDATE, runtime.config().downloadDir().resolveSibling("peer.properties"), updateService, statusConsumer);
    }

    private void executeOperation(
            PeerRuntime runtime,
            UpdateJournal.Operation op,
            Path configPath,
            UpdateService updateService,
            Consumer<String> statusConsumer
    ) throws Exception {
        String reason = getUnsupportedRestartReason();
        if (reason != null) {
            throw new IllegalStateException(reason);
        }

        if (statusConsumer != null) {
            statusConsumer.accept("Preparing restart helper...");
        }

        Files.createDirectories(updateDir);
        Path peerAppJar = installRoot.resolve("peer-app.jar");
        String currentSha256 = HashUtil.sha256(peerAppJar);

        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String transactionId = HexFormat.of().formatHex(nonce);

        String installId = UpdateJournal.getOrCreateInstallId(updateDir, ReleaseClient.EXPECTED_REPOSITORY, installRoot);
        Path helperJar = updateDir.resolve("helper.jar");
        Files.copy(peerAppJar, helperJar, StandardCopyOption.REPLACE_EXISTING);

        String targetVer = "";
        String candSha = "";
        if (op == UpdateJournal.Operation.UPDATE && updateService != null) {
            targetVer = updateService.snapshot().candidate().version().toString();
            candSha = updateService.snapshot().candidate().manifest().sha256();
        }

        String allowedJvmArgs = getAllowedJvmArgs();
        String javaPath = resolveJavaPath();
        String cwd = Path.of("").toAbsolutePath().normalize().toString();

        ProcessHandle currentProcess = ProcessHandle.current();
        Instant currentStart = currentProcess.info().startInstant().orElse(Instant.now());

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
                configPath.toAbsolutePath().normalize().toString(),
                cwd,
                javaPath,
                allowedJvmArgs,
                runtime.config().peerId(),
                targetVer,
                currentSha256,
                candSha,
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
        Process helper = pb.start();

        BufferedReader helperOut = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
        long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean helperReady = false;
        while (System.nanoTime() < readyDeadline && helper.isAlive()) {
            if (helperOut.ready()) {
                String line = helperOut.readLine();
                if (line != null && line.startsWith(UpdateInstaller.READY_PREFIX + transactionId)) {
                    helperReady = true;
                    break;
                }
            }
            Thread.sleep(50);
        }

        if (!helperReady) {
            helper.destroyForcibly();
            throw new IOException("Update helper process failed to signal readiness within 5 seconds");
        }

        // Reserve idle restart on TransferManager
        if (statusConsumer != null) {
            statusConsumer.accept("Reserving idle transfer status...");
        }

        boolean reserved = runtime.transferManager().tryReserveRestart();
        if (!reserved) {
            helper.destroyForcibly();
            throw new IllegalStateException("Active transfers or prompt decisions are in progress. Restart refused.");
        }

        // Close runtime off-EDT
        if (statusConsumer != null) {
            statusConsumer.accept("Shutting down running services...");
        }

        try {
            runtime.close();
        } catch (Exception ex) {
            runtime.transferManager().cancelRestartReservation();
            helper.destroyForcibly();
            throw new IOException("Runtime shutdown failed: " + ex.getMessage(), ex);
        }

        // Send GO to helper
        OutputStream helperIn = helper.getOutputStream();
        helperIn.write((UpdateInstaller.GO_PREFIX + transactionId + "\n").getBytes(StandardCharsets.UTF_8));
        helperIn.flush();

        if (statusConsumer != null) {
            statusConsumer.accept("Exiting application for restart...");
        }

        // Hand-off complete, exit parent
        System.exit(0);
    }

    private static String getAllowedJvmArgs() {
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        List<String> allowed = new ArrayList<>();
        for (String arg : jvmArgs) {
            if (arg.startsWith("-Djava.awt.headless=") || arg.toLowerCase().startsWith("-Dfile.encoding=")) {
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
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                    return path.getParent();
                }
            }
        } catch (Exception ignored) {
        }
        return Path.of(".").toAbsolutePath().normalize();
    }
}
