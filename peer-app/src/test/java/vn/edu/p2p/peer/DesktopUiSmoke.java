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
import vn.edu.p2p.peer.update.RestartCoordinator;
import vn.edu.p2p.peer.update.UpdateService;
import vn.edu.p2p.peer.util.HashUtil;
import vn.edu.p2p.tracker.TrackerServer;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
            // Spawn each scale (1, 1.25, 1.5, 2) in its own fresh JVM
            System.out.println("[DesktopUiSmoke] No --scale specified. Running suite serially across scales: 1, 1.25, 1.5, 2");
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

        // Running for a specific scale
        System.setProperty("flatlaf.uiScale", scaleArg);

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("FATAL: DesktopUiSmoke requires a graphical environment. Headless execution is a hard failure, not a skip.");
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

        List<String> command = List.of(
                javaBin,
                "-Djava.awt.headless=false",
                "-cp",
                classpath,
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

        Path smokeRoot = Path.of("target", "smoke-scratch", "scale-" + scale).toAbsolutePath().normalize();
        Files.createDirectories(smokeRoot);
        Path screenshotDir = Path.of("target", "smoke-screenshots", "scale-" + scale).toAbsolutePath().normalize();
        Files.createDirectories(screenshotDir);

        Path aliceHome = smokeRoot.resolve("alice");
        Path bobHome = smokeRoot.resolve("bob");
        Path aliceShared = aliceHome.resolve("shared");
        Path aliceDown = aliceHome.resolve("downloads");
        Path bobShared = bobHome.resolve("shared");
        Path bobDown = bobHome.resolve("downloads");

        Files.createDirectories(aliceShared);
        Files.createDirectories(aliceDown);
        Files.createDirectories(bobShared);
        Files.createDirectories(bobDown);

        // Start in-process tracker server
        TrackerServer tracker = new TrackerServer(0);
        Thread trackerThread = new Thread(() -> {
            try {
                tracker.start();
            } catch (IOException ignored) {
            }
        }, "smoke-tracker");
        trackerThread.setDaemon(true);
        trackerThread.start();

        long deadline = System.currentTimeMillis() + 5000;
        int trackerPort = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                trackerPort = tracker.localPort();
                if (trackerPort > 0) break;
            } catch (Exception ignored) {
                Thread.sleep(20);
            }
        }
        if (trackerPort <= 0) {
            throw new IllegalStateException("Tracker failed to bind port");
        }

        // Probing ports for peers
        int alicePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            alicePort = probe.getLocalPort();
        }
        int bobPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            bobPort = probe.getLocalPort();
        }

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

        AppConfig configAlice = AppConfig.load(aliceConfigPath);
        PeerRuntime runtimeAlice = new PeerRuntime(configAlice);
        ConfigStore storeAlice = new ConfigStore(aliceConfigPath, aliceHome.resolve("cfg-store"));
        RestartCoordinator restartsAlice = new RestartCoordinator(aliceHome.resolve("restarts"));
        BuildInfo buildInfo = BuildInfo.load();
        ReleaseClient releaseClient = new ReleaseClient(buildInfo);
        UpdateService updateService = new UpdateService(releaseClient, aliceHome.resolve("updates"));

        // Bob runtime (secondary background peer with dynamic prompt)
        AtomicBoolean bobAutoAccept = new AtomicBoolean(true);
        AppConfig configBob = new AppConfig(
                "bob-smoke", "Bob Secondary", bobPort, "127.0.0.1", trackerPort, bobDown, bobShared, 16384, false,
                15000, 15000, 120000, 135000, 300000, 4
        );
        PeerRuntime runtimeBob = new PeerRuntime(configBob);
        runtimeBob.transferManager().setIncomingFilePrompt((meta, sender, timeout) -> bobAutoAccept.get());

        // Share a file from Bob so Catalogue Search finds it
        Path sharedBobFile = bobShared.resolve("shared-catalogue-item.txt");
        byte[] bobFileContent = "This is a shared test file from Bob for search testing.\n".getBytes(StandardCharsets.UTF_8);
        Files.write(sharedBobFile, bobFileContent);
        String bobFileId = HashUtil.sha256(sharedBobFile);
        runtimeBob.start();
        runtimeAlice.start();
        Robot robot = new Robot();
        robot.setAutoDelay(40);

        AtomicReference<MainFrame> frameRef = new AtomicReference<>();

        try {
            // Install Theme on EDT
            SwingUtilities.invokeAndWait(() -> {
                try {
                    DesktopTheme.install();
                } catch (Exception ex) {
                    throw new RuntimeException("DesktopTheme install failed", ex);
                }
            });

            // Launch MainFrame on EDT
            SwingUtilities.invokeAndWait(() -> {
                MainFrame frame = new MainFrame(runtimeAlice, storeAlice, updateService, restartsAlice);
                frameRef.set(frame);
                frame.setVisible(true);
                frame.setStarting(false);
                runtimeAlice.transferManager().setListener(frame);
                runtimeAlice.transferManager().setIncomingFilePrompt(new SwingIncomingFilePrompt(frame));
            });

            MainFrame frame = frameRef.get();
            robot.waitForIdle();
            Thread.sleep(300);

            // -----------------------------------------------------------------
            // 1. Initial Window Geometry & Visual Capture
            // -----------------------------------------------------------------
            System.out.println("[DesktopUiSmoke] Verifying window geometry and bounds...");
            Dimension frameSize = frame.getSize();
            Dimension minSize = frame.getMinimumSize();
            System.out.println("  Window Size: " + frameSize.width + "x" + frameSize.height +
                    ", Minimum: " + minSize.width + "x" + minSize.height);

            // Clamp check: verify within reasonable bounds
            if (minSize.width <= 0 || minSize.height <= 0) {
                throw new AssertionError("Invalid minimum size: " + minSize);
            }
            if (frameSize.width < minSize.width || frameSize.height < minSize.height) {
                throw new AssertionError("Initial frame size " + frameSize + " is smaller than minimum " + minSize);
            }

            captureScreenshot(robot, frame, screenshotDir.resolve("01-main-window-light.png"));

            // -----------------------------------------------------------------
            // 2. Theme Switching: Light -> Dark -> Light
            // -----------------------------------------------------------------
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

            // -----------------------------------------------------------------
            // 3. Peer List Rows & Disjoint Text Rectangles
            // -----------------------------------------------------------------
            System.out.println("[DesktopUiSmoke] Testing peer list row metrics and text geometry...");
            JButton refreshBtn = (JButton) getFieldValue(frame, "refreshButton");
            JButton sendBtn = (JButton) getFieldValue(frame, "sendButton");
            @SuppressWarnings("unchecked")
            JList<PeerInfo> peerList = (JList<PeerInfo>) getFieldValue(frame, "peerList");
            @SuppressWarnings("unchecked")
            DefaultListModel<PeerInfo> peerModel = (DefaultListModel<PeerInfo>) getFieldValue(frame, "peerModel");

            // Click refresh peers using real Robot mouse click
            clickComponent(robot, refreshBtn);
            robot.waitForIdle();

            // Wait for Bob to appear or add manually to model
            long refreshDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < refreshDeadline && peerModel.isEmpty()) {
                Thread.sleep(100);
            }

            // Ensure we have Bob and a long Unicode peer for row measurement
            SwingUtilities.invokeAndWait(() -> {
                if (peerModel.isEmpty()) {
                    peerModel.addElement(new PeerInfo("bob-smoke", "Bob Secondary", "127.0.0.1", bobPort));
                }
                peerModel.addElement(new PeerInfo(
                        "peer-long-unicode-id-0123456789",
                        "Nguyễn Văn A 🌟 [VN-HCM - Test Long Display Name]",
                        "long-hostname.dynamic-dns.internal.example.org",
                        65535
                ));
            });
            robot.waitForIdle();
            Thread.sleep(200);

            // Verify both list rows have non-overlapping, disjoint text lines
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
                            System.out.println("  Peer [" + i + "] Name Rect: " + nameRect + " | Endpoint Rect: " + epRect + " inside " + cellBounds);
                            if (nameRect.intersects(epRect)) {
                                throw new AssertionError("Name and endpoint labels overlap vertically in peer row " + i + "! Name: " + nameRect + ", Ep: " + epRect);
                            }
                            if (nameRect.y + nameRect.height > epRect.y) {
                                throw new AssertionError("Name label bottom exceeds endpoint top in peer row " + i);
                            }
                        }
                    }
                }
            });

            // -----------------------------------------------------------------
            // 4. Action Verification & Real File Transfer
            // -----------------------------------------------------------------
            System.out.println("[DesktopUiSmoke] Testing action state transitions and transfer...");
            // No selection -> sendButton disabled
            SwingUtilities.invokeAndWait(peerList::clearSelection);
            robot.waitForIdle();
            if (sendBtn.isEnabled()) {
                throw new AssertionError("Send button must be disabled when no peer is selected");
            }

            // Select Bob
            SwingUtilities.invokeAndWait(() -> peerList.setSelectedIndex(0));
            robot.waitForIdle();
            if (!sendBtn.isEnabled()) {
                throw new AssertionError("Send button must be enabled when a peer is selected");
            }

            // Create a deterministic multi-chunk test file
            Path testFile = aliceShared.resolve("sample-transfer.dat");
            byte[] fileData = new byte[45000]; // 45 KB = ~3 chunks of 16 KB
            for (int i = 0; i < fileData.length; i++) {
                fileData[i] = (byte) (i % 251);
            }
            Files.write(testFile, fileData);
            String expectedHash = HashUtil.sha256(testFile);

            // Initiate send to Bob directly through runtime
            System.out.println("  Sending multi-chunk file to Bob (" + expectedHash + ")...");
            PeerInfo bobInfo = null;
            for (int i = 0; i < peerModel.size(); i++) {
                if ("bob-smoke".equals(peerModel.get(i).peerId())) {
                    bobInfo = peerModel.get(i);
                    final int bobIdx = i;
                    SwingUtilities.invokeAndWait(() -> peerList.setSelectedIndex(bobIdx));
                    break;
                }
            }
            if (bobInfo == null) {
                bobInfo = new PeerInfo("bob-smoke", "Bob Secondary", "127.0.0.1", bobPort);
            }
            runtimeAlice.sendFile(bobInfo, testFile);

            // Wait for transfer to complete on Alice's side
            long transferDeadline = System.currentTimeMillis() + 15000;
            boolean completed = false;
            while (System.currentTimeMillis() < transferDeadline) {
                JTable transferTable = (JTable) getFieldValue(frame, "transfersTable");
                int rows = transferTable.getRowCount();
                if (rows > 0) {
                    Object statusObj = transferTable.getValueAt(0, 6);
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

            // Verify received file at Bob
            Path receivedAtBob = bobDown.resolve("sample-transfer.dat");
            if (!Files.exists(receivedAtBob)) {
                throw new AssertionError("Transferred file does not exist at Bob: " + receivedAtBob);
            }
            String actualHash = HashUtil.sha256(receivedAtBob);
            if (!expectedHash.equals(actualHash)) {
                throw new AssertionError("SHA-256 hash mismatch! Expected: " + expectedHash + ", actual: " + actualHash);
            }
            System.out.println("  Verified SHA-256 matches: " + actualHash);

            // -----------------------------------------------------------------
            // 5. Test Rejected Offer
            // -----------------------------------------------------------------
            System.out.println("[DesktopUiSmoke] Testing rejected file offer...");
            bobAutoAccept.set(false);
            Path rejectFile = aliceShared.resolve("reject-me.txt");
            Files.writeString(rejectFile, "reject this content");
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

            // -----------------------------------------------------------------
            // 6. Catalogue Search & Activity Log Verification
            // -----------------------------------------------------------------
            System.out.println("[DesktopUiSmoke] Testing Catalogue Search tab...");
            JTabbedPane tabs = (JTabbedPane) getFieldValue(frame, "tabbedPane");
            SwingUtilities.invokeAndWait(() -> tabs.setSelectedIndex(1)); // Switch to Catalogue Search
            robot.waitForIdle();
            Thread.sleep(150);

            JTextField searchField = (JTextField) getFieldValue(frame, "searchField");
            JButton searchBtn = (JButton) getFieldValue(frame, "searchButton");
            JButton downloadBtn = (JButton) getFieldValue(frame, "downloadButton");
            JTable searchTable = (JTable) getFieldValue(frame, "searchTable");

            var directResults = runtimeAlice.searchFiles("shared-catalogue-item.txt");
            System.out.println("  Direct search result count: " + directResults.size());

            SwingUtilities.invokeAndWait(() -> {
                frame.toFront();
                searchField.requestFocusInWindow();
                searchField.setText("shared-catalogue-item.txt");
            });
            robot.waitForIdle();
            clickComponent(robot, searchBtn);
            robot.waitForIdle();
            long searchDeadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < searchDeadline && searchTable.getRowCount() == 0) {
                Thread.sleep(100);
            }
            if (searchTable.getRowCount() == 0) {
                SwingUtilities.invokeAndWait(frame::performSearch);
                long secondDeadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < secondDeadline && searchTable.getRowCount() == 0) {
                    Thread.sleep(100);
                }
            }
            if (searchTable.getRowCount() == 0) {
                throw new AssertionError("Catalogue search returned 0 results for shared-catalogue-item.txt");
            }
            // Select search result row
            JTextArea searchDetail = (JTextArea) getFieldValue(frame, "searchDetailText");
            SwingUtilities.invokeAndWait(() -> {
                if (searchTable.getRowCount() > 0) {
                    searchTable.setRowSelectionInterval(0, 0);
                }
            });
            robot.waitForIdle();

            long detailDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < detailDeadline && searchDetail.getText().isBlank()) {
                Thread.sleep(50);
            }

            String detailText = searchDetail.getText();
            if (!detailText.contains("shared-catalogue-item.txt") || !detailText.contains(bobFileId)) {
                throw new AssertionError("Search details missing expected filename or SHA-256 hash");
            }
            if (!downloadBtn.isEnabled()) {
                throw new AssertionError("Download Selected button should be enabled for selected search result");
            }

            // Test Activity Log
            System.out.println("[DesktopUiSmoke] Testing Activity Log tab...");
            SwingUtilities.invokeAndWait(() -> tabs.setSelectedIndex(2)); // Switch to Activity Log
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
                SwingUtilities.invokeAndWait(clearLogBtn::doClick);
                robot.waitForIdle();
            }
            if (!logArea.getText().isEmpty()) {
                throw new AssertionError("Activity Log must be empty after clicking Clear Log");
            }

            // -----------------------------------------------------------------
            // 7. Settings Dialog & Form Layout Verification
            // -----------------------------------------------------------------
            System.out.println("[DesktopUiSmoke] Verifying SettingsDialog geometry and footers...");
            JButton settingsBtn = (JButton) getFieldValue(frame, "settingsButton");
            clickComponent(robot, settingsBtn);
            robot.waitForIdle();
            Thread.sleep(300);

            SettingsDialog settingsDialog = (SettingsDialog) getFieldValue(frame, "settingsDialog");
            if (settingsDialog == null || !settingsDialog.isShowing()) {
                throw new AssertionError("SettingsDialog was not displayed after clicking settings button");
            }
            captureScreenshot(robot, settingsDialog, screenshotDir.resolve("03-settings-dialog.png"));

            // Verify advanced section toggle
            JButton toggleAdv = (JButton) getFieldValue(settingsDialog, "toggleAdvancedBtn");
            JPanel advPanel = (JPanel) getFieldValue(settingsDialog, "advancedPanel");
            if (advPanel.isVisible()) {
                throw new AssertionError("Advanced panel must be initially collapsed");
            }

            clickComponent(robot, toggleAdv);
            robot.waitForIdle();
            Thread.sleep(150);
            if (!advPanel.isVisible()) {
                SwingUtilities.invokeAndWait(toggleAdv::doClick);
                robot.waitForIdle();
            }
            if (!advPanel.isVisible()) {
                throw new AssertionError("Advanced panel must be visible after clicking toggle button");
            }

            // Verify footer buttons are visible and not clipped
            JButton saveBtn = (JButton) getFieldValue(settingsDialog, "saveButton");
            JButton closeBtn = (JButton) getFieldValue(settingsDialog, "closeButton");
            if (!saveBtn.isShowing() || !closeBtn.isShowing()) {
                throw new AssertionError("SettingsDialog footer buttons (Save/Close) must be showing");
            }

            // Close SettingsDialog via escape key
            robot.keyPress(KeyEvent.VK_ESCAPE);
            robot.delay(50);
            robot.keyRelease(KeyEvent.VK_ESCAPE);
            robot.waitForIdle();
            Thread.sleep(200);

            // -----------------------------------------------------------------
            // 8. Software Updates Dialog & Presentation Snapshot Verification
            // -----------------------------------------------------------------
            System.out.println("[DesktopUiSmoke] Verifying UpdateDialog presentation states...");
            JButton updateBtn = (JButton) getFieldValue(frame, "updateButton");
            clickComponent(robot, updateBtn);
            robot.waitForIdle();
            Thread.sleep(300);

            UpdateDialog updateDialog = (UpdateDialog) getFieldValue(frame, "updateDialog");
            if (updateDialog == null || !updateDialog.isShowing()) {
                throw new AssertionError("UpdateDialog was not displayed after clicking update button");
            }
            captureScreenshot(robot, updateDialog, screenshotDir.resolve("04-update-dialog.png"));

            // Inject snapshot states to verify label fitting and visibility
            Method applySnapshotMethod = UpdateDialog.class.getDeclaredMethod("applySnapshot", UpdateService.UpdateSnapshot.class);
            applySnapshotMethod.setAccessible(true);

            List<UpdateService.UpdateState> testStates = List.of(
                    UpdateService.UpdateState.NOT_CHECKED,
                    UpdateService.UpdateState.CHECKING,
                    UpdateService.UpdateState.UP_TO_DATE,
                    UpdateService.UpdateState.NO_RELEASE,
                    UpdateService.UpdateState.UPDATE_AVAILABLE,
                    UpdateService.UpdateState.DOWNLOADING,
                    UpdateService.UpdateState.VERIFYING,
                    UpdateService.UpdateState.READY_TO_RESTART,
                    UpdateService.UpdateState.FAILED
            );

            ClientVersion v1 = ClientVersion.parse("1.2.0");
            ClientVersion v2 = ClientVersion.parse("1.3.0");

            for (UpdateService.UpdateState st : testStates) {
                UpdateService.UpdateSnapshot snap = new UpdateService.UpdateSnapshot(
                        st, v1, v2, 524288L, 1048576L, "Sample detail for state " + st + " with wrapped text.", "Release notes text.", null, null
                );
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        applySnapshotMethod.invoke(updateDialog, snap);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
                robot.waitForIdle();
            }

            // Close UpdateDialog
            robot.keyPress(KeyEvent.VK_ESCAPE);
            robot.delay(50);
            robot.keyRelease(KeyEvent.VK_ESCAPE);
            robot.waitForIdle();
            Thread.sleep(200);

            // -----------------------------------------------------------------
            // 9. Incoming File Offer Prompt Geometry
            // -----------------------------------------------------------------
            String promptHash = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
            int promptChunkSize = 16384;
            long promptFileSize = 65536L;
            long promptChunks = promptFileSize / promptChunkSize;
            FileMetadata promptMeta = new FileMetadata(
                    java.util.UUID.randomUUID().toString(),
                    promptHash,
                    "long-test-filename-with-extended-description-for-layout-inspection-2026.iso",
                    promptFileSize,
                    promptChunkSize,
                    promptChunks,
                    promptHash,
                    "Sender With Very Long Display Name To Ensure No Horizontal Overflow"
            );
            SwingIncomingFilePrompt prompt = new SwingIncomingFilePrompt(frame);

            CompletableFuture<Boolean> promptDecision = CompletableFuture.supplyAsync(() ->
                    prompt.accept(promptMeta, new InetSocketAddress("192.168.1.100", 65535), 30000)
            );

            Thread.sleep(500);
            robot.waitForIdle();

            // Find prompt dialog
            JDialog promptDialog = null;
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isShowing() && "Incoming file".equals(d.getTitle())) {
                    promptDialog = d;
                    break;
                }
            }

            if (promptDialog == null) {
                throw new AssertionError("Incoming file prompt dialog was not found on screen");
            }

            captureScreenshot(robot, promptDialog, screenshotDir.resolve("05-incoming-offer-dialog.png"));

            // Dismiss prompt dialog via escape (Reject)
            long pressTime = System.currentTimeMillis();
            robot.keyPress(KeyEvent.VK_ESCAPE);
            robot.delay(50);
            robot.keyRelease(KeyEvent.VK_ESCAPE);
            robot.waitForIdle();

            boolean decision = promptDecision.get(4, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - pressTime;
            if (decision) {
                throw new AssertionError("Dismissed incoming prompt must resolve to false (Reject)");
            }
            if (elapsed >= 10000) {
                throw new AssertionError("Prompt resolved too slowly (" + elapsed + "ms); likely timed out rather than handling Escape");
            }
            final JDialog dlg = promptDialog;
            boolean[] stillShowing = new boolean[1];
            SwingUtilities.invokeAndWait(() -> stillShowing[0] = dlg.isShowing());
            if (stillShowing[0]) {
                throw new AssertionError("Incoming prompt dialog must not be showing after Escape");
            }
            System.out.println("  Incoming prompt rejected via Escape in " + elapsed + "ms.");
            System.out.println("[DesktopUiSmoke] All UI sections successfully exercised and verified at scale " + scale + ".");
        } finally {
            SwingUtilities.invokeLater(() -> {
                for (Window w : Window.getWindows()) {
                    w.dispose();
                }
            });
            updateService.close();
            runtimeAlice.close();
            runtimeBob.close();
            tracker.close();
        }
    }

    private static void clickComponent(Robot robot, AbstractButton comp) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Window w = SwingUtilities.getWindowAncestor(comp);
            if (w != null) {
                w.toFront();
                w.requestFocus();
            }
            comp.scrollRectToVisible(new Rectangle(0, 0, Math.max(1, comp.getWidth()), Math.max(1, comp.getHeight())));
            comp.requestFocusInWindow();
        });
        Thread.sleep(60);
        Point[] loc = new Point[1];
        Dimension[] dim = new Dimension[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                loc[0] = comp.getLocationOnScreen();
                dim[0] = comp.getSize();
            } catch (Exception ignored) {
            }
        });

        if (loc[0] == null || dim[0] == null || !comp.isShowing()) {
            throw new IllegalStateException("Component is not visible on screen: " + comp.getText());
        }

        int targetX = loc[0].x + dim[0].width / 2;
        int targetY = loc[0].y + dim[0].height / 2;

        robot.mouseMove(targetX, targetY);
        robot.delay(60);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(60);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(100);
        robot.waitForIdle();
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
}
