package vn.edu.p2p.peer.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.update.BuildInfo;
import vn.edu.p2p.peer.update.ReleaseClient;
import vn.edu.p2p.peer.update.RestartCoordinator;
import vn.edu.p2p.peer.update.UpdateService;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainFrameTest {

    private void runOnEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeAndWait(r);
        }
    }

    private Object getFieldValue(Object obj, String fieldName) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(obj);
    }

    @Test
    void testActionStateTransitionsAndLifecycle(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");

        Path configFile = tempDir.resolve("peer.properties");
        Properties props = new Properties();
        props.setProperty("peer.id", "test");
        props.setProperty("peer.name", "Test");
        props.setProperty("peer.port", "6001");
        props.setProperty("tracker.host", "127.0.0.1");
        props.setProperty("tracker.port", "5000");
        props.setProperty("download.dir", tempDir.resolve("down").toString());
        props.setProperty("shared.dir", tempDir.resolve("share").toString());
        try (OutputStream out = Files.newOutputStream(configFile)) {
            props.store(out, "test config");
        }

        AppConfig config = AppConfig.load(configFile);
        PeerRuntime runtime = new PeerRuntime(config);
        ConfigStore store = new ConfigStore(configFile, tempDir);
        RestartCoordinator restarts = new RestartCoordinator(tempDir);
        BuildInfo buildInfo = BuildInfo.load();
        ReleaseClient client = new ReleaseClient(buildInfo);
        UpdateService updateService = new UpdateService(client, tempDir);

        AtomicReference<MainFrame> frameRef = new AtomicReference<>();
        runOnEdt(() -> {
            MainFrame frame = new MainFrame(runtime, store, updateService, restarts);
            frameRef.set(frame);
        });

        MainFrame frame = frameRef.get();
        try {
            JButton sendButton = (JButton) getFieldValue(frame, "sendButton");
            JButton refreshButton = (JButton) getFieldValue(frame, "refreshButton");
            JMenuItem sendMenuItem = (JMenuItem) getFieldValue(frame, "sendMenuItem");
            JPopupMenu peerPopupMenu = (JPopupMenu) getFieldValue(frame, "peerPopupMenu");
            @SuppressWarnings("unchecked")
            JList<PeerInfo> peerList = (JList<PeerInfo>) getFieldValue(frame, "peerList");
            @SuppressWarnings("unchecked")
            DefaultListModel<PeerInfo> peerModel = (DefaultListModel<PeerInfo>) getFieldValue(frame, "peerModel");

            // Context menu contains sendMenuItem
            assertNotNull(peerPopupMenu);
            assertEquals(sendMenuItem, peerPopupMenu.getComponent(0));

            // 1. Initial state: starting=true -> send and refresh disabled
            assertFalse(sendButton.isEnabled(), "Send button must be disabled while starting");
            assertFalse(sendMenuItem.isEnabled(), "Send menu item must be disabled while starting");
            assertFalse(refreshButton.isEnabled(), "Refresh button must be disabled while starting");

            // 2. State transition: starting -> ready (starting=false)
            runOnEdt(() -> frame.setStarting(false));
            assertTrue(refreshButton.isEnabled(), "Refresh button should become enabled when ready");
            assertFalse(sendButton.isEnabled(), "Send button should remain disabled when no peer is selected");
            assertFalse(sendMenuItem.isEnabled(), "Send menu item should remain disabled when no peer is selected");

            // 3. Selection: add peer and select it -> send actions become enabled
            PeerInfo peer1 = new PeerInfo("peer-target-1", "Target 1", "127.0.0.1", 6002);
            PeerInfo peer2 = new PeerInfo("peer-target-2", "Target 2", "127.0.0.1", 6003);
            runOnEdt(() -> {
                peerModel.addElement(peer1);
                peerModel.addElement(peer2);
                peerList.setSelectedValue(peer1, true);
            });
            assertTrue(sendButton.isEnabled(), "Send button should be enabled when a peer is selected");
            assertTrue(sendMenuItem.isEnabled(), "Send menu item should be enabled when a peer is selected");

            // 4. Freeze for restart: disables send actions
            runOnEdt(() -> frame.freezeForRestart(true));
            assertFalse(sendButton.isEnabled(), "Send button must be disabled while frozen for restart");
            assertFalse(sendMenuItem.isEnabled(), "Send menu item must be disabled while frozen for restart");

            // Unfreeze: restores enabled state for selected peer
            runOnEdt(() -> frame.freezeForRestart(false));
            assertTrue(sendButton.isEnabled(), "Send button should be re-enabled after unfreeze");
            assertTrue(sendMenuItem.isEnabled(), "Send menu item should be re-enabled after unfreeze");

            // 5. Stopped: permanently disables send and refresh
            runOnEdt(() -> frame.setStopped("Testing stopped transition"));
            assertFalse(sendButton.isEnabled(), "Send button must be disabled when stopped");
            assertFalse(sendMenuItem.isEnabled(), "Send menu item must be disabled when stopped");
            assertFalse(refreshButton.isEnabled(), "Refresh button must be disabled when stopped");

            // Changing selection after stopped MUST NOT re-enable send actions
            runOnEdt(() -> peerList.setSelectedValue(peer2, true));
            assertFalse(sendButton.isEnabled(), "Selecting a peer after stopped must not re-enable send button");
            assertFalse(sendMenuItem.isEnabled(), "Selecting a peer after stopped must not re-enable send menu item");
        } finally {
            runOnEdt(frame::dispose);
            updateService.close();
            runtime.close();
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
