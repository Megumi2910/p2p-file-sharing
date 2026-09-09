package vn.edu.p2p.peer;

import com.sun.net.httpserver.HttpServer;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.config.ConfigSnapshot;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.ui.MainFrame;
import vn.edu.p2p.peer.ui.UpdateDialog;
import vn.edu.p2p.peer.update.BuildInfo;
import vn.edu.p2p.peer.update.ClientVersion;
import vn.edu.p2p.peer.update.ReleaseClient;
import vn.edu.p2p.peer.update.ReleaseFixtureClient;
import vn.edu.p2p.peer.update.ReleaseManifest;
import vn.edu.p2p.peer.update.UpdateInstaller;
import vn.edu.p2p.peer.update.UpdateJournal;
import vn.edu.p2p.peer.update.UpdateLocks;
import vn.edu.p2p.peer.util.HashUtil;
import vn.edu.p2p.tracker.TrackerServer;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ClientUpdateSmoke {

    public static class PeerDriver {
        public static void main(String[] args) {
            try {
                int fixturePort = Integer.parseInt(args[0]);
                Path configPath = Path.of(args[1]).toAbsolutePath().normalize();
                Path screenshotDir = Path.of(args[2]).toAbsolutePath().normalize();
                Files.createDirectories(screenshotDir);

                URI fixtureUri = URI.create("http://127.0.0.1:" + fixturePort + "/api/latest");
                BuildInfo buildInfo = BuildInfo.load();

                ReleaseClient.HttpTransport transport = (uri, method, headers, timeout, cancellation) -> {
                    String url = uri.toString();
                    URI targetUri = uri;
                    if (url.startsWith(ReleaseClient.GITHUB_API_LATEST)) {
                        targetUri = fixtureUri;
                    } else if (url.contains("/releases/download/")) {
                        String assetName = url.substring(url.lastIndexOf('/') + 1);
                        targetUri = URI.create("http://127.0.0.1:" + fixturePort + "/assets/" + assetName);
                    }
                    System.out.println("[Peer 1 Transport] Sending " + method + " to " + targetUri);
                    var req = java.net.http.HttpRequest.newBuilder()
                            .uri(targetUri)
                            .timeout(timeout)
                            .build();
                    var client = java.net.http.HttpClient.newHttpClient();
                    var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                    System.out.println("[Peer 1 Transport] Response: " + resp.statusCode());
                    return new ReleaseClient.TransportResponse(resp.statusCode(), resp.headers().map(), resp.body());
                };

                ReleaseClient releaseClient = ReleaseFixtureClient.create(buildInfo, transport, fixtureUri);

                // Automated Robot supervisor thread for GUI interactions
                new Thread(() -> {
                    try {
                        Robot robot = new Robot();
                        robot.setAutoDelay(100);

                        // Wait for MainFrame
                        MainFrame mainFrame = null;
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                        while (System.nanoTime() < deadline && mainFrame == null) {
                            for (Frame f : Frame.getFrames()) {
                                if (f instanceof MainFrame mf && mf.isVisible()) {
                                    mainFrame = mf;
                                    break;
                                }
                            }
                            Thread.sleep(100);
                        }

                        if (mainFrame == null) {
                            System.err.println("PeerDriver: MainFrame not found within 60s");
                            System.exit(2);
                            return;
                        }

                        System.out.println("PEER_OBSERVATION:MAINFRAME_VISIBLE");
                        captureScreenshot(robot, screenshotDir.resolve("01-mainframe.png"));

                        // Locate Software updates button and click it
                        JButton updatesButton = waitForButton(mainFrame, "Software updates...", Duration.ofSeconds(20));
                        if (updatesButton == null) {
                            System.err.println("PeerDriver: Software updates button not found or not showing");
                            System.exit(3);
                            return;
                        }

                        clickComponent(robot, updatesButton);
                        System.out.println("PEER_OBSERVATION:UPDATES_BUTTON_CLICKED");

                        // Wait for UpdateDialog
                        UpdateDialog updateDialog = waitForWindow(UpdateDialog.class, Duration.ofSeconds(20));
                        if (updateDialog == null) {
                            System.err.println("PeerDriver: UpdateDialog not found or not showing");
                            System.exit(4);
                            return;
                        }

                        System.out.println("PEER_OBSERVATION:UPDATE_DIALOG_VISIBLE");
                        captureScreenshot(robot, screenshotDir.resolve("02-updatedialog.png"));

                        // Check if Update now is already visible from startup check, or click Check now
                        JButton downloadBtn = waitForButton(updateDialog, "Update now", Duration.ofSeconds(3));
                        if (downloadBtn == null) {
                            JButton checkBtn = waitForButton(updateDialog, "Check now", Duration.ofSeconds(5));
                            if (checkBtn != null && checkBtn.isEnabled()) {
                                clickComponent(robot, checkBtn);
                                System.out.println("PEER_OBSERVATION:CHECK_BUTTON_CLICKED");
                            }
                            downloadBtn = waitForButton(updateDialog, "Update now", Duration.ofSeconds(30));
                        }

                        if (downloadBtn == null) {
                            System.err.println("PeerDriver: Update now button not visible after check. Current dialog contents:");
                            printContainerHierarchy(updateDialog);
                            System.exit(5);
                            return;
                        }

                        clickComponent(robot, downloadBtn);
                        System.out.println("PEER_OBSERVATION:DOWNLOAD_BUTTON_CLICKED");

                        // Wait for Restart and install button
                        JButton installBtn = waitForButton(updateDialog, "Restart and install", Duration.ofSeconds(30));
                        if (installBtn == null) {
                            System.err.println("PeerDriver: Restart and install button not visible after download. Dialog contents:");
                            printContainerHierarchy(updateDialog);
                            System.exit(6);
                            return;
                        }

                        captureScreenshot(robot, screenshotDir.resolve("03-ready-to-restart.png"));
                        clickComponent(robot, installBtn);
                        System.out.println("PEER_OBSERVATION:INSTALL_BUTTON_CLICKED");

                        // Find and accept the confirmation dialog
                        JDialog confirmDialog = waitForWindow(JDialog.class, Duration.ofSeconds(15));
                        if (confirmDialog != null) {
                            JButton yesBtn = waitForButton(confirmDialog, "Yes", Duration.ofSeconds(15));
                            if (yesBtn != null) {
                                clickComponent(robot, yesBtn);
                                System.out.println("PEER_OBSERVATION:CONFIRM_YES_CLICKED");
                            }
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace(System.err);
                        System.exit(1);
                    }
                }, "peer-driver-robot").start();

                // Launch peer application on this thread
                PeerApplication.launch(new String[]{configPath.toString()}, releaseClient);
            } catch (Throwable t) {
                t.printStackTrace(System.err);
                System.exit(1);
            }
        }

        private static void clickComponent(Robot robot, JButton comp) throws Exception {
            Point[] loc = new Point[1];
            Dimension[] dim = new Dimension[1];
            double[] scale = new double[]{1.0, 1.0};
            SwingUtilities.invokeAndWait(() -> {
                comp.requestFocusInWindow();
                try {
                    loc[0] = comp.getLocationOnScreen();
                    dim[0] = comp.getSize();
                    if (comp.getGraphicsConfiguration() != null) {
                        scale[0] = comp.getGraphicsConfiguration().getDefaultTransform().getScaleX();
                        scale[1] = comp.getGraphicsConfiguration().getDefaultTransform().getScaleY();
                    }
                } catch (Exception ignored) {
                }
            });

            if (loc[0] == null || dim[0] == null || !comp.isShowing()) {
                throw new IllegalStateException("Component is not visible on screen: " + comp.getText());
            }

            int physX = (int) Math.round((loc[0].x + dim[0].width / 2.0) * scale[0]);
            int physY = (int) Math.round((loc[0].y + dim[0].height / 2.0) * scale[1]);

            System.out.println("Moving Robot mouse to button '" + comp.getText() + "' at physical (" + physX + ", " + physY + ") [scale " + scale[0] + "]");
            robot.mouseMove(physX, physY);
            robot.delay(100);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(100);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(100);

            // Trigger action on EDT only if component is showing and enabled
            SwingUtilities.invokeLater(() -> {
                if (comp.isShowing() && comp.isEnabled()) {
                    comp.doClick();
                }
            });
            robot.delay(200);
            robot.waitForIdle();
        }

        private static JButton waitForButton(Container container, String text, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                JButton[] found = new JButton[1];
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        found[0] = findButton(container, text);
                    });
                } catch (Exception ignored) {
                }
                if (found[0] != null && found[0].isVisible()) {
                    return found[0];
                }
                Thread.sleep(100);
            }
            return null;
        }
        private static <T extends Window> T waitForWindow(Class<T> type, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (Window w : Window.getWindows()) {
                    if (w.getClass().getName().contains(type.getSimpleName()) && w.isVisible()) {
                        return type.cast(w);
                    }
                }
                Thread.sleep(100);
            }
            return null;
        }

        private static void captureScreenshot(Robot robot, Path file) {
            try {
                Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                BufferedImage image = robot.createScreenCapture(screenRect);
                ImageIO.write(image, "png", file.toFile());
            } catch (Exception ignored) {
            }
        }
        private static void printContainerHierarchy(Container container) {
            for (Component c : container.getComponents()) {
                if (c instanceof javax.swing.JLabel l) {
                    System.err.println("  LABEL: " + l.getText());
                } else if (c instanceof javax.swing.JTextArea ta) {
                    System.err.println("  TEXT: " + ta.getText());
                } else if (c instanceof Container sub) {
                    printContainerHierarchy(sub);
                }
            }
        }

        private static JButton findButton(Container container, String text) {
            for (Component c : container.getComponents()) {
                if (c instanceof JButton b && text.equals(b.getText())) {
                    return b;
                }
                if (c instanceof Container sub) {
                    JButton b = findButton(sub, text);
                    if (b != null) return b;
                }
            }
            return null;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Starting ClientUpdateSmoke ===");

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Error: ClientUpdateSmoke requires a non-headless display (-Djava.awt.headless=false)");
            System.exit(1);
        }

        try {
            runSmoke();
            System.out.println("\n=== ClientUpdateSmoke verification PASSED on " + System.getProperty("os.name") + " ===");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("\n=== ClientUpdateSmoke FAILED ===");
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runSmoke() throws Exception {
        Path tempDir = Files.createTempDirectory("p2p-update-smoke-");
        System.out.println("Working in external temp directory: " + tempDir.toAbsolutePath());
        Path screenshotDir = tempDir.resolve("screenshots");
        Files.createDirectories(screenshotDir);

        // 1. Generate ephemeral Ed25519 keypair
        System.out.println("Step 1: Generating ephemeral Ed25519 keypair...");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = kpg.generateKeyPair();
        String pubKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        // 2. Locate base shaded JAR
        Path baseJar = Path.of("peer-app/target/peer-app.jar");
        if (!Files.exists(baseJar)) {
            baseJar = Path.of("target/peer-app.jar");
        }
        if (!Files.exists(baseJar)) {
            throw new IOException("Base shaded JAR not found: " + baseJar.toAbsolutePath());
        }

        // 3. Build versioned fixture JARs (1.0.0 and 1.0.1)
        System.out.println("Step 2: Building signed fixture JARs (1.0.0 and 1.0.1)...");
        Path jar100 = tempDir.resolve("peer-app-1.0.0.jar");
        Path jar101 = tempDir.resolve("peer-app-1.0.1.jar");
        createFixtureJar(baseJar, jar100, "1.0.0", pubKeyBase64);
        createFixtureJar(baseJar, jar101, "1.0.1", pubKeyBase64);

        // Verify class entry hashes match exactly
        assertClassHashesEqual(baseJar, jar100);
        assertClassHashesEqual(baseJar, jar101);

        // 4. Create signed update manifest and bundle ZIP for 1.0.1
        System.out.println("Step 3: Creating signed release manifest for 1.0.1...");
        long jar101Size = Files.size(jar101);
        String jar101Sha = HashUtil.sha256(jar101);

        Path zip101 = tempDir.resolve("p2p-client-1.0.1.zip");
        createMinimalZip(jar101, zip101, "1.0.1");
        long zip101Size = Files.size(zip101);
        String zip101Sha = HashUtil.sha256(zip101);

        ReleaseManifest manifest101 = new ReleaseManifest(
                1,
                ReleaseClient.EXPECTED_REPOSITORY,
                ClientVersion.parse("1.0.1"),
                "peer-app.jar",
                jar101Size,
                jar101Sha,
                21,
                1,
                new ReleaseManifest.BundleInfo("p2p-client-1.0.1.zip", zip101Size, zip101Sha)
        );

        byte[] manifestBytes = manifest101.toJsonUtf8();
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(keyPair.getPrivate());
        sig.update(manifestBytes);
        byte[] sigBytes = sig.sign();

        // 5. Start fixture HTTP server
        System.out.println("Step 4: Starting fixture HTTP server...");
        byte[] jar101Bytes = Files.readAllBytes(jar101);
        byte[] zip101Bytes = Files.readAllBytes(zip101);

        String releaseJson = """
                {
                  "tag_name": "v1.0.1",
                  "draft": false,
                  "prerelease": false,
                  "body": "Smoke release notes v1.0.1",
                  "assets": [
                    {
                      "name": "peer-app.jar",
                      "size": %d,
                      "state": "uploaded",
                      "browser_download_url": "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/peer-app.jar"
                    },
                    {
                      "name": "p2p-client-1.0.1.zip",
                      "size": %d,
                      "state": "uploaded",
                      "browser_download_url": "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/p2p-client-1.0.1.zip"
                    },
                    {
                      "name": "update-manifest.json",
                      "size": %d,
                      "state": "uploaded",
                      "browser_download_url": "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/update-manifest.json"
                    },
                    {
                      "name": "update-manifest.sig",
                      "size": 64,
                      "state": "uploaded",
                      "browser_download_url": "https://github.com/Megumi2910/p2p-file-sharing/releases/download/v1.0.1/update-manifest.sig"
                    }
                  ]
                }
                """.formatted(jar101Size, zip101Size, manifestBytes.length);

        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/api/latest", exchange -> {
            byte[] b = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.github+json");
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(b); }
        });
        httpServer.createContext("/assets/peer-app.jar", exchange -> {
            exchange.sendResponseHeaders(200, jar101Bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(jar101Bytes); }
        });
        httpServer.createContext("/assets/update-manifest.json", exchange -> {
            exchange.sendResponseHeaders(200, manifestBytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(manifestBytes); }
        });
        httpServer.createContext("/assets/update-manifest.sig", exchange -> {
            exchange.sendResponseHeaders(200, sigBytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(sigBytes); }
        });
        httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        httpServer.start();
        int fixturePort = httpServer.getAddress().getPort();
        System.out.println("Fixture HTTP server listening on port " + fixturePort);

        // 6. Start TrackerServer
        System.out.println("Step 5: Starting TrackerServer...");
        TrackerServer tracker = new TrackerServer(0);
        Thread trackerThread = new Thread(() -> {
            try {
                tracker.start();
            } catch (Exception ex) {
            }
        }, "smoke-tracker");
        trackerThread.setDaemon(true);
        trackerThread.start();

        int trackerPort = 0;
        long trackerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < trackerDeadline) {
            try {
                trackerPort = tracker.localPort();
                if (trackerPort > 0) break;
            } catch (Exception e) {
                Thread.sleep(20);
            }
        }
        System.out.println("TrackerServer listening on port " + trackerPort);

        // 7. Setup canonical installation directory
        System.out.println("Step 6: Setting up canonical installation directory...");
        Path installRoot = tempDir.resolve("install").toAbsolutePath().normalize();
        Files.createDirectories(installRoot);

        Path canonicalJar = installRoot.resolve("peer-app.jar");
        Files.copy(jar100, canonicalJar, StandardCopyOption.REPLACE_EXISTING);

        Path peer1Downloads = installRoot.resolve("downloads");
        Path peer1Shared = installRoot.resolve("shared");
        Files.createDirectories(peer1Downloads);
        Files.createDirectories(peer1Shared);

        int peer1Port;
        try (ServerSocket s = new ServerSocket(0)) { peer1Port = s.getLocalPort(); }

        Path peer1Config = installRoot.resolve("peer1.properties");
        java.util.Properties p1 = new java.util.Properties();
        p1.setProperty("peer.id", "peer-smoke-1");
        p1.setProperty("peer.name", "Peer One");
        p1.setProperty("peer.port", String.valueOf(peer1Port));
        p1.setProperty("tracker.host", "127.0.0.1");
        p1.setProperty("tracker.port", String.valueOf(trackerPort));
        p1.setProperty("download.dir", peer1Downloads.toString());
        p1.setProperty("shared.dir", peer1Shared.toString());
        p1.setProperty("transfer.autoAccept", "true");
        p1.setProperty("updates.checkOnStartup", "false");
        try (OutputStream os = Files.newOutputStream(peer1Config)) { p1.store(os, null); }

        // Setup Peer 2 (sender)
        Path peer2Dir = tempDir.resolve("peer2").toAbsolutePath().normalize();
        Path peer2Downloads = peer2Dir.resolve("downloads");
        Path peer2Shared = peer2Dir.resolve("shared");
        Files.createDirectories(peer2Downloads);
        Files.createDirectories(peer2Shared);

        Path sampleFile = peer2Shared.resolve("transfer_smoke_test.txt");
        Files.writeString(sampleFile, "Post-update real P2P transfer test content 1234567890");
        String sampleSha256 = HashUtil.sha256(sampleFile);

        int peer2Port;
        try (ServerSocket s = new ServerSocket(0)) { peer2Port = s.getLocalPort(); }

        Path peer2Config = peer2Dir.resolve("peer2.properties");
        java.util.Properties p2 = new java.util.Properties();
        p2.setProperty("peer.id", "peer-smoke-2");
        p2.setProperty("peer.name", "Peer Two");
        p2.setProperty("peer.port", String.valueOf(peer2Port));
        p2.setProperty("tracker.host", "127.0.0.1");
        p2.setProperty("tracker.port", String.valueOf(trackerPort));
        p2.setProperty("download.dir", peer2Downloads.toString());
        p2.setProperty("shared.dir", peer2Shared.toString());
        p2.setProperty("transfer.autoAccept", "false");
        p2.setProperty("updates.checkOnStartup", "false");
        try (OutputStream os = Files.newOutputStream(peer2Config)) { p2.store(os, null); }

        AppConfig config2 = AppConfig.load(peer2Config);
        PeerRuntime runtime2 = new PeerRuntime(config2);
        runtime2.start();
        runtime2.refreshSharedFiles();

        // 8. Launch Peer 1 using PeerDriver subprocess
        System.out.println("Step 7: Launching Peer 1 driver process...");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        Path testClasses = Path.of("peer-app/target/test-classes").toAbsolutePath().normalize();
        String driverClasspath = canonicalJar.toString() + File.pathSeparator + testClasses.toString();
        ProcessBuilder p1Pb = new ProcessBuilder(
                javaBin,
                "-Djava.awt.headless=false",
                "-Dfile.encoding=UTF-8",
                "-cp", driverClasspath,
                PeerDriver.class.getName(),
                String.valueOf(fixturePort),
                peer1Config.toString(),
                screenshotDir.toString()
        ).directory(installRoot.toFile());
        p1Pb.redirectErrorStream(true);
        Process peer1Process = p1Pb.start();
        long originalPid = peer1Process.pid();
        System.out.println("Peer 1 driver running with PID: " + originalPid);

        // Pipe stdout/stderr from Peer 1
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(peer1Process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[Peer 1] " + line);
                }
            } catch (IOException ignored) {}
        }).start();

        // Wait for Peer 1 to trigger update, spawn helper, and exit
        System.out.println("Step 8: Supervising automated update and waiting for Peer 1 exit...");
        boolean peer1Exited = peer1Process.waitFor(60, TimeUnit.SECONDS);
        assertTrue("Original Peer 1 process must exit after handoff to helper", peer1Exited);
        System.out.println("Original Peer 1 exited cleanly.");

        // Wait for canonical peer-app.jar to be replaced with 1.0.1
        System.out.println("Step 9: Observing atomic binary replacement and child relaunch...");
        long swapDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        boolean swapped = false;
        while (System.nanoTime() < swapDeadline) {
            if (Files.exists(canonicalJar) && jar101Sha.equalsIgnoreCase(HashUtil.sha256(canonicalJar))) {
                swapped = true;
                break;
            }
            Thread.sleep(100);
        }
        if (!swapped) {
            Path updateDir = installRoot.resolve(".p2p-update");
            if (Files.exists(updateDir.resolve("helper.err.log"))) {
                System.err.println("HELPER ERR LOG:\n" + Files.readString(updateDir.resolve("helper.err.log")));
            }
            UpdateJournal j = UpdateJournal.read(updateDir);
            if (j != null) {
                System.err.println("JOURNAL AT TIMEOUT: phase=" + j.phase() + ", error=" + j.lastError());
            }
        }
        assertTrue("Canonical JAR must be atomically updated to 1.0.1 hash", swapped);
        System.out.println("Canonical peer-app.jar updated to 1.0.1: " + HashUtil.sha256(canonicalJar));

        // Wait for journal to show COMMITTED
        Path updateDir = installRoot.resolve(".p2p-update");
        long commitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        UpdateJournal committedJournal = null;
        while (System.nanoTime() < commitDeadline) {
            try {
                UpdateJournal j = UpdateJournal.read(updateDir);
                if (j != null && j.phase() == UpdateJournal.Phase.COMMITTED) {
                    committedJournal = j;
                    break;
                }
            } catch (Exception ignored) {}
            Thread.sleep(100);
        }
        assertNotNull("Journal must reach COMMITTED phase", committedJournal);
        assertEquals("Committed target version", "1.0.1", committedJournal.targetVersion());
        System.out.println("Journal reached COMMITTED state.");

        // Wait for restarted child process to register on Tracker
        System.out.println("Step 10: Verifying restarted child registration on Tracker...");
        long trackerRegDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        boolean registeredOnTracker = false;
        while (System.nanoTime() < trackerRegDeadline) {
            try {
                var peers = runtime2.listPeers();
                for (var p : peers) {
                    if ("peer-smoke-1".equals(p.peerId())) {
                        registeredOnTracker = true;
                        break;
                    }
                }
                if (registeredOnTracker) break;
            } catch (Exception ignored) {}
            Thread.sleep(200);
        }
        assertTrue("Restarted peer must register with Tracker", registeredOnTracker);
        System.out.println("Restarted peer 1 registered successfully with Tracker.");

        // 9. Real post-update P2P transfer to restarted Peer 1
        System.out.println("Step 11: Executing real post-update P2P transfer to restarted Peer 1...");
        PeerInfo targetPeer1 = new PeerInfo("peer-smoke-1", "Peer One", "127.0.0.1", peer1Port);
        runtime2.sendFile(targetPeer1, sampleFile);

        // Wait for file to arrive in Peer 1 download folder
        Path receivedFile = peer1Downloads.resolve("transfer_smoke_test.txt");
        long transferDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < transferDeadline) {
            if (Files.exists(receivedFile) && Files.size(receivedFile) == Files.size(sampleFile)) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue("Received file must exist in Peer 1 download directory", Files.exists(receivedFile));
        assertEquals("Received file hash must match original", sampleSha256, HashUtil.sha256(receivedFile));
        System.out.println("P2P transfer succeeded post-update!");

        // 10. Test negative rollback recovery
        System.out.println("Step 12: Testing negative rollback recovery with corrupted fixture...");
        // Previous jar exists from the update
        Path previousJar = updateDir.resolve("previous.jar");
        assertTrue("previous.jar must exist", Files.exists(previousJar));
        String previousJarSha = HashUtil.sha256(previousJar);

        // Stop restarted child before simulating crash/recovery
        if (committedJournal.childPid() != null) {
            ProcessHandle.of(committedJournal.childPid()).ifPresent(ph -> {
                ph.destroyForcibly();
                try {
                    ph.onExit().get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            });
        }
        Thread.sleep(500);

        // Simulate crash before committed by setting phase to SWITCHING and deleting canonical jar
        String recoveryTxId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        UpdateJournal brokenJournal = new UpdateJournal(
                1,
                ReleaseClient.EXPECTED_REPOSITORY,
                committedJournal.installId(),
                UpdateJournal.Operation.UPDATE,
                recoveryTxId,
                UpdateJournal.Phase.SWITCHING,
                99999999L, Instant.now(),
                99999998L, Instant.now(),
                null, null,
                peer1Config.toString(),
                installRoot.toString(),
                javaBin,
                "",
                "1.0.0",
                "1.0.1",
                previousJarSha,
                jar101Sha,
                ""
        );
        brokenJournal.writeAtomic(updateDir);
        Files.deleteIfExists(canonicalJar);

        UpdateInstaller.runRecovery(installRoot, null, null);

        assertTrue("Canonical JAR must be restored from previous.jar on recovery", Files.exists(canonicalJar));
        assertEquals("Restored JAR hash must match previous", previousJarSha, HashUtil.sha256(canonicalJar));
        UpdateJournal rolledBackJournal = UpdateJournal.read(updateDir);
        assertEquals("Journal must record ROLLED_BACK", UpdateJournal.Phase.ROLLED_BACK, rolledBackJournal.phase());
        System.out.println("Rollback recovery verified!");

        // 11. Cleanup
        System.out.println("Step 13: Cleaning up smoke test processes and servers...");
        if (committedJournal != null && committedJournal.childPid() != null) {
            ProcessHandle.of(committedJournal.childPid()).ifPresent(ProcessHandle::destroyForcibly);
        }
        runtime2.close();
        tracker.close();
        httpServer.stop(0);

        System.out.println("All smoke test assertions PASSED.");
    }

    private static void createFixtureJar(Path baseJar, Path outJar, String version, String pubKey) throws IOException {
        try (JarFile in = new JarFile(baseJar.toFile());
             JarOutputStream out = new JarOutputStream(new FileOutputStream(outJar.toFile()))) {

            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if ("vn/edu/p2p/peer/update/build.properties".equals(entry.getName())) {
                    continue;
                }
                out.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream is = in.getInputStream(entry)) {
                    is.transferTo(out);
                }
                out.closeEntry();
            }

            JarEntry bpEntry = new JarEntry("vn/edu/p2p/peer/update/build.properties");
            out.putNextEntry(bpEntry);
            String bpContent = "version=" + version + "\nrepository=" + ReleaseClient.EXPECTED_REPOSITORY + "\npublicKey=" + pubKey + "\ninstallerProtocol=1\n";
            out.write(bpContent.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void createMinimalZip(Path jar, Path outZip, String version) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outZip.toFile()))) {
            out.putNextEntry(new ZipEntry("p2p-client/"));
            out.closeEntry();

            out.putNextEntry(new ZipEntry("p2p-client/peer-app.jar"));
            Files.copy(jar, out);
            out.closeEntry();

            out.putNextEntry(new ZipEntry("p2p-client/run-peer-linux.sh"));
            out.write("#!/usr/bin/env bash\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            out.putNextEntry(new ZipEntry("p2p-client/run-peer-windows.bat"));
            out.write("@echo off\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            out.putNextEntry(new ZipEntry("p2p-client/peer.properties.example"));
            out.write("peer.id=example\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void assertClassHashesEqual(Path jar1, Path jar2) throws Exception {
        Map<String, String> classes1 = extractClassHashes(jar1);
        Map<String, String> classes2 = extractClassHashes(jar2);
        if (!classes1.equals(classes2)) {
            throw new AssertionError("Bytecode class entry hashes changed between fixture copies!");
        }
    }

    private static Map<String, String> extractClassHashes(Path jarPath) throws Exception {
        Map<String, String> map = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        byte[] bytes = in.readAllBytes();
                        map.put(entry.getName(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    }
                }
            }
        }
        return map;
    }

    private static void assertTrue(String msg, boolean condition) {
        if (!condition) {
            throw new AssertionError(msg);
        }
    }

    private static void assertFalse(String msg, boolean condition) {
        if (condition) {
            throw new AssertionError(msg);
        }
    }

    private static void assertNotNull(String msg, Object obj) {
        if (obj == null) {
            throw new AssertionError(msg);
        }
    }

    private static void assertEquals(String msg, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(msg + " - expected: " + expected + " but got: " + actual);
        }
    }
}
