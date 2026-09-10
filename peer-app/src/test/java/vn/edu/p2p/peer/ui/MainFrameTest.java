package vn.edu.p2p.peer.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.update.BuildInfo;
import vn.edu.p2p.peer.update.ClientVersion;
import vn.edu.p2p.peer.update.ReleaseClient;
import vn.edu.p2p.peer.update.RestartCoordinator;
import vn.edu.p2p.peer.update.UpdateService;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void testFontUtilityMethods() {
        Font derived = MainFrame.getDerivedFont(0f);
        assertNotNull(derived);

        Font derivedSmaller = MainFrame.getDerivedFont(-2f);
        assertNotNull(derivedSmaller);
        assertTrue(derivedSmaller.getSize2D() <= derived.getSize2D());

        Font mono = MainFrame.getMonospacedFont();
        assertNotNull(mono);
        assertEquals(Font.MONOSPACED, mono.getFamily());
    }

    @Test
    void testPeerPanelLayoutAndActionStates(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");

        Path configFile = tempDir.resolve("peer.properties");
        Files.writeString(configFile, "peer.id=test\npeer.name=Test\npeer.port=6001\ntracker.host=127.0.0.1\ntracker.port=5000\nstorage.download.dir=" + tempDir.resolve("down") + "\nstorage.shared.dir=" + tempDir.resolve("share") + "\n");
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

            // Verify parent panel layout is GridLayout with 2 rows, 1 col (stacked)
            JPanel buttonsPanel = (JPanel) sendButton.getParent();
            assertNotNull(buttonsPanel);
            assertTrue(buttonsPanel.getLayout() instanceof GridLayout);
            GridLayout layout = (GridLayout) buttonsPanel.getLayout();
            assertEquals(2, layout.getRows());
            assertEquals(1, layout.getColumns());
            assertEquals(sendButton, buttonsPanel.getComponent(0), "Primary sendButton must be top button");
            assertEquals(refreshButton, buttonsPanel.getComponent(1), "refreshButton must be bottom button");

            // Verify context menu has sendMenuItem
            assertNotNull(peerPopupMenu);
            assertEquals(sendMenuItem, peerPopupMenu.getComponent(0));

            // Initially starting=false, no peer selected -> send disabled
            runOnEdt(() -> {
                frame.setStarting(false);
            });
            assertFalse(sendButton.isEnabled(), "Send button should be disabled when no peer is selected");
            assertFalse(sendMenuItem.isEnabled(), "Send menu item should be disabled when no peer is selected");
            assertTrue(refreshButton.isEnabled(), "Refresh button should be enabled");

            // Add a peer and select it -> send becomes enabled
            runOnEdt(() -> {
                PeerInfo peer = new PeerInfo("peer-target", "Target", "127.0.0.1", 6002);
                peerModel.addElement(peer);
                peerList.setSelectedValue(peer, true);
            });

            assertTrue(sendButton.isEnabled(), "Send button should be enabled when a peer is selected");
            assertTrue(sendMenuItem.isEnabled(), "Send menu item should be enabled when a peer is selected");
        } finally {
            runOnEdt(frame::dispose);
            updateService.close();
        }
    }
}
