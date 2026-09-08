package vn.edu.p2p.peer.ui;

import vn.edu.p2p.common.protocol.TransferProtocol;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.config.ConfigConflictException;
import vn.edu.p2p.peer.config.ConfigSnapshot;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.update.RestartCoordinator;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class SettingsDialog extends JDialog {

    public enum Mode {
        BOOTSTRAP,
        RUNTIME
    }

    private final Mode mode;
    private final ConfigStore configStore;
    private final PeerRuntime runtime;
    private final RestartCoordinator restartCoordinator;

    private ConfigSnapshot currentSnapshot;
    private boolean savedSuccess = false;

    // Fields
    private final JTextField configPathField = new JTextField(30);
    private final JTextField peerIdField = new JTextField(30);
    private final JTextField displayNameField = new JTextField(24);
    private final JTextField peerPortField = new JTextField(8);
    private final JTextField trackerHostField = new JTextField(20);
    private final JTextField trackerPortField = new JTextField(8);
    private final JTextField downloadDirField = new JTextField(24);
    private final JLabel downloadDirPreview = new JLabel();
    private final JTextField sharedDirField = new JTextField(24);
    private final JLabel sharedDirPreview = new JLabel();
    private final JCheckBox autoAcceptBox = new JCheckBox("Automatically accept incoming transfers");
    private final JCheckBox checkOnStartupBox = new JCheckBox("Check for updates on startup");

    // Advanced fields
    private final JPanel advancedPanel = new JPanel(new GridBagLayout());
    private final JButton toggleAdvancedBtn = new JButton("Show Advanced Settings ▼");
    private boolean advancedVisible = false;

    private final JTextField chunkSizeField = new JTextField(10);
    private final JTextField trackerReadTimeoutField = new JTextField(10);
    private final JTextField transferReadTimeoutField = new JTextField(10);
    private final JTextField transferPromptTimeoutField = new JTextField(10);
    private final JTextField transferOfferTimeoutField = new JTextField(10);
    private final JTextField transferVerifyTimeoutField = new JTextField(10);
    private final JTextField maxConcurrentField = new JTextField(6);

    // Status / Error banner
    private final JLabel statusBanner = new JLabel();
    private final JButton saveButton;
    private final JButton restartButton = new JButton("Restart now");
    private final JButton closeButton;

    public static boolean showBootstrapDialog(ConfigStore configStore, String initialError) {
        JDialog owner = new JDialog((JFrame) null, "P2P File Sharing - Setup", true);
        SettingsDialog dialog = new SettingsDialog(owner, configStore, null, null, Mode.BOOTSTRAP);
        if (initialError != null && !initialError.isBlank()) {
            dialog.showError("Configuration error: " + initialError);
        }
        dialog.setVisible(true);
        return dialog.savedSuccess;
    }

    public SettingsDialog(
            JFrame owner,
            ConfigStore configStore,
            PeerRuntime runtime,
            RestartCoordinator restartCoordinator
    ) {
        super(owner, "Settings", false);
        this.mode = Mode.RUNTIME;
        this.configStore = Objects.requireNonNull(configStore, "configStore cannot be null");
        this.runtime = runtime;
        this.restartCoordinator = restartCoordinator;
        this.saveButton = new JButton("Save settings");
        this.closeButton = new JButton("Close");
        init();
    }

    private SettingsDialog(
            Dialog owner,
            ConfigStore configStore,
            PeerRuntime runtime,
            RestartCoordinator restartCoordinator,
            Mode mode
    ) {
        super(owner, "P2P File Sharing - Setup", true);
        this.mode = mode;
        this.configStore = Objects.requireNonNull(configStore, "configStore cannot be null");
        this.runtime = runtime;
        this.restartCoordinator = restartCoordinator;
        this.saveButton = new JButton("Save and start");
        this.closeButton = new JButton("Cancel");
        init();
    }

    private void init() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(600, 480));
        setPreferredSize(new Dimension(680, 600));

        try {
            this.currentSnapshot = configStore.read();
        } catch (IOException ex) {
            this.currentSnapshot = new ConfigSnapshot(configStore.configPath(), configStore.targetPath(), false, null, Map.of());
        }

        buildUi();
        populateFields();

        // Escape closes
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog");
        getRootPane().getActionMap().put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });

        pack();
        setLocationRelativeTo(getOwner());
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        // Top banner for errors or status
        statusBanner.setVisible(false);
        statusBanner.putClientProperty("html.disable", Boolean.TRUE);
        statusBanner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        statusBanner.setOpaque(true);

        JPanel mainForm = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 4, 4, 4);

        int row = 0;

        // Configuration Path (readonly)
        configPathField.setEditable(false);
        configPathField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(mainForm, gbc, row++, "Config File:", configPathField, null);

        // Peer ID
        peerIdField.putClientProperty("html.disable", Boolean.TRUE);
        if (mode == Mode.RUNTIME || currentSnapshot.exists()) {
            peerIdField.setEditable(false);
        }
        addFormRow(mainForm, gbc, row++, "Peer ID:", peerIdField, "(unique identity)");

        // Display Name
        displayNameField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(mainForm, gbc, row++, "Display Name:", displayNameField, null);

        // Listen Port
        peerPortField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(mainForm, gbc, row++, "Listen Port:", peerPortField, "(1-65535)");

        // Tracker Host & Port
        JPanel trackerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        trackerHostField.putClientProperty("html.disable", Boolean.TRUE);
        trackerPortField.putClientProperty("html.disable", Boolean.TRUE);
        trackerPanel.add(trackerHostField);
        trackerPanel.add(new JLabel(":"));
        trackerPanel.add(trackerPortField);
        addFormRow(mainForm, gbc, row++, "Tracker Address:", trackerPanel, null);

        // Download Directory + Browse
        JPanel downloadPanel = new JPanel(new BorderLayout(4, 0));
        downloadDirField.putClientProperty("html.disable", Boolean.TRUE);
        JButton browseDownloadBtn = new JButton("Browse...");
        browseDownloadBtn.addActionListener(e -> browseDirectory(downloadDirField));
        downloadPanel.add(downloadDirField, BorderLayout.CENTER);
        downloadPanel.add(browseDownloadBtn, BorderLayout.EAST);
        downloadDirField.getDocument().addDocumentListener(new SimpleDocumentListener(this::updatePathPreviews));
        downloadDirPreview.putClientProperty("html.disable", Boolean.TRUE);
        downloadDirPreview.setForeground(Color.GRAY);

        JPanel downloadWrapper = new JPanel(new BorderLayout(2, 2));
        downloadWrapper.add(downloadPanel, BorderLayout.NORTH);
        downloadWrapper.add(downloadDirPreview, BorderLayout.SOUTH);
        addFormRow(mainForm, gbc, row++, "Download Folder:", downloadWrapper, null);

        // Shared Directory + Browse
        JPanel sharedPanel = new JPanel(new BorderLayout(4, 0));
        sharedDirField.putClientProperty("html.disable", Boolean.TRUE);
        JButton browseSharedBtn = new JButton("Browse...");
        browseSharedBtn.addActionListener(e -> browseDirectory(sharedDirField));
        sharedPanel.add(sharedDirField, BorderLayout.CENTER);
        sharedPanel.add(browseSharedBtn, BorderLayout.EAST);
        sharedDirField.getDocument().addDocumentListener(new SimpleDocumentListener(this::updatePathPreviews));
        sharedDirPreview.putClientProperty("html.disable", Boolean.TRUE);
        sharedDirPreview.setForeground(Color.GRAY);

        JPanel sharedWrapper = new JPanel(new BorderLayout(2, 2));
        sharedWrapper.add(sharedPanel, BorderLayout.NORTH);
        sharedWrapper.add(sharedDirPreview, BorderLayout.SOUTH);
        addFormRow(mainForm, gbc, row++, "Shared Folder:", sharedWrapper, null);

        // Checkboxes
        autoAcceptBox.putClientProperty("html.disable", Boolean.TRUE);
        checkOnStartupBox.putClientProperty("html.disable", Boolean.TRUE);
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        mainForm.add(autoAcceptBox, gbc);

        gbc.gridy = row++;
        mainForm.add(checkOnStartupBox, gbc);

        // Advanced collapsible section
        buildAdvancedPanel();
        toggleAdvancedBtn.addActionListener(e -> toggleAdvancedSection());
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        mainForm.add(toggleAdvancedBtn, gbc);

        gbc.gridy = row++;
        advancedPanel.setVisible(false);
        mainForm.add(advancedPanel, gbc);

        JScrollPane scrollPane = new JScrollPane(mainForm);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // Buttons at bottom
        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        saveButton.addActionListener(e -> onSave());
        closeButton.addActionListener(e -> dispose());
        restartButton.setVisible(false);
        restartButton.addActionListener(e -> onRestartNow());

        buttonBar.add(saveButton);
        if (mode == Mode.RUNTIME) {
            buttonBar.add(restartButton);
        }
        buttonBar.add(closeButton);

        JPanel topWrapper = new JPanel(new BorderLayout(4, 4));
        topWrapper.add(statusBanner, BorderLayout.NORTH);

        content.add(topWrapper, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(buttonBar, BorderLayout.SOUTH);

        setContentPane(content);
    }

    private void buildAdvancedPanel() {
        advancedPanel.setBorder(BorderFactory.createTitledBorder("Advanced Transfer Settings"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(3, 4, 3, 4);

        int arow = 0;
        chunkSizeField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, gbc, arow++, "Chunk Size (bytes):", chunkSizeField, "(max " + TransferProtocol.MAX_CHUNK_BYTES + ")");

        trackerReadTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, gbc, arow++, "Tracker Timeout (ms):", trackerReadTimeoutField, null);

        transferReadTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, gbc, arow++, "Transfer Read Timeout (ms):", transferReadTimeoutField, null);

        transferPromptTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, gbc, arow++, "Prompt Timeout (ms):", transferPromptTimeoutField, null);

        transferOfferTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, gbc, arow++, "Offer Response Timeout (ms):", transferOfferTimeoutField, "(must be > Prompt Timeout)");

        transferVerifyTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, gbc, arow++, "Verify Timeout (ms):", transferVerifyTimeoutField, null);

        maxConcurrentField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, gbc, arow++, "Max Concurrent Transfers:", maxConcurrentField, "(1 - 64)");
    }

    private void toggleAdvancedSection() {
        advancedVisible = !advancedVisible;
        advancedPanel.setVisible(advancedVisible);
        toggleAdvancedBtn.setText(advancedVisible ? "Hide Advanced Settings ▲" : "Show Advanced Settings ▼");
        revalidate();
        repaint();
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String labelText, Component field, String hint) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lbl = new JLabel(labelText);
        lbl.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(lbl, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        if (hint != null) {
            JPanel fieldWithHint = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            fieldWithHint.add(field);
            JLabel hintLbl = new JLabel(hint);
            hintLbl.putClientProperty("html.disable", Boolean.TRUE);
            hintLbl.setForeground(Color.GRAY);
            fieldWithHint.add(hintLbl);
            panel.add(fieldWithHint, gbc);
        } else {
            panel.add(field, gbc);
        }
    }

    private void populateFields() {
        configPathField.setText(configStore.targetPath().toString());

        if (currentSnapshot.exists()) {
            peerIdField.setText(currentSnapshot.getProperty("peer.id", ""));
            displayNameField.setText(currentSnapshot.getProperty("peer.name", ""));
            peerPortField.setText(currentSnapshot.getProperty("peer.port", "6001"));
            trackerHostField.setText(currentSnapshot.getProperty("tracker.host", "127.0.0.1"));
            trackerPortField.setText(currentSnapshot.getProperty("tracker.port", "5000"));
            downloadDirField.setText(currentSnapshot.getProperty("download.dir", "downloads"));
            sharedDirField.setText(currentSnapshot.getProperty("shared.dir", "shared"));

            boolean autoAccept = "true".equalsIgnoreCase(currentSnapshot.getProperty("transfer.autoAccept", "false"));
            autoAcceptBox.setSelected(autoAccept);

            boolean checkStartup = !"false".equalsIgnoreCase(currentSnapshot.getProperty("updates.checkOnStartup", "true"));
            checkOnStartupBox.setSelected(checkStartup);

            chunkSizeField.setText(currentSnapshot.getProperty("chunk.size.bytes", "1048576"));
            trackerReadTimeoutField.setText(currentSnapshot.getProperty("tracker.read.timeout.ms", "15000"));
            transferReadTimeoutField.setText(currentSnapshot.getProperty("transfer.read.timeout.ms", "15000"));
            transferPromptTimeoutField.setText(currentSnapshot.getProperty("transfer.prompt.timeout.ms", "120000"));
            transferOfferTimeoutField.setText(currentSnapshot.getProperty("transfer.offer.response.timeout.ms", "135000"));
            transferVerifyTimeoutField.setText(currentSnapshot.getProperty("transfer.verify.timeout.ms", "300000"));
            maxConcurrentField.setText(currentSnapshot.getProperty("transfer.max.concurrent", "4"));
        } else {
            // First run defaults
            peerIdField.setText("peer-" + UUID.randomUUID());
            displayNameField.setText("Peer");
            peerPortField.setText("6001");
            trackerHostField.setText("127.0.0.1");
            trackerPortField.setText("5000");
            downloadDirField.setText("downloads");
            sharedDirField.setText("shared");
            autoAcceptBox.setSelected(false);
            checkOnStartupBox.setSelected(true);

            chunkSizeField.setText("1048576");
            trackerReadTimeoutField.setText("15000");
            transferReadTimeoutField.setText("15000");
            transferPromptTimeoutField.setText("120000");
            transferOfferTimeoutField.setText("135000");
            transferVerifyTimeoutField.setText("300000");
            maxConcurrentField.setText("4");
        }

        updatePathPreviews();
    }

    private void updatePathPreviews() {
        Path base = configStore.workingDirectory();
        String dl = downloadDirField.getText().trim();
        if (!dl.isEmpty()) {
            Path p = Path.of(dl);
            Path resolved = p.isAbsolute() ? p.normalize() : base.resolve(p).normalize();
            downloadDirPreview.setText("Resolved: " + resolved);
        } else {
            downloadDirPreview.setText("");
        }

        String sh = sharedDirField.getText().trim();
        if (!sh.isEmpty()) {
            Path p = Path.of(sh);
            Path resolved = p.isAbsolute() ? p.normalize() : base.resolve(p).normalize();
            sharedDirPreview.setText("Resolved: " + resolved);
        } else {
            sharedDirPreview.setText("");
        }
    }

    private void browseDirectory(JTextField targetField) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        String current = targetField.getText().trim();
        if (!current.isEmpty()) {
            Path p = Path.of(current);
            Path resolved = p.isAbsolute() ? p : configStore.workingDirectory().resolve(p);
            chooser.setCurrentDirectory(resolved.toFile());
        } else {
            chooser.setCurrentDirectory(configStore.workingDirectory().toFile());
        }

        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            targetField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void onSave() {
        hideBanner();

        Map<String, String> edits = new HashMap<>();
        edits.put("peer.id", peerIdField.getText().trim());
        edits.put("peer.name", displayNameField.getText().trim());
        edits.put("peer.port", peerPortField.getText().trim());
        edits.put("tracker.host", trackerHostField.getText().trim());
        edits.put("tracker.port", trackerPortField.getText().trim());
        edits.put("download.dir", downloadDirField.getText().trim());
        edits.put("shared.dir", sharedDirField.getText().trim());
        edits.put("transfer.autoAccept", String.valueOf(autoAcceptBox.isSelected()));
        edits.put("updates.checkOnStartup", String.valueOf(checkOnStartupBox.isSelected()));

        edits.put("chunk.size.bytes", chunkSizeField.getText().trim());
        edits.put("tracker.read.timeout.ms", trackerReadTimeoutField.getText().trim());
        edits.put("transfer.read.timeout.ms", transferReadTimeoutField.getText().trim());
        edits.put("transfer.prompt.timeout.ms", transferPromptTimeoutField.getText().trim());
        edits.put("transfer.offer.response.timeout.ms", transferOfferTimeoutField.getText().trim());
        edits.put("transfer.verify.timeout.ms", transferVerifyTimeoutField.getText().trim());
        edits.put("transfer.max.concurrent", maxConcurrentField.getText().trim());

        try {
            configStore.validate(edits);
        } catch (IllegalArgumentException ex) {
            showError("Invalid settings: " + ex.getMessage());
            return;
        }

        try {
            ConfigSnapshot saved = configStore.save(currentSnapshot, edits);
            this.currentSnapshot = saved;
            this.savedSuccess = true;

            if (mode == Mode.BOOTSTRAP) {
                dispose();
            } else {
                showSuccess("Saved; applies after restart.");
                if (restartCoordinator != null && restartCoordinator.isAutomatedRestartSupported()) {
                    restartButton.setVisible(true);
                }
            }
        } catch (ConfigConflictException ex) {
            showError("Conflict: configuration was modified externally. Please reload settings.");
            try {
                this.currentSnapshot = configStore.read();
                populateFields();
            } catch (IOException ignored) {
            }
        } catch (IOException ex) {
            showError("Save failed: " + ex.getMessage());
        }
    }

    private void onRestartNow() {
        if (restartCoordinator == null || runtime == null) {
            return;
        }

        int choice = JOptionPane.showConfirmDialog(
                this,
                "Restart P2P File Sharing now to apply saved settings?\nActive transfers will prevent restart.",
                "Confirm Restart",
                JOptionPane.YES_NO_OPTION
        );
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        new Thread(() -> {
            try {
                restartCoordinator.restartForSettings(runtime, configStore, msg -> {
                    SwingUtilities.invokeLater(() -> showSuccess(msg));
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError("Restart failed: " + ex.getMessage()));
            }
        }, "settings-restart-worker").start();
    }

    private void showError(String message) {
        statusBanner.setBackground(new Color(255, 230, 230));
        statusBanner.setForeground(new Color(180, 0, 0));
        statusBanner.setText(message);
        statusBanner.setVisible(true);
    }

    private void showSuccess(String message) {
        statusBanner.setBackground(new Color(230, 255, 230));
        statusBanner.setForeground(new Color(0, 140, 0));
        statusBanner.setText(message);
        statusBanner.setVisible(true);
    }

    private void hideBanner() {
        statusBanner.setVisible(false);
    }

    private static class SimpleDocumentListener implements javax.swing.event.DocumentListener {
        private final Runnable callback;
        SimpleDocumentListener(Runnable callback) { this.callback = callback; }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { callback.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { callback.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { callback.run(); }
    }
}
