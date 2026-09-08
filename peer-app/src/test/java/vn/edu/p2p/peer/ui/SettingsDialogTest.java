package vn.edu.p2p.peer.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.update.RestartCoordinator;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JTextField;
import java.awt.Dimension;
import java.io.IOException;
import org.junit.jupiter.api.Assumptions;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsDialogTest {

    @Test
    void testSettingsDialogConstructionAndPopulation(@TempDir Path tempDir) throws IOException {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");
        Path configFile = tempDir.resolve("peer.properties");
        String content = """
                peer.id=peer-test-id
                peer.name=Alice
                peer.port=6001
                tracker.host=127.0.0.1
                tracker.port=5000
                custom.key=preserved
                """;
        Files.writeString(configFile, content);

        AppConfig config = AppConfig.load(configFile);
        PeerRuntime runtime = new PeerRuntime(config);
        ConfigStore store = new ConfigStore(configFile, tempDir);
        RestartCoordinator restarts = new RestartCoordinator(tempDir);

        JFrame parent = new JFrame();
        SettingsDialog dialog = new SettingsDialog(parent, store, runtime, restarts);

        assertNotNull(dialog);
        assertEquals("Settings", dialog.getTitle());

        // Check sizing at target resolutions
        dialog.setSize(new Dimension(980, 620));
        dialog.doLayout();
        assertEquals(980, dialog.getWidth());
        assertEquals(620, dialog.getHeight());

        dialog.setSize(new Dimension(1200, 760));
        dialog.doLayout();
        assertEquals(1200, dialog.getWidth());
        assertEquals(760, dialog.getHeight());

        dialog.dispose();
        parent.dispose();
    }

    @Test
    void testFirstRunDefaultsWhenConfigAbsent(@TempDir Path tempDir) {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");
        Path absentConfigFile = tempDir.resolve("new_peer.properties");
        ConfigStore store = new ConfigStore(absentConfigFile, tempDir);

        JFrame parent = new JFrame();
        SettingsDialog dialog = new SettingsDialog(parent, store, null, null);

        assertNotNull(dialog);
        assertFalse(Files.exists(absentConfigFile));

        dialog.dispose();
        parent.dispose();
    }
}
