package vn.edu.p2p.peer;

import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.config.ConfigSnapshot;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.ui.DesktopTheme;
import vn.edu.p2p.peer.ui.MainFrame;
import vn.edu.p2p.peer.ui.SettingsDialog;
import vn.edu.p2p.peer.ui.SwingIncomingFilePrompt;
import vn.edu.p2p.peer.update.BuildInfo;
import vn.edu.p2p.peer.update.ReleaseClient;
import vn.edu.p2p.peer.update.RestartCoordinator;
import vn.edu.p2p.peer.update.UpdateInstaller;
import vn.edu.p2p.peer.update.UpdateJournal;
import vn.edu.p2p.peer.update.UpdateLocks;
import vn.edu.p2p.peer.update.UpdateService;
import vn.edu.p2p.peer.util.HashUtil;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PeerApplication {

    private static UpdateLocks.FileLockHandle activeSharedRuntimeLock;

    private PeerApplication() {
    }

    public static void main(String[] args) {
        BuildInfo buildInfo = BuildInfo.load();
        ReleaseClient releaseClient = new ReleaseClient(buildInfo);
        launch(args, releaseClient);
    }

    static void launch(String[] args, ReleaseClient releaseClient) {
        try {
            Path configPath = Path.of("peer.properties");
            String bootstrapTxId = null;

            if (args.length == 0) {
                // Default config
            } else if (args.length == 1) {
                if (UpdateInstaller.BOOTSTRAP_FLAG.equals(args[0])) {
                    throw new IllegalArgumentException("Missing transactionId for " + UpdateInstaller.BOOTSTRAP_FLAG);
                }
                if (args[0].startsWith("-")) {
                    throw new IllegalArgumentException("Unrecognized argument: " + args[0]);
                }
                configPath = Path.of(args[0]);
            } else if (args.length == 2) {
                if (UpdateInstaller.BOOTSTRAP_FLAG.equals(args[0])) {
                    bootstrapTxId = args[1];
                } else {
                    throw new IllegalArgumentException("Unrecognized arguments: " + Arrays.toString(args));
                }
            } else if (args.length == 3) {
                if (args[0].startsWith("-")) {
                    throw new IllegalArgumentException("Config argument must precede flags: " + args[0]);
                }
                configPath = Path.of(args[0]);
                if (UpdateInstaller.BOOTSTRAP_FLAG.equals(args[1])) {
                    bootstrapTxId = args[2];
                } else {
                    throw new IllegalArgumentException("Unrecognized arguments: " + Arrays.toString(args));
                }
            } else {
                throw new IllegalArgumentException("Too many arguments: " + Arrays.toString(args));
            }

            Path installRoot = RestartCoordinator.resolveInstallRoot();
            Path updateDir = installRoot != null ? installRoot.resolve(".p2p-update") : null;

            // Authenticated child bootstrap branches BEFORE ordinary recovery, operation waiting, and shared lock
            if (bootstrapTxId != null) {
                runChildBootstrap(bootstrapTxId, configPath, installRoot, updateDir, releaseClient);
                return;
            }

            runOrdinaryLaunch(configPath, installRoot, updateDir, releaseClient);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(
                    null,
                    "Application initialization error: " + ex.getMessage(),
                    "Startup Failure",
                    JOptionPane.ERROR_MESSAGE
            );
            System.exit(1);
        }
    }

    private static void runChildBootstrap(
            String bootstrapTxId,
            Path configPath,
            Path installRoot,
            Path updateDir,
            ReleaseClient releaseClient
    ) throws Exception {
        if (installRoot == null || updateDir == null) {
            System.err.println("Child bootstrap aborted: application is not running from canonical installation");
            System.exit(1);
            return;
        }

        UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);

        // Bounded wait for spawn-to-journal-write race
        long deadline = System.nanoTime() + 2_000_000_000L;
        UpdateJournal journal = null;
        long selfPid = ProcessHandle.current().pid();
        while (System.nanoTime() < deadline) {
            journal = UpdateJournal.read(updateDir);
            if (journal != null && journal.childPid() != null && journal.childPid() == selfPid) {
                break;
            }
            Thread.sleep(50);
        }

        if (journal == null) {
            System.err.println("Child bootstrap aborted: no update journal found in " + updateDir);
            System.exit(1);
            return;
        }
        if (journal.phase() != UpdateJournal.Phase.LAUNCHING) {
            System.err.println("Child bootstrap aborted: journal phase is " + journal.phase() + " (expected LAUNCHING)");
            System.exit(1);
            return;
        }
        if (!bootstrapTxId.equals(journal.transactionId())) {
            System.err.println("Child bootstrap aborted: transactionId mismatch");
            System.exit(1);
            return;
        }
        if (journal.childPid() == null || journal.childPid() != selfPid) {
            System.err.println("Child bootstrap aborted: child PID mismatch");
            System.exit(1);
            return;
        }

        // Validate CodeSource binary hash against journal
        Path peerAppJar = installRoot.resolve("peer-app.jar");
        String currentSha = HashUtil.sha256(peerAppJar);
        String expectedSha = journal.operation() == UpdateJournal.Operation.UPDATE
                ? journal.candidateSha256()
                : journal.currentSha256();
        if (!currentSha.equalsIgnoreCase(expectedSha)) {
            System.err.println("Child bootstrap aborted: binary hash mismatch");
            System.exit(1);
            return;
        }

        // Validate configPath and cwd against journal
        Path resolvedConfig = configPath.isAbsolute() ? configPath.normalize() : Path.of("").toAbsolutePath().resolve(configPath).normalize();
        if (!resolvedConfig.equals(Path.of(journal.configPath()))) {
            System.err.println("Child bootstrap aborted: config path mismatch");
            System.exit(1);
            return;
        }
        if (!Path.of("").toAbsolutePath().normalize().equals(Path.of(journal.workingDirectory()))) {
            System.err.println("Child bootstrap aborted: working directory mismatch");
            System.exit(1);
            return;
        }

        // Load config and theme
        try {
            DesktopTheme.install();
        } catch (Exception ignored) {
        }

        ConfigStore configStore = new ConfigStore(configPath, Path.of(""));
        ConfigSnapshot snapshot = configStore.read();
        configStore.validate(snapshot.properties());
        boolean checkOnStartup = ConfigStore.checkOnStartup(snapshot);
        AppConfig config = AppConfig.load(configStore.targetPath());

        PeerRuntime runtime = new PeerRuntime(config);
        UpdateService updateService = new UpdateService(releaseClient, installRoot);
        RestartCoordinator restartCoordinator = new RestartCoordinator(installRoot);

        // Construct and wire MainFrame on EDT in disabled Starting state
        AtomicReference<MainFrame> frameRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MainFrame frame = new MainFrame(runtime, configStore, updateService, restartCoordinator);
            frame.setStarting(true);
            frame.setEnabled(false);
            frame.setVisible(true);
            runtime.transferManager().setListener(frame);
            runtime.transferManager().setIncomingFilePrompt(new SwingIncomingFilePrompt(frame));
            setupCloseHandler(frame, runtime, updateService);
            frameRef.set(frame);
        });

        MainFrame frame = frameRef.get();

        // Emit exact P2P_BOOTSTRAP_READY NONCE VERSION\n to stdout
        System.out.println(UpdateInstaller.CHILD_READY_PREFIX + bootstrapTxId + " " + releaseClient.buildInfo().displayVersion());
        System.out.flush();

        // Bounded wait for helper COMMIT on stdin (20s)
        BufferedReader inReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        long commitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String commitLine = null;
        while (System.nanoTime() < commitDeadline) {
            if (inReader.ready()) {
                commitLine = inReader.readLine();
                break;
            }
            Thread.sleep(50);
        }

        String expectedCommit = UpdateInstaller.COMMIT_PREFIX + bootstrapTxId;
        if (commitLine == null || !expectedCommit.equals(commitLine.trim())) {
            System.err.println("Child bootstrap aborted: expected '" + expectedCommit + "' but got '" + commitLine + "'");
            System.exit(1);
            return;
        }

        // Revalidate committed journal
        UpdateJournal committedJournal = UpdateJournal.read(updateDir);
        if (committedJournal == null || committedJournal.phase() != UpdateJournal.Phase.COMMITTED) {
            System.err.println("Child bootstrap aborted: journal is not COMMITTED");
            System.exit(1);
            return;
        }

        // Acquire lifetime shared runtime lock
        activeSharedRuntimeLock = UpdateLocks.acquireSharedRuntimeLock(updateDir);
        // Enable UI and networking worker after COMMIT and shared lock
        SwingUtilities.invokeLater(() -> frame.setEnabled(true));
        startNetworkingWorker(frame, runtime, updateService, checkOnStartup);
    }

    private static void runOrdinaryLaunch(
            Path configPath,
            Path installRoot,
            Path updateDir,
            ReleaseClient releaseClient
    ) throws Exception {
        // Canonical install lifecycle & lock management
        if (installRoot != null && updateDir != null) {
            if (Files.exists(updateDir)) {
                try {
                    UpdateJournal interrupted = UpdateJournal.read(updateDir);
                    if (interrupted != null && !interrupted.phase().isTerminal() && interrupted.phase() != UpdateJournal.Phase.PREPARED) {
                        Path helperJar = updateDir.resolve("helper.jar");
                        if (Files.exists(helperJar)) {
                            String ext = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
                            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java" + ext).toString();

                            ProcessHandle self = ProcessHandle.current();
                            Instant selfStart = self.info().startInstant().orElse(null);

                            List<String> recoverCmd = new ArrayList<>();
                            recoverCmd.add(javaBin);
                            recoverCmd.add("-cp");
                            recoverCmd.add(helperJar.toString());
                            recoverCmd.add(UpdateInstaller.class.getName());
                            recoverCmd.add(UpdateInstaller.RECOVER_FLAG);
                            recoverCmd.add(installRoot.toString());
                            if (selfStart != null) {
                                recoverCmd.add(String.valueOf(self.pid()));
                                recoverCmd.add(selfStart.toString());
                            }

                            new ProcessBuilder(recoverCmd).start();
                            System.exit(0);
                            return;
                        }
                    }
                } catch (Exception ex) {
                    System.err.println("Recovery check warning: " + ex.getMessage());
                }

                // Wait boundedly if an update installer operation is active
                if (UpdateLocks.isOperationActive(updateDir)) {
                    boolean finished = UpdateLocks.waitForOperationExit(updateDir, Duration.ofSeconds(2));
                    if (!finished) {
                        JOptionPane.showMessageDialog(
                                null,
                                "An update installation is currently in progress. Please wait and try again.",
                                "Update In Progress",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                        System.exit(0);
                        return;
                    }
                }
            }

            // Acquire shared runtime lock for application lifetime, initializing state if fresh install
            try {
                UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);
                activeSharedRuntimeLock = UpdateLocks.acquireSharedRuntimeLock(updateDir);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        null,
                        "Could not acquire runtime lock: " + ex.getMessage(),
                        "Startup Refused",
                        JOptionPane.ERROR_MESSAGE
                );
                System.exit(1);
                return;
            }
        }
        try {
            DesktopTheme.install();
        } catch (Exception ex) {
            System.err.println("Could not initialize desktop theme: " + ex.getMessage());
        }

        ConfigStore configStore = new ConfigStore(configPath, Path.of(""));
        ConfigSnapshot snapshot;
        try {
            snapshot = configStore.read();
        } catch (ConfigStore.SyntaxException ex) {
            boolean repaired = SettingsDialog.showBootstrapDialog(configStore, ex.getMessage(), ex);
            if (!repaired) {
                System.exit(0);
                return;
            }
            snapshot = configStore.read();
        } catch (IOException ex) {
            boolean repaired = SettingsDialog.showBootstrapDialog(configStore, ex.getMessage());
            if (!repaired) {
                System.exit(0);
                return;
            }
            snapshot = configStore.read();
        }

        if (!snapshot.exists()) {
            boolean saved = SettingsDialog.showBootstrapDialog(configStore, null);
            if (!saved) {
                System.exit(0);
                return;
            }
            snapshot = configStore.read();
        }

        AppConfig config;
        boolean checkOnStartup;
        while (true) {
            try {
                configStore.validate(snapshot.properties());
                checkOnStartup = ConfigStore.checkOnStartup(snapshot);
                config = AppConfig.load(configStore.targetPath());
                break;
            } catch (Exception ex) {
                boolean repaired = SettingsDialog.showBootstrapDialog(configStore, ex.getMessage());
                if (!repaired) {
                    System.exit(0);
                    return;
                }
                try {
                    snapshot = configStore.read();
                } catch (ConfigStore.SyntaxException synEx) {
                    boolean syntaxRepaired = SettingsDialog.showBootstrapDialog(configStore, synEx.getMessage(), synEx);
                    if (!syntaxRepaired) {
                        System.exit(0);
                        return;
                    }
                    snapshot = configStore.read();
                }
            }
        }
        final boolean checkOnStartupCaptured = checkOnStartup;

        PeerRuntime runtime = new PeerRuntime(config);
        UpdateService updateService = new UpdateService(releaseClient, installRoot);
        RestartCoordinator restartCoordinator = new RestartCoordinator(installRoot);

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(runtime, configStore, updateService, restartCoordinator);
            frame.setStarting(true);
            frame.setVisible(true);

            runtime.transferManager().setListener(frame);
            runtime.transferManager().setIncomingFilePrompt(new SwingIncomingFilePrompt(frame));

            setupCloseHandler(frame, runtime, updateService);
            startNetworkingWorker(frame, runtime, updateService, checkOnStartupCaptured);
        });
    }

    private static void setupCloseHandler(MainFrame frame, PeerRuntime runtime, UpdateService updateService) {
        AtomicBoolean closing = new AtomicBoolean(false);
        Runnable closeApp = () -> {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            frame.setEnabled(false);
            updateService.close();

            new Thread(() -> {
                try {
                    runtime.close();
                } catch (Exception ignored) {
                } finally {
                    if (activeSharedRuntimeLock != null) {
                        try {
                            activeSharedRuntimeLock.close();
                        } catch (Exception ignored) {
                        }
                    }
                    SwingUtilities.invokeLater(() -> {
                        frame.dispose();
                        System.exit(0);
                    });
                }
            }, "peer-close-worker").start();
        };

        frame.setCloseHandler(closeApp);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                updateService.close();
                runtime.close();
            } catch (Exception ignored) {
            } finally {
                if (activeSharedRuntimeLock != null) {
                    try {
                        activeSharedRuntimeLock.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "peer-shutdown-hook"));
    }

    private static void startNetworkingWorker(
            MainFrame frame,
            PeerRuntime runtime,
            UpdateService updateService,
            boolean checkOnStartup
    ) {
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                runtime.start();
                return runtime.trackerObservedHost();
            }

            @Override
            protected void done() {
                try {
                    get();
                    frame.setStarting(false);
                    frame.refreshPeers();

                    if (checkOnStartup) {
                        updateService.checkForUpdates();
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(
                            frame,
                            "Peer startup failed: " + cause.getMessage(),
                            "Startup Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                    frame.dispose();
                    try {
                        runtime.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }.execute();
    }
}
