package vn.edu.p2p.peer;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.ui.DesktopTheme;
import vn.edu.p2p.peer.ui.MainFrame;
import vn.edu.p2p.peer.ui.SettingsDialog;
import vn.edu.p2p.peer.ui.SwingIncomingFilePrompt;
import vn.edu.p2p.peer.ui.UpdateDialog;
import vn.edu.p2p.peer.update.BuildInfo;
import vn.edu.p2p.peer.update.ClientVersion;
import vn.edu.p2p.peer.update.ReleaseClient;
import vn.edu.p2p.peer.update.ReleaseFixtureClient;
import vn.edu.p2p.peer.update.RestartCoordinator;
import vn.edu.p2p.peer.update.UpdateService;
import vn.edu.p2p.peer.util.HashUtil;
import vn.edu.p2p.tracker.TrackerServer;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DesktopUiSmoke {

    public static void main(String[] args) {
        String scaleArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--scale".equals(args[i]) && i + 1 < args.length) {
                scaleArg = args[i + 1];
                break;
            }
        }

        if (scaleArg == null) {
            System.out.println("[DesktopUiSmoke] Running suite across scales: 1, 1.25, 1.5, 2");
            List<String> scales = List.of("1", "1.25", "1.5", "2");
            for (String scale : scales) {
                System.out.println("\n========================================================");
                System.out.println("[DesktopUiSmoke] Spawning JVM for scale: " + scale);
                System.out.println("========================================================");
                runProcessForScale(scale);
            }
            System.out.println("\n[DesktopUiSmoke] ALL 4 SCALES PASSED SUCCESSFULLY!");
            return;
        }

        System.setProperty("flatlaf.uiScale", scaleArg);

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("FATAL: DesktopUiSmoke requires a graphical environment.");
            System.exit(1);
        }

        try {
            executeSmoke(scaleArg);
            System.out.println("[DesktopUiSmoke] SUCCESS: Scale " + scaleArg + " passed all checks.");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("[DesktopUiSmoke] FAILURE at scale " + scaleArg + ": " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void runProcessForScale(String scale) {
        String javaHome = System.getProperty("java.home");
        String javaBin = Path.of(javaHome, "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        Path fixtureDir;
        try {
            fixtureDir = Files.createTempDirectory("smoke-fixture-scale-" + scale);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create fixture dir", e);
        }

        Path peerJar = Path.of("peer-app", "target", "peer-app.jar").toAbsolutePath();
        Path trackerJar = Path.of("tracker-server", "target", "tracker-server.jar").toAbsolutePath();
        String effectiveCp = classpath;
        if (Files.exists(peerJar) && Files.exists(trackerJar)) {
            try {
                Path copyPeer = fixtureDir.resolve("peer-app.jar");
                Path copyTracker = fixtureDir.resolve("tracker-server.jar");
                Files.copy(peerJar, copyPeer, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(trackerJar, copyTracker, StandardCopyOption.REPLACE_EXISTING);
                effectiveCp = copyPeer + File.pathSeparator + copyTracker + File.pathSeparator + classpath;
            } catch (IOException ignored) {}
        }

        List<String> command = List.of(
                javaBin,
                "-Djava.awt.headless=false",
                "-cp",
                effectiveCp,
                "vn.edu.p2p.peer.DesktopUiSmoke",
                "--scale",
                scale
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        try {
            Process proc = pb.start();
            boolean finished = proc.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IllegalStateException("DesktopUiSmoke timed out after 180 seconds at scale " + scale);
            }
            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException("DesktopUiSmoke failed with exit code " + exitCode + " at scale " + scale);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Subprocess execution failed for scale " + scale + ": " + ex.getMessage(), ex);
        }
    }

    private static void executeSmoke(String scale) throws Exception {
        System.out.println("[DesktopUiSmoke] Initializing test harness for scale " + scale + "...");

        Path smokeRoot = Files.createTempDirectory("desktop-smoke-scratch-scale-" + scale).toAbsolutePath().normalize();
        Path screenshotDir = Path.of("target", "smoke-screenshots", "scale-" + scale).toAbsolutePath().normalize();
        Files.createDirectories(screenshotDir);

        Path aliceHome = smokeRoot.resolve("alice");
        Path bobHome = smokeRoot.resolve("bob");
        Path charlieHome = smokeRoot.resolve("charlie");
        Path aliceShared = aliceHome.resolve("shared");
        Path aliceDown = aliceHome.resolve("downloads");
        Path bobShared = bobHome.resolve("shared");
        Path bobDown = bobHome.resolve("downloads");
        Path charlieShared = charlieHome.resolve("shared");
        Path charlieDown = charlieHome.resolve("downloads");

        Files.createDirectories(aliceShared);
        Files.createDirectories(aliceDown);
        Files.createDirectories(bobShared);
        Files.createDirectories(bobDown);
        Files.createDirectories(charlieShared);
        Files.createDirectories(charlieDown);

        // Start tracker
        AtomicReference<TrackerServer> trackerRef = new AtomicReference<>(new TrackerServer(0));
        Thread trackerThread = new Thread(() -> {
            try {
                TrackerServer t = trackerRef.get();
                if (t != null) t.start();
            } catch (IOException ignored) {}
        }, "smoke-tracker");
        trackerThread.setDaemon(true);
        trackerThread.start();

        long deadline = System.currentTimeMillis() + 5000;
        int trackerPort = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                TrackerServer t = trackerRef.get();
                if (t != null) trackerPort = t.localPort();
                if (trackerPort > 0) break;
            } catch (Exception ignored) {
                Thread.sleep(20);
            }
        }
        if (trackerPort <= 0) {
            throw new IllegalStateException("Tracker failed to bind port");
        }

        int alicePort;
        try (ServerSocket probe = new ServerSocket(0)) { alicePort = probe.getLocalPort(); }
        int bobPort;
        try (ServerSocket probe = new ServerSocket(0)) { bobPort = probe.getLocalPort(); }
        int charliePort;
        try (ServerSocket probe = new ServerSocket(0)) { charliePort = probe.getLocalPort(); }

        Path aliceConfigPath = aliceHome.resolve("peer.properties");
        Properties aliceProps = new Properties();
        aliceProps.setProperty("peer.id", "alice-smoke");
        aliceProps.setProperty("peer.name", "Alice Smoke");
        aliceProps.setProperty("peer.port", String.valueOf(alicePort));
        aliceProps.setProperty("tracker.host", "127.0.0.1");
        aliceProps.setProperty("tracker.port", String.valueOf(trackerPort));
        aliceProps.setProperty("download.dir", aliceDown.toString());
        aliceProps.setProperty("shared.dir", aliceShared.toString());
        aliceProps.setProperty("chunk.size.bytes", "16384");
        try (OutputStream out = Files.newOutputStream(aliceConfigPath)) {
            aliceProps.store(out, "Alice smoke properties");
        }

        // Bob secondary peer
        AppConfig configBob = new AppConfig(
                "bob-smoke", "Bob Secondary", bobPort, "127.0.0.1", trackerPort, bobDown, bobShared, 16384, false,
                15000, 15000, 120000, 135000, 300000, 4
        );
        PeerRuntime runtimeBob = new PeerRuntime(configBob);
        JFrame bobFrame = new JFrame("Bob Host");
        SwingIncomingFilePrompt bobPrompt = new SwingIncomingFilePrompt(bobFrame);
        runtimeBob.transferManager().setIncomingFilePrompt(bobPrompt);

        // Share a file from Bob
        Path sharedBobFile = bobShared.resolve("shared-catalogue-item.txt");
        Files.writeString(sharedBobFile, "This is a shared test file from Bob for search testing.\n");
        String bobFileId = HashUtil.sha256(sharedBobFile);

        // Charlie: third real peer with long Unicode name to verify row measurement
        AppConfig configCharlie = new AppConfig(
                "charlie-smoke", "Nguyễn Văn A 🌟 [VN-HCM - Test Long Display Name]", charliePort, "127.0.0.1", trackerPort,
                charlieDown, charlieShared, 16384, true,
                15000, 15000, 120000, 135000, 300000, 4
        );
        PeerRuntime runtimeCharlie = new PeerRuntime(configCharlie);

        runtimeBob.start();
        runtimeCharlie.start();

        Robot robot = new Robot();
        robot.setAutoDelay(40);

        // Launch Alice through PeerApplication.launch
        BuildInfo buildInfo = BuildInfo.load();
        ReleaseClient releaseClient = ReleaseFixtureClient.createDefault(buildInfo);
        PeerApplication.launch(new String[]{aliceConfigPath.toString()}, releaseClient);

        // Find Alice MainFrame
        long frameDeadline = System.currentTimeMillis() + 12000;
        MainFrame frame = null;
        while (System.currentTimeMillis() < frameDeadline) {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof MainFrame mf && mf.isShowing()) {
                    frame = mf;
                    break;
                }
            }
            if (frame != null) break;
            Thread.sleep(100);
        }
        if (frame == null) {
            throw new IllegalStateException("MainFrame was not displayed within deadline");
        }

        long startDeadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < startDeadline) {
            Boolean isStarting = (Boolean) getFieldValue(frame, "starting");
            if (isStarting != null && !isStarting) break;
            Thread.sleep(100);
        }

        PeerRuntime runtimeAlice = (PeerRuntime) getFieldValue(frame, "runtime");
        try {
            robot.waitForIdle();
            Thread.sleep(400);

            // 1. Initial Window Geometry & Visual Capture
            System.out.println("[DesktopUiSmoke] Verifying window geometry and bounds...");
            Dimension frameSize = frame.getSize();
            Dimension minSize = frame.getMinimumSize();
            System.out.println("  Window Size: " + frameSize.width + "x" + frameSize.height +
                    ", Minimum: " + minSize.width + "x" + minSize.height);

            if (minSize.width <= 0 || minSize.height <= 0) {
                throw new AssertionError("Invalid minimum size: " + minSize);
            }
            if (frameSize.width < minSize.width || frameSize.height < minSize.height) {
                throw new AssertionError("Initial frame size " + frameSize + " is smaller than minimum " + minSize);
            }

            captureScreenshot(robot, frame, screenshotDir.resolve("01-main-window-light.png"));

            // 2. Theme Switching: Light -> Dark -> Light
            System.out.println("[DesktopUiSmoke] Testing Light -> Dark -> Light theme switching...");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    DesktopTheme.setDark(true);
                } catch (Exception ex) {
                    throw new RuntimeException("setDark(true) failed", ex);
                }
            });
            robot.waitForIdle();
            Thread.sleep(200);
            if (!DesktopTheme.isDark()) {
                throw new AssertionError("DesktopTheme.isDark() must be true after setting dark theme");
            }
            captureScreenshot(robot, frame, screenshotDir.resolve("02-main-window-dark.png"));

            SwingUtilities.invokeAndWait(() -> {
                try {
                    DesktopTheme.setDark(false);
                } catch (Exception ex) {
                    throw new RuntimeException("setDark(false) failed", ex);
                }
            });
            robot.waitForIdle();
            Thread.sleep(200);
            if (DesktopTheme.isDark()) {
                throw new AssertionError("DesktopTheme.isDark() must be false after reverting to light theme");
            }

            // 3. Peer List Discovery (real registered peers Bob and Charlie)
            System.out.println("[DesktopUiSmoke] Testing peer list row metrics and text geometry...");
            JButton refreshBtn = (JButton) getFieldValue(frame, "refreshButton");
            JButton sendBtn = (JButton) getFieldValue(frame, "sendButton");
            @SuppressWarnings("unchecked")
            JList<PeerInfo> peerList = (JList<PeerInfo>) getFieldValue(frame, "peerList");
            @SuppressWarnings("unchecked")
            DefaultListModel<PeerInfo> peerModel = (DefaultListModel<PeerInfo>) getFieldValue(frame, "peerModel");

            clickComponent(robot, refreshBtn);
            robot.waitForIdle();

            long peerDeadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < peerDeadline && peerModel.size() < 2) {
                Thread.sleep(100);
            }
            if (peerModel.size() < 2) {
                throw new AssertionError("Both Bob and Charlie should be discovered by tracker; found: " + peerModel.size());
            }

            // Verify both list rows have non-overlapping text lines on EDT
            SwingUtilities.invokeAndWait(() -> {
                for (int i = 0; i < peerModel.size(); i++) {
                    Rectangle cellBounds = peerList.getCellBounds(i, i);
                    if (cellBounds == null) {
                        throw new AssertionError("Cell bounds for peer index " + i + " must not be null");
                    }
                    Component rendererComp = peerList.getCellRenderer()
                            .getListCellRendererComponent(peerList, peerModel.get(i), i, false, false);
                    rendererComp.setSize(cellBounds.width, cellBounds.height);
                    rendererComp.doLayout();

                    if (rendererComp instanceof Container container) {
                        JLabel nameLbl = findLabel(container, peerModel.get(i).displayName());
                        JLabel epLbl = findLabel(container, peerModel.get(i).host() + ":" + peerModel.get(i).port());
                        if (nameLbl != null && epLbl != null) {
                            Rectangle nameRect = nameLbl.getBounds();
                            Rectangle epRect = epLbl.getBounds();
                            System.out.println("  Peer [" + i + "] Name Rect: " + nameRect + " | Endpoint Rect: " + epRect);
                            if (nameRect.intersects(epRect)) {
                                throw new AssertionError("Name and endpoint labels overlap in peer row " + i);
                            }
                            if (nameRect.y + nameRect.height > epRect.y) {
                                throw new AssertionError("Name label bottom exceeds endpoint top in peer row " + i);
                            }
                        }
                    }
                }
            });

            // 4. File Transfer with Real Visible Prompt Acceptance
            System.out.println("[DesktopUiSmoke] Testing action state transitions and transfer with visible prompt...");
            SwingUtilities.invokeAndWait(peerList::clearSelection);
            robot.waitForIdle();
            if (sendBtn.isEnabled()) {
                throw new AssertionError("Send button must be disabled when no peer is selected");
            }

            // Select Bob
            int bobIdx = -1;
            PeerInfo bobInfo = null;
            for (int i = 0; i < peerModel.size(); i++) {
                if ("bob-smoke".equals(peerModel.get(i).peerId())) {
                    bobIdx = i;
                    bobInfo = peerModel.get(i);
                    break;
                }
            }
            if (bobInfo == null) {
                throw new AssertionError("Bob peer not found in peer model");
            }
            final int finalBobIdx = bobIdx;
            SwingUtilities.invokeAndWait(() -> peerList.setSelectedIndex(finalBobIdx));
            robot.waitForIdle();
            if (!sendBtn.isEnabled()) {
                throw new AssertionError("Send button must be enabled when a peer is selected");
            }

            Path testFile = aliceShared.resolve("sample-transfer.dat");
            byte[] fileData = new byte[45000];
            for (int i = 0; i < fileData.length; i++) {
                fileData[i] = (byte) (i % 251);
            }
            Files.write(testFile, fileData);
            String expectedHash = HashUtil.sha256(testFile);

            // Supervisor thread to accept the real visible prompt dialog for Bob
            AtomicBoolean acceptHandled = new AtomicBoolean(false);
            Thread promptAcceptor = new Thread(() -> {
                try {
                    long pDeadline = System.currentTimeMillis() + 8000;
                    while (System.currentTimeMillis() < pDeadline) {
                        for (Window w : Window.getWindows()) {
                            if (w instanceof JDialog d && d.isShowing() && "Incoming file".equals(d.getTitle())) {
                                System.out.println("  [promptAcceptor] Found dialog: " + d.getTitle());
                                JButton acceptBtn = findButton(d, "Accept");
                                if (acceptBtn != null && acceptBtn.isShowing()) {
                                    SwingUtilities.invokeAndWait(() -> {
                                        d.toFront();
                                        d.requestFocus();
                                        acceptBtn.requestFocusInWindow();
                                    });
                                    Thread.sleep(100);
                                    clickComponent(robot, acceptBtn);
                                    robot.waitForIdle();
                                    if (acceptBtn.isShowing()) {
                                        robot.keyPress(KeyEvent.VK_SPACE);
                                        robot.delay(40);
                                        robot.keyRelease(KeyEvent.VK_SPACE);
                                    }
                                    acceptHandled.set(true);
                                    return;
                                }
                            }
                        }
                        Thread.sleep(50);
                    }
                    System.out.println("  [promptAcceptor] Timed out waiting for dialog");
                } catch (Exception ex) {
                    System.err.println("  [promptAcceptor] Error: " + ex.getMessage());
                }
            }, "prompt-acceptor");
            promptAcceptor.start();

            runtimeAlice.sendFile(bobInfo, testFile);

            long transferDeadline = System.currentTimeMillis() + 15000;
            boolean completed = false;
            while (System.currentTimeMillis() < transferDeadline) {
                JTable transferTable = (JTable) getFieldValue(frame, "transfersTable");
                int rows = transferTable.getRowCount();
                if (rows > 0) {
                    Object statusObj = transferTable.getValueAt(0, 6);
                    System.out.println("  [transfer loop] row 0 status: " + statusObj);
                    if (statusObj != null && statusObj.toString().toLowerCase().contains("completed")) {
                        completed = true;
                        break;
                    }
                }
                Thread.sleep(100);
            }
            if (!completed) {
                throw new AssertionError("Transfer did not reach Completed status within 15 seconds");
            }
            System.out.println("  Transfer completed successfully!");

            Path receivedAtBob = bobDown.resolve("sample-transfer.dat");
            if (!Files.exists(receivedAtBob)) {
                throw new AssertionError("Transferred file does not exist at Bob: " + receivedAtBob);
            }
            String actualHash = HashUtil.sha256(receivedAtBob);
            if (!expectedHash.equals(actualHash)) {
                throw new AssertionError("SHA-256 mismatch: expected " + expectedHash + ", got " + actualHash);
            }
            System.out.println("  Verified SHA-256 matches: " + actualHash);

            // 5. Test Rejected Offer with Escape key
            System.out.println("[DesktopUiSmoke] Testing rejected file offer via Escape key...");
            Path rejectFile = aliceShared.resolve("reject-me.txt");
            Files.writeString(rejectFile, "reject this content");

            Thread promptRejector = new Thread(() -> {
                try {
                    long pDeadline = System.currentTimeMillis() + 8000;
                    while (System.currentTimeMillis() < pDeadline) {
                        for (Window w : Window.getWindows()) {
                            if (w instanceof JDialog d && d.isShowing() && "Incoming file".equals(d.getTitle())) {
                                Thread.sleep(100);
                                robot.keyPress(KeyEvent.VK_ESCAPE);
                                robot.delay(40);
                                robot.keyRelease(KeyEvent.VK_ESCAPE);
                                return;
                            }
                        }
                        Thread.sleep(50);
                    }
                } catch (Exception ignored) {}
            }, "prompt-rejector");
            promptRejector.start();

            runtimeAlice.sendFile(bobInfo, rejectFile);

            long rejectDeadline = System.currentTimeMillis() + 10000;
            boolean rejectedObserved = false;
            while (System.currentTimeMillis() < rejectDeadline) {
                JTable transferTable = (JTable) getFieldValue(frame, "transfersTable");
                int rows = transferTable.getRowCount();
                for (int r = 0; r < rows; r++) {
                    Object statusObj = transferTable.getValueAt(r, 6);
                    if (statusObj != null && statusObj.toString().toLowerCase().contains("rejected")) {
                        rejectedObserved = true;
                        break;
                    }
                }
                if (rejectedObserved) break;
                Thread.sleep(100);
            }
            if (!rejectedObserved) {
                throw new AssertionError("Rejected transfer was not observed in transfer table");
            }
            System.out.println("  Rejected transfer observed correctly.");

            // 6. Catalogue Search & Local Sharing Strip
            System.out.println("[DesktopUiSmoke] Testing Catalogue Search tab and Local Sharing Strip...");
            JTabbedPane tabs = (JTabbedPane) getFieldValue(frame, "tabbedPane");
            SwingUtilities.invokeAndWait(() -> tabs.setSelectedIndex(1));
            robot.waitForIdle();
            Thread.sleep(200);

            JButton rescanBtn = (JButton) getFieldValue(frame, "rescanButton");
            JTextField searchField = (JTextField) getFieldValue(frame, "searchField");
            JButton searchBtn = (JButton) getFieldValue(frame, "searchButton");
            JButton downloadBtn = (JButton) getFieldValue(frame, "downloadButton");
            JTable searchTable = (JTable) getFieldValue(frame, "searchTable");

            // Click Rescan shared folder and verify outcome
            clickComponent(robot, rescanBtn);
            robot.waitForIdle();
            JTextArea sharingStatus = (JTextArea) getFieldValue(frame, "sharingStatusArea");
            long rescanDeadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < rescanDeadline) {
                if (sharingStatus.getText().contains("up to date")) break;
                Thread.sleep(50);
            }
            if (!sharingStatus.getText().contains("up to date")) {
                throw new AssertionError("Rescan did not reach up to date status: " + sharingStatus.getText());
            }
            // Search catalogue
            SwingUtilities.invokeAndWait(() -> {
                searchField.requestFocusInWindow();
                searchField.setText("shared-catalogue-item.txt");
            });
            robot.waitForIdle();
            clickComponent(robot, searchBtn);
            robot.waitForIdle();

            long searchDeadline = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < searchDeadline && searchTable.getRowCount() == 0) {
                Thread.sleep(100);
            }
            if (searchTable.getRowCount() == 0) {
                throw new AssertionError("Catalogue search returned 0 results for shared-catalogue-item.txt");
            }

            // Select search result row
            SwingUtilities.invokeAndWait(() -> searchTable.setRowSelectionInterval(0, 0));
            robot.waitForIdle();
            Thread.sleep(200);

            JTextArea searchDetail = (JTextArea) getFieldValue(frame, "searchDetailText");
            String detailText = searchDetail.getText();
            if (!detailText.contains("shared-catalogue-item.txt") || !detailText.contains(bobFileId)) {
                throw new AssertionError("Search details missing expected filename or SHA-256 hash");
            }
            if (!downloadBtn.isEnabled()) {
                throw new AssertionError("Download Selected button should be enabled for selected search result");
            }

            // Click Download Selected and verify download completes
            clickComponent(robot, downloadBtn);
            robot.waitForIdle();
            SwingUtilities.invokeAndWait(() -> tabs.setSelectedIndex(0));
            robot.waitForIdle();
            long dlDeadline = System.currentTimeMillis() + 15000;
            boolean dlCompleted = false;
            while (System.currentTimeMillis() < dlDeadline) {
                JTable transferTable = (JTable) getFieldValue(frame, "transfersTable");
                int rows = transferTable.getRowCount();
                for (int r = 0; r < rows; r++) {
                    Object fileObj = transferTable.getValueAt(r, 1);
                    Object statusObj = transferTable.getValueAt(r, 6);
                    if (fileObj != null && fileObj.toString().contains("shared-catalogue-item.txt")
                            && statusObj != null && statusObj.toString().toLowerCase().contains("completed")) {
                        dlCompleted = true;
                        break;
                    }
                }
                if (dlCompleted) break;
                Thread.sleep(100);
            }
            if (!dlCompleted) {
                throw new AssertionError("Download Selected did not reach Completed status within 15 seconds");
            }
            Path downloadedFile = aliceDown.resolve("shared-catalogue-item.txt");
            if (!Files.exists(downloadedFile)) {
                throw new AssertionError("Downloaded file missing at Alice: " + downloadedFile);
            }
            if (!bobFileId.equals(HashUtil.sha256(downloadedFile))) {
                throw new AssertionError("Downloaded file SHA-256 mismatch");
            }
            System.out.println("  Verified download from Catalogue Search completed with SHA-256 match!");

            // 7. Activity Log
            System.out.println("[DesktopUiSmoke] Testing Activity Log tab...");
            SwingUtilities.invokeAndWait(() -> tabs.setSelectedIndex(2));
            robot.waitForIdle();
            Thread.sleep(150);

            JTextArea logArea = (JTextArea) getFieldValue(frame, "logArea");
            JButton clearLogBtn = (JButton) getFieldValue(frame, "clearLogBtn");
            if (logArea.getText().isBlank()) {
                throw new AssertionError("Activity Log must contain transfer events");
            }
            clickComponent(robot, clearLogBtn);
            robot.waitForIdle();
            Thread.sleep(150);
            if (!logArea.getText().isEmpty()) {
                System.out.println("  [debug] logArea content after click: '" + logArea.getText() + "'");
                clickComponent(robot, clearLogBtn);
                robot.waitForIdle();
                Thread.sleep(150);
            }
            if (!logArea.getText().isEmpty()) {
                throw new AssertionError("Activity Log must be empty after clicking Clear Log. Found: " + logArea.getText());
            }

            // 8. Settings Dialog with Unsaved Settings Guard
            System.out.println("[DesktopUiSmoke] Verifying SettingsDialog geometry, advanced section, and discard guard...");
            JButton settingsBtn = (JButton) getFieldValue(frame, "settingsButton");
            clickComponent(robot, settingsBtn);
            robot.waitForIdle();
            Thread.sleep(300);

            SettingsDialog settingsDialog = (SettingsDialog) getFieldValue(frame, "settingsDialog");
            if (settingsDialog == null || !settingsDialog.isShowing()) {
                throw new AssertionError("SettingsDialog was not displayed after clicking settings button");
            }
            captureScreenshot(robot, settingsDialog, screenshotDir.resolve("03-settings-dialog.png"));

            JButton toggleAdv = (JButton) getFieldValue(settingsDialog, "toggleAdvancedBtn");
            JPanel advPanel = (JPanel) getFieldValue(settingsDialog, "advancedPanel");
            if (advPanel.isVisible()) {
                throw new AssertionError("Advanced panel must be initially collapsed");
            }
            clickComponent(robot, toggleAdv);
            robot.waitForIdle();
            Thread.sleep(150);
            if (!advPanel.isVisible()) {
                throw new AssertionError("Advanced panel must be visible after clicking toggle");
            }

            // Edit field to create unsaved changes
            JTextField nameField = (JTextField) getFieldValue(settingsDialog, "displayNameField");
            SwingUtilities.invokeAndWait(() -> nameField.setText("Alice Modified Name"));
            robot.waitForIdle();

            // Press Escape: triggers Unsaved settings confirmation dialog
            AtomicBoolean keepEditingObserved = new AtomicBoolean(false);
            Thread dismissPromptThread = new Thread(() -> {
                try {
                    long dDeadline = System.currentTimeMillis() + 4000;
                    while (System.currentTimeMillis() < dDeadline) {
                        for (Window w : Window.getWindows()) {
                            if (w instanceof JDialog d && d.isShowing() && "Unsaved settings".equals(d.getTitle())) {
                                keepEditingObserved.set(true);
                                robot.keyPress(KeyEvent.VK_ENTER);
                                robot.delay(40);
                                robot.keyRelease(KeyEvent.VK_ENTER);
                                return;
                            }
                        }
                        Thread.sleep(50);
                    }
                } catch (Exception ignored) {}
            });
            dismissPromptThread.start();

            robot.keyPress(KeyEvent.VK_ESCAPE);
            robot.delay(50);
            robot.keyRelease(KeyEvent.VK_ESCAPE);
            robot.waitForIdle();
            dismissPromptThread.join(4000);

            if (!keepEditingObserved.get()) {
                throw new AssertionError("Unsaved settings confirmation was not observed on Escape");
            }
            if (!settingsDialog.isShowing()) {
                throw new AssertionError("SettingsDialog must remain visible when user chooses Keep editing");
            }
            if (!"Alice Modified Name".equals(nameField.getText())) {
                throw new AssertionError("Draft edits must be preserved after Keep editing");
            }

            // Now close with Discard changes
            AtomicBoolean discardObserved = new AtomicBoolean(false);
            Thread discardPromptThread = new Thread(() -> {
                try {
                    long dDeadline = System.currentTimeMillis() + 4000;
                    while (System.currentTimeMillis() < dDeadline) {
                        for (Window w : Window.getWindows()) {
                            if (w instanceof JDialog d && d.isShowing() && "Unsaved settings".equals(d.getTitle())) {
                                JButton discardBtn = findButton(d, "Discard changes");
                                if (discardBtn != null && discardBtn.isShowing()) {
                                    clickComponent(robot, discardBtn);
                                    discardObserved.set(true);
                                    return;
                                }
                            }
                        }
                        Thread.sleep(50);
                    }
                } catch (Exception ignored) {}
            });
            discardPromptThread.start();

            JButton closeBtn = (JButton) getFieldValue(settingsDialog, "closeButton");
            clickComponent(robot, closeBtn);
            robot.waitForIdle();
            discardPromptThread.join(4000);

            if (!discardObserved.get()) {
                throw new AssertionError("Unsaved settings confirmation was not observed on Close button");
            }
            if (settingsDialog.isShowing()) {
                throw new AssertionError("SettingsDialog must be closed after choosing Discard changes");
            }

            // 9. Software Updates Dialog & Complete Presentation States (11 states)
            System.out.println("[DesktopUiSmoke] Verifying UpdateDialog all 11 presentation states...");
            JButton updateBtn = (JButton) getFieldValue(frame, "updateButton");
            clickComponent(robot, updateBtn);
            robot.waitForIdle();
            Thread.sleep(300);

            UpdateDialog updateDialog = (UpdateDialog) getFieldValue(frame, "updateDialog");
            if (updateDialog == null || !updateDialog.isShowing()) {
                throw new AssertionError("UpdateDialog was not displayed after clicking update button");
            }
            captureScreenshot(robot, updateDialog, screenshotDir.resolve("04-update-dialog.png"));

            Method applySnapshotMethod = UpdateDialog.class.getDeclaredMethod("applySnapshot", UpdateService.UpdateSnapshot.class);
            applySnapshotMethod.setAccessible(true);

            List<UpdateService.UpdateState> all11States = Arrays.asList(UpdateService.UpdateState.values());
            ClientVersion v1 = ClientVersion.parse("1.2.0");
            ClientVersion v2 = ClientVersion.parse("1.3.0");

            for (UpdateService.UpdateState st : all11States) {
                UpdateService.UpdateSnapshot snap = new UpdateService.UpdateSnapshot(
                        st, v1, v2, 524288L, 1048576L,
                        "Detailed status description for state " + st + " verifying text wrapping and metrics across all display scales.",
                        "Release notes text.", null, null
                );
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        applySnapshotMethod.invoke(updateDialog, snap);
                        JLabel statusLbl = (JLabel) getFieldValue(updateDialog, "statusLabel");
                        JTextArea detailArea = (JTextArea) getFieldValue(updateDialog, "detailLabel");
                        if (statusLbl.getText().isBlank() || detailArea.getText().isBlank()) {
                            throw new AssertionError("Status or detail empty for state " + st);
                        }
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
                robot.waitForIdle();
                if (st == UpdateService.UpdateState.UPDATE_AVAILABLE) {
                    captureScreenshot(robot, updateDialog, screenshotDir.resolve("04-update-dialog-available.png"));
                } else if (st == UpdateService.UpdateState.READY_TO_RESTART) {
                    captureScreenshot(robot, updateDialog, screenshotDir.resolve("04-update-dialog-restart.png"));
                }
            }

            // Close UpdateDialog
            robot.keyPress(KeyEvent.VK_ESCAPE);
            robot.delay(50);
            robot.keyRelease(KeyEvent.VK_ESCAPE);
            robot.waitForIdle();
            Thread.sleep(200);

            // 10. Tracker Outage & Offline Peer Resilience
            System.out.println("[DesktopUiSmoke] Verifying Tracker Outage resilience and recovery...");
            TrackerServer t = trackerRef.getAndSet(null);
            if (t != null) t.close();

            long outageDeadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < outageDeadline) {
                if (runtimeAlice.snapshot().trackerState() == PeerRuntime.TrackerState.OFFLINE) {
                    break;
                }
                Thread.sleep(100);
            }
            robot.waitForIdle();
            Thread.sleep(300);

            captureScreenshot(robot, frame, screenshotDir.resolve("06-main-window-offline.png"));

            JTextArea peerStatus = (JTextArea) getFieldValue(frame, "peerStatusArea");
            String peerStatusText = peerStatus.getText();
            if (!peerStatusText.contains("Tracker unavailable")) {
                throw new AssertionError("Peer status area must show tracker unavailable wording during outage: " + peerStatusText);
            }

            // Cached peers survive
            if (peerModel.isEmpty()) {
                throw new AssertionError("Cached peers must remain visible in peer list during outage");
            }

            System.out.println("[DesktopUiSmoke] All UI journeys verified successfully at scale " + scale + ".");
        } finally {
            SwingUtilities.invokeLater(() -> {
                for (Window w : Window.getWindows()) {
                    w.dispose();
                }
            });
            try { runtimeAlice.close(); } catch (Exception ignored) {}
            try { runtimeBob.close(); } catch (Exception ignored) {}
            try { runtimeCharlie.close(); } catch (Exception ignored) {}
            TrackerServer t2 = trackerRef.getAndSet(null);
            if (t2 != null) {
                try { t2.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void clickComponent(Robot robot, Component comp) throws Exception {
        Point[] centerOnScreen = new Point[1];
        SwingUtilities.invokeAndWait(() -> {
            Window w = SwingUtilities.getWindowAncestor(comp);
            if (w != null) {
                w.toFront();
                w.requestFocus();
            }
            if (comp instanceof JComponent jc) {
                jc.scrollRectToVisible(new Rectangle(0, 0, Math.max(1, jc.getWidth()), Math.max(1, jc.getHeight())));
            }
            if (!comp.isShowing()) {
                throw new IllegalStateException("Component is not showing on screen: " + comp);
            }
            comp.requestFocusInWindow();
            Point loc = comp.getLocationOnScreen();
            Dimension size = comp.getSize();
            centerOnScreen[0] = new Point(loc.x + size.width / 2, loc.y + size.height / 2);
        });

        Point pt = centerOnScreen[0];
        robot.mouseMove(pt.x, pt.y);
        robot.delay(80);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(80);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    private static JButton findButton(Container container, String textSubstring) {
        for (Component c : container.getComponents()) {
            if (c instanceof JButton b && b.getText() != null && b.getText().contains(textSubstring)) {
                return b;
            } else if (c instanceof Container sub) {
                JButton found = findButton(sub, textSubstring);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel findLabel(Container container, String textSubstring) {
        for (Component c : container.getComponents()) {
            if (c instanceof JLabel l) {
                if (l.getText() != null && l.getText().contains(textSubstring)) {
                    return l;
                }
            } else if (c instanceof Container sub) {
                JLabel found = findLabel(sub, textSubstring);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void captureScreenshot(Robot robot, Window window, Path destination) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                window.toFront();
                window.requestFocus();
            });
            Thread.sleep(150);
            Rectangle bounds = window.getBounds();
            BufferedImage image = robot.createScreenCapture(bounds);
            ImageIO.write(image, "png", destination.toFile());
            System.out.println("  Captured on-screen screenshot: " + destination.getFileName());

            BufferedImage compImage = new BufferedImage(Math.max(1, window.getWidth()), Math.max(1, window.getHeight()), BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = compImage.createGraphics();
            try {
                window.printAll(g2);
            } finally {
                g2.dispose();
            }
            String compName = destination.getFileName().toString().replace(".png", "-component.png");
            ImageIO.write(compImage, "png", destination.getParent().resolve(compName).toFile());
        } catch (Exception ex) {
            System.err.println("  Warning: could not capture screenshot to " + destination + ": " + ex.getMessage());
        }
    }

    private static Object getFieldValue(Object obj, String fieldName) throws Exception {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found on " + obj.getClass());
    }
}
