package vn.edu.p2p.peer.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.config.ConfigSnapshot;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.update.RestartCoordinator;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsDialogTest {

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

    private void invokeMethod(Object obj, String methodName) throws Exception {
        Method m = obj.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(obj);
    }

    @Test
    void testSavePreservesSnapshotAndAppliesRestartNotice(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");

        Path configFile = tempDir.resolve("peer.properties");
        String content = """
                peer.id=peer-test-id
                peer.name=Alice
                peer.port=6001
                tracker.host=127.0.0.1
                tracker.port=5000
                download.dir=downloads
                shared.dir=shared
                custom.key=preserved
                """;
        Files.writeString(configFile, content);

        AppConfig config = AppConfig.load(configFile);
        PeerRuntime runtime = new PeerRuntime(config);
        ConfigStore store = new ConfigStore(configFile, tempDir);
        RestartCoordinator restarts = new RestartCoordinator(tempDir);

        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();
        runOnEdt(() -> {
            JFrame parent = new JFrame();
            SettingsDialog dialog = new SettingsDialog(parent, store, runtime, restarts);
            dialogRef.set(dialog);
        });

        SettingsDialog dialog = dialogRef.get();
        assertNotNull(dialog);
        assertFalse(dialog.hasUnsavedChanges(), "Initially there should be no unsaved changes");

        runOnEdt(() -> {
            try {
                JTextField nameField = (JTextField) getFieldValue(dialog, "displayNameField");
                nameField.setText("AliceRenamed");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        assertTrue(dialog.hasUnsavedChanges(), "Editing field must mark unsaved changes");

        // Save
        runOnEdt(() -> {
            try {
                invokeMethod(dialog, "onSave");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        // Wait for save SwingWorker to complete
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && dialog.hasUnsavedChanges()) {
            Thread.sleep(50);
        }

        assertFalse(dialog.hasUnsavedChanges(), "After save, unsaved changes must be false");
        ConfigSnapshot diskSnapshot = store.read();
        assertEquals("AliceRenamed", diskSnapshot.getProperty("peer.name"));
        assertEquals("preserved", diskSnapshot.getProperty("custom.key"), "Custom keys must be preserved");

        runOnEdt(dialog::dispose);
    }

    @Test
    void testSyntaxRepairPanelDisplaysOriginalSourceAndRepairs(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");

        Path configFile = tempDir.resolve("bad_peer.properties");
        String badContent = "peer.id=p1\npeer.name=\\u12\n";
        Files.writeString(configFile, badContent);

        ConfigStore store = new ConfigStore(configFile, tempDir);
        ConfigStore.SyntaxException syntaxEx = null;
        try {
            store.read();
        } catch (ConfigStore.SyntaxException ex) {
            syntaxEx = ex;
        }
        assertNotNull(syntaxEx, "Expected SyntaxException on malformed unicode escape");

        final ConfigStore.SyntaxException capturedEx = syntaxEx;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();
        runOnEdt(() -> {
            SettingsDialog dialog = new SettingsDialog(
                    (Dialog) null,
                    store,
                    null,
                    null,
                    SettingsDialog.Mode.BOOTSTRAP_SYNTAX_REPAIR,
                    capturedEx
            );
            dialogRef.set(dialog);
        });

        SettingsDialog dialog = dialogRef.get();
        assertNotNull(dialog);

        JTextArea textArea = (JTextArea) getFieldValue(dialog, "syntaxTextArea");
        assertNotNull(textArea);
        assertEquals(badContent, textArea.getText(), "Syntax repair panel must display the exact source text");

        // Repair text in the text area
        String repairedSource = "peer.id=p1\npeer.name=RepairedUser\n";
        runOnEdt(() -> {
            textArea.setText(repairedSource);
            try {
                invokeMethod(dialog, "onSave");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        long deadline = System.currentTimeMillis() + 5000;
        ConfigSnapshot repairedSnapshot = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                repairedSnapshot = store.read();
                if ("RepairedUser".equals(repairedSnapshot.getProperty("peer.name"))) {
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(50);
        }

        assertNotNull(repairedSnapshot);
        assertEquals("RepairedUser", repairedSnapshot.getProperty("peer.name"));

        runOnEdt(dialog::dispose);
    }

    @Test
    void testConflictPreservesDraftAndOffersReload(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");

        Path configFile = tempDir.resolve("conflict.properties");
        Files.writeString(configFile, "peer.id=p1\npeer.name=Initial\n");

        ConfigStore store = new ConfigStore(configFile, tempDir);
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();
        runOnEdt(() -> {
            SettingsDialog dialog = new SettingsDialog(
                    (Dialog) null,
                    store,
                    null,
                    null,
                    SettingsDialog.Mode.BOOTSTRAP
            );
            dialogRef.set(dialog);
        });

        SettingsDialog dialog = dialogRef.get();
        JTextField nameField = (JTextField) getFieldValue(dialog, "displayNameField");
        JButton reloadBtn = (JButton) getFieldValue(dialog, "reloadButton");

        runOnEdt(() -> nameField.setText("MyDraftName"));

        // Simulate external modification to disk
        Files.writeString(configFile, "peer.id=p1\npeer.name=ExternalWin\n");

        // Attempt save -> triggers conflict
        runOnEdt(() -> {
            try {
                invokeMethod(dialog, "onSave");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !reloadBtn.isVisible()) {
            Thread.sleep(50);
        }

        // Draft must be preserved!
        assertEquals("MyDraftName", nameField.getText(), "Conflict must preserve the user draft in form fields");
        assertTrue(reloadBtn.isVisible(), "Reload button must become visible on conflict");

        // Now trigger reload
        runOnEdt(() -> {
            try {
                invokeMethod(dialog, "onReload");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && reloadBtn.isVisible()) {
            Thread.sleep(50);
        }

        assertEquals("ExternalWin", nameField.getText(), "After reload, fields must reflect disk content");
        assertFalse(reloadBtn.isVisible(), "Reload button must be hidden after successful reload");

        runOnEdt(dialog::dispose);
    }

    @Test
    void testInvalidPathSyntaxDoesNotThrowOnEdt(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");

        Path configFile = tempDir.resolve("peer.properties");
        Files.writeString(configFile, "peer.id=p1\npeer.name=Alice\n");

        ConfigStore store = new ConfigStore(configFile, tempDir);
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();
        runOnEdt(() -> {
            SettingsDialog dialog = new SettingsDialog((Dialog) null, store, null, null, SettingsDialog.Mode.BOOTSTRAP);
            dialogRef.set(dialog);
        });

        SettingsDialog dialog = dialogRef.get();
        JTextField dlField = (JTextField) getFieldValue(dialog, "downloadDirField");
        JLabel dlPreview = (JLabel) getFieldValue(dialog, "downloadDirPreview");

        runOnEdt(() -> {
            // Put an invalid path syntax
            dlField.setText("C:\\bad\0path");
        });

        assertEquals("Invalid path syntax", dlPreview.getText(), "Invalid path syntax must be handled gracefully in preview");

        runOnEdt(dialog::dispose);
    }

    @Test
    void testPeerIdEditabilityRules(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");

        Path validConfigFile = tempDir.resolve("valid.properties");
        Files.writeString(validConfigFile, "peer.id=valid-id\npeer.name=Alice\n");

        ConfigStore validStore = new ConfigStore(validConfigFile, tempDir);

        // 1. In RUNTIME mode: valid ID is non-editable
        runOnEdt(() -> {
            AppConfig cfg = null;
            try {
                cfg = AppConfig.load(validConfigFile);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            PeerRuntime runtime = new PeerRuntime(cfg);
            SettingsDialog dialog = new SettingsDialog(new JFrame(), validStore, runtime, null);
            try {
                JTextField idField = (JTextField) getFieldValue(dialog, "peerIdField");
                assertFalse(idField.isEditable(), "Peer ID must be read-only in RUNTIME mode");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                dialog.dispose();
            }
        });

        // 2. In BOOTSTRAP mode with valid ID: non-editable
        runOnEdt(() -> {
            SettingsDialog dialog = new SettingsDialog((Dialog) null, validStore, null, null, SettingsDialog.Mode.BOOTSTRAP);
            try {
                JTextField idField = (JTextField) getFieldValue(dialog, "peerIdField");
                assertFalse(idField.isEditable(), "Valid Peer ID must stay read-only in BOOTSTRAP mode");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                dialog.dispose();
            }
        });

        // 3. In BOOTSTRAP mode with absent file: editable and auto-generated UUID
        Path absentFile = tempDir.resolve("absent.properties");
        ConfigStore absentStore = new ConfigStore(absentFile, tempDir);
        runOnEdt(() -> {
            SettingsDialog dialog = new SettingsDialog((Dialog) null, absentStore, null, null, SettingsDialog.Mode.BOOTSTRAP);
            try {
                JTextField idField = (JTextField) getFieldValue(dialog, "peerIdField");
                assertTrue(idField.isEditable(), "Peer ID must be editable on first run (absent file)");
                assertTrue(idField.getText().startsWith("peer-"), "Auto-generated peer ID must start with peer-");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                dialog.dispose();
            }
        });
    }
}
