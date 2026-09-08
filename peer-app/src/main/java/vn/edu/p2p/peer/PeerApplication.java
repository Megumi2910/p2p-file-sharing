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

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
            try {
                DesktopTheme.install();
            } catch (Exception ex) {
                System.err.println("Could not initialize the desktop theme: " + ex.getMessage());
            }

            Path configPath = Path.of("peer.properties");
            String bootstrapTxId = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (UpdateInstaller.BOOTSTRAP_FLAG.equals(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing transactionId for " + UpdateInstaller.BOOTSTRAP_FLAG);
                    }
                    bootstrapTxId = args[++i];
                } else if (arg.startsWith("-")) {
                    throw new IllegalArgumentException("Unrecognized argument: " + arg);
                } else {
                    configPath = Path.of(arg);
                }
            }

            Path installRoot = RestartCoordinator.resolveInstallRoot();
            Path updateDir = installRoot.resolve(".p2p-update");

            // Interrupted update recovery check
            if (Files.exists(updateDir)) {
                try {
                    UpdateJournal interrupted = UpdateJournal.read(updateDir);
                    if (interrupted != null && !interrupted.phase().isTerminal() && interrupted.phase() != UpdateJournal.Phase.PREPARED) {
                        Path helperJar = updateDir.resolve("helper.jar");
                        if (Files.exists(helperJar)) {
                            String ext = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
                            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java" + ext).toString();
                            new ProcessBuilder(
                                    javaBin, "-cp", helperJar.toString(),
                                    UpdateInstaller.class.getName(), UpdateInstaller.RECOVER_FLAG, installRoot.toString()
                            ).start();
                            System.exit(0);
                            return;
                        }
                    }
                } catch (Exception ex) {
                    System.err.println("Recovery check warning: " + ex.getMessage());
                }
            }

            // Wait boundedly if an update installer operation is active
            if (Files.exists(updateDir) && UpdateLocks.isOperationActive(updateDir)) {
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

            // Acquire shared runtime lock for application lifetime
            try {
                activeSharedRuntimeLock = UpdateLocks.acquireSharedRuntimeLock(updateDir);
            } catch (Exception ex) {
                System.err.println("Notice: shared runtime lock could not be acquired: " + ex.getMessage());
            }

            ConfigStore configStore = new ConfigStore(configPath, Path.of(""));
            ConfigSnapshot snapshot = configStore.read();

            if (!snapshot.exists()) {
                boolean saved = SettingsDialog.showBootstrapDialog(configStore, null);
                if (!saved) {
                    System.exit(0);
                    return;
                }
            }

            AppConfig config;
            try {
                config = AppConfig.load(configStore.targetPath());
            } catch (Exception ex) {
                boolean repaired = SettingsDialog.showBootstrapDialog(configStore, ex.getMessage());
                if (!repaired) {
                    System.exit(0);
                    return;
                }
                config = AppConfig.load(configStore.targetPath());
            }

            PeerRuntime runtime = new PeerRuntime(config);
            UpdateService updateService = new UpdateService(releaseClient, installRoot);
            RestartCoordinator restartCoordinator = new RestartCoordinator(installRoot);

            // Child bootstrap handshake if spawned by installer
            if (bootstrapTxId != null) {
                System.out.println(UpdateInstaller.CHILD_READY_PREFIX + bootstrapTxId + " " + releaseClient.buildInfo().displayVersion());
                System.out.flush();

                BufferedReader inReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                boolean committed = false;
                while (System.nanoTime() < deadline) {
                    if (inReader.ready()) {
                        String line = inReader.readLine();
                        if (line != null && line.startsWith(UpdateInstaller.COMMIT_PREFIX + bootstrapTxId)) {
                            committed = true;
                            break;
                        }
                    }
                    Thread.sleep(50);
                }

                if (!committed) {
                    System.err.println("Bootstrap aborted: no COMMIT received from updater within 20s");
                    System.exit(1);
                    return;
                }
            }

            SwingUtilities.invokeLater(() -> {
                MainFrame frame = new MainFrame(runtime, configStore, updateService, restartCoordinator);
                frame.setStarting(true);
                frame.setVisible(true);

                runtime.transferManager().setListener(frame);
                runtime.transferManager().setIncomingFilePrompt(new SwingIncomingFilePrompt(frame));

                // Unified close path
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

                            ConfigSnapshot snap = configStore.read();
                            boolean checkStartup = !"false".equalsIgnoreCase(snap.getProperty("updates.checkOnStartup", "true"));
                            if (checkStartup) {
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
            });

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(
                    null,
                    ex.getMessage(),
                    "Peer startup failed",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }
}
