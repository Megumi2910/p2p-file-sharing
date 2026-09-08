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
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SettingsDialog extends JDialog {

    public enum Mode {
        BOOTSTRAP,
        BOOTSTRAP_SYNTAX_REPAIR,
        RUNTIME
    }

    private Mode mode;
    private final ConfigStore configStore;
    private final PeerRuntime runtime;
    private final RestartCoordinator restartCoordinator;
    private ConfigStore.SyntaxException syntaxException;
    private boolean cancelledInit = false;

    private ConfigSnapshot currentSnapshot;
    private boolean savedSuccess = false;
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

    // Syntax repair text area
    private JTextArea syntaxTextArea;

    // Status / Error banner
    private final JLabel statusBanner = new JLabel();
    private final JButton saveButton;
    private final JButton restartButton = new JButton("Restart now");
    private final JButton reloadButton = new JButton("Reload from disk");
    private final JButton closeButton;

    public static boolean showBootstrapDialog(ConfigStore configStore, String initialError) {
        return showBootstrapDialog(configStore, initialError, null);
    }

    public static boolean showBootstrapDialog(
            ConfigStore configStore,
            String initialError,
            ConfigStore.SyntaxException syntaxException
    ) {
        if (SwingUtilities.isEventDispatchThread()) {
            return showBootstrapDialogOnEdt(configStore, initialError, syntaxException);
        }
        final AtomicBoolean result = new AtomicBoolean(false);
        try {
            SwingUtilities.invokeAndWait(() -> {
                result.set(showBootstrapDialogOnEdt(configStore, initialError, syntaxException));
            });
        } catch (Exception ex) {
            return false;
        }
        return result.get();
    }

    private static boolean showBootstrapDialogOnEdt(
            ConfigStore configStore,
            String initialError,
            ConfigStore.SyntaxException syntaxException
    ) {
        SettingsDialog dialog = new SettingsDialog(
                (Dialog) null,
                configStore,
                null,
                null,
                syntaxException != null ? Mode.BOOTSTRAP_SYNTAX_REPAIR : Mode.BOOTSTRAP,
                syntaxException
        );
        if (dialog.cancelledInit) {
            dialog.dispose();
            return false;
        }
        if (initialError != null && !initialError.isBlank()) {
            dialog.showError("Configuration error: " + initialError);
        }
        dialog.setVisible(true);
        dialog.dispose();
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
        this.syntaxException = null;
        this.saveButton = new JButton("Save settings");
        this.closeButton = new JButton("Close");
        init();
    }

    public SettingsDialog(
            Dialog owner,
            ConfigStore configStore,
            PeerRuntime runtime,
            RestartCoordinator restartCoordinator,
            Mode mode
    ) {
        this(owner, configStore, runtime, restartCoordinator, mode, null);
    }

    public SettingsDialog(
            Dialog owner,
            ConfigStore configStore,
            PeerRuntime runtime,
            RestartCoordinator restartCoordinator,
            Mode mode,
            ConfigStore.SyntaxException syntaxException
    ) {
        super(owner, mode == Mode.RUNTIME ? "Settings" : "P2P File Sharing - Setup",
                mode == Mode.RUNTIME ? ModalityType.MODELESS : ModalityType.APPLICATION_MODAL);
        this.mode = mode;
        this.configStore = Objects.requireNonNull(configStore, "configStore cannot be null");
        this.runtime = runtime;
        this.restartCoordinator = restartCoordinator;
        this.syntaxException = syntaxException;
        if (mode == Mode.RUNTIME) {
            this.saveButton = new JButton("Save settings");
            this.closeButton = new JButton("Close");
        } else {
            this.saveButton = new JButton("Save and start");
            this.closeButton = new JButton("Cancel");
        }
        init();
    }

    private void init() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(600, 480));
        setPreferredSize(new Dimension(680, 600));

        if (mode == Mode.BOOTSTRAP_SYNTAX_REPAIR && syntaxException != null) {
            this.currentSnapshot = syntaxException.snapshot();
        } else {
            boolean loaded = false;
            while (!loaded) {
                try {
                    this.currentSnapshot = configStore.read();
                    loaded = true;
                } catch (ConfigStore.SyntaxException ex) {
                    this.mode = Mode.BOOTSTRAP_SYNTAX_REPAIR;
                    this.syntaxException = ex;
                    this.currentSnapshot = ex.snapshot();
                    loaded = true;
                } catch (IOException ex) {
                    int option = JOptionPane.showOptionDialog(
                            this,
                            "Failed to read configuration: " + ex.getMessage() + "\nRetry reading or cancel?",
                            "Configuration Read Error",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.ERROR_MESSAGE,
                            null,
                            new Object[]{"Retry", "Cancel"},
                            "Retry"
                    );
                    if (option != JOptionPane.YES_OPTION) {
                        cancelledInit = true;
                        dispose();
                        return;
                    }
                }
            }
        }

        buildUi();
        if (mode != Mode.BOOTSTRAP_SYNTAX_REPAIR) {
            populateFields();
        }

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

        statusBanner.setVisible(false);
        statusBanner.putClientProperty("html.disable", Boolean.TRUE);
        statusBanner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        statusBanner.setOpaque(true);

        if (mode == Mode.BOOTSTRAP_SYNTAX_REPAIR) {
            JLabel repairLabel = new JLabel("Configuration syntax error. Please repair the properties source below and click 'Save and start':");
            repairLabel.putClientProperty("html.disable", Boolean.TRUE);

            syntaxTextArea = new JTextArea(syntaxException != null ? syntaxException.sourceText() : "");
            syntaxTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            syntaxTextArea.setTabSize(4);
            syntaxTextArea.setCaretPosition(0);
            JScrollPane scrollPane = new JScrollPane(syntaxTextArea);

            JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            saveButton.addActionListener(e -> onSave());
            closeButton.addActionListener(e -> dispose());
            reloadButton.setVisible(false);
            reloadButton.addActionListener(e -> onReload());

            buttonBar.add(reloadButton);
            buttonBar.add(saveButton);
            buttonBar.add(closeButton);

            JPanel topWrapper = new JPanel(new BorderLayout(4, 4));
            topWrapper.add(statusBanner, BorderLayout.NORTH);
            topWrapper.add(repairLabel, BorderLayout.SOUTH);

            content.add(topWrapper, BorderLayout.NORTH);
            content.add(scrollPane, BorderLayout.CENTER);
            content.add(buttonBar, BorderLayout.SOUTH);
            setContentPane(content);
            return;
        }

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
        reloadButton.setVisible(false);
        reloadButton.addActionListener(e -> onReload());

        buttonBar.add(reloadButton);
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
            String existingId = currentSnapshot.getProperty("peer.id", "");
            peerIdField.setText(existingId);
            if (mode == Mode.RUNTIME) {
                peerIdField.setEditable(false);
            } else {
                boolean validId = false;
                if (!existingId.isBlank()) {
                    try {
                        AppConfig.parsePeerId(existingId);
                        validId = true;
                    } catch (Exception ignored) {
                    }
                }
                peerIdField.setEditable(!validId);
            }

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
            if (peerIdField.getText().isBlank()) {
                peerIdField.setText("peer-" + UUID.randomUUID());
            }
            peerIdField.setEditable(true);

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
        updateDifferingFieldsNotice();
    }

    private void updateDifferingFieldsNotice() {
        if (mode != Mode.RUNTIME || runtime == null || runtime.config() == null || currentSnapshot == null || !currentSnapshot.exists()) {
            return;
        }

        AppConfig rc = runtime.config();
        List<String> differing = new ArrayList<>();
        if (!Objects.equals(currentSnapshot.getProperty("peer.name", ""), rc.displayName())) differing.add("Display Name");
        if (!Objects.equals(currentSnapshot.getProperty("peer.port", "6001"), String.valueOf(rc.peerPort()))) differing.add("Listen Port");
        if (!Objects.equals(currentSnapshot.getProperty("tracker.host", "127.0.0.1"), rc.trackerHost())) differing.add("Tracker Host");
        if (!Objects.equals(currentSnapshot.getProperty("tracker.port", "5000"), String.valueOf(rc.trackerPort()))) differing.add("Tracker Port");
        if (!Objects.equals(currentSnapshot.getProperty("download.dir", "downloads"), rc.downloadDir().toString())) differing.add("Download Folder");
        if (!Objects.equals(currentSnapshot.getProperty("shared.dir", "shared"), rc.sharedDir().toString())) differing.add("Shared Folder");
        if (!Objects.equals(currentSnapshot.getProperty("transfer.autoAccept", "false"), String.valueOf(rc.autoAccept()))) differing.add("Auto Accept");
        if (!Objects.equals(currentSnapshot.getProperty("chunk.size.bytes", "1048576"), String.valueOf(rc.chunkSizeBytes()))) differing.add("Chunk Size");
        if (!Objects.equals(currentSnapshot.getProperty("tracker.read.timeout.ms", "15000"), String.valueOf(rc.trackerReadTimeoutMillis()))) differing.add("Tracker Timeout");
        if (!Objects.equals(currentSnapshot.getProperty("transfer.read.timeout.ms", "15000"), String.valueOf(rc.transferReadTimeoutMillis()))) differing.add("Transfer Read Timeout");
        if (!Objects.equals(currentSnapshot.getProperty("transfer.prompt.timeout.ms", "120000"), String.valueOf(rc.transferPromptTimeoutMillis()))) differing.add("Prompt Timeout");
        if (!Objects.equals(currentSnapshot.getProperty("transfer.offer.response.timeout.ms", "135000"), String.valueOf(rc.transferOfferResponseTimeoutMillis()))) differing.add("Offer Timeout");
        if (!Objects.equals(currentSnapshot.getProperty("transfer.verify.timeout.ms", "300000"), String.valueOf(rc.transferVerifyTimeoutMillis()))) differing.add("Verify Timeout");
        if (!Objects.equals(currentSnapshot.getProperty("transfer.max.concurrent", "4"), String.valueOf(rc.maxConcurrentTransfers()))) differing.add("Max Concurrent");

        if (!differing.isEmpty()) {
            showInfo("Saved; applies after restart. Differing fields: " + String.join(", ", differing));
        }
    }

    private void updatePathPreviews() {
        Path base = configStore.workingDirectory();
        try {
            String dl = downloadDirField.getText().trim();
            if (!dl.isEmpty()) {
                Path p = Path.of(dl);
                Path resolved = p.isAbsolute() ? p.normalize() : base.resolve(p).normalize();
                downloadDirPreview.setText("Resolved: " + resolved);
            } else {
                downloadDirPreview.setText("");
            }
        } catch (InvalidPathException ex) {
            downloadDirPreview.setText("Invalid path syntax");
        }

        try {
            String sh = sharedDirField.getText().trim();
            if (!sh.isEmpty()) {
                Path p = Path.of(sh);
                Path resolved = p.isAbsolute() ? p.normalize() : base.resolve(p).normalize();
                sharedDirPreview.setText("Resolved: " + resolved);
            } else {
                sharedDirPreview.setText("");
            }
        } catch (InvalidPathException ex) {
            sharedDirPreview.setText("Invalid path syntax");
        }
    }

    private void browseDirectory(JTextField targetField) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);

        Path startDir;
        try {
            String current = targetField.getText().trim();
            if (!current.isEmpty()) {
                Path p = Path.of(current);
                startDir = p.isAbsolute() ? p : configStore.workingDirectory().resolve(p);
            } else {
                startDir = configStore.workingDirectory();
            }
        } catch (InvalidPathException ex) {
            startDir = configStore.workingDirectory();
        }
        chooser.setCurrentDirectory(startDir.toFile());

        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            targetField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void checkAndPutEdit(Map<String, String> edits, String key, String newValue) {
        if (!currentSnapshot.exists()) {
            edits.put(key, newValue);
        } else {
            String existing = currentSnapshot.getProperty(key);
            if (!Objects.equals(existing, newValue)) {
                edits.put(key, newValue);
            }
        }
    }

    private void onSave() {
        hideBanner();

        if (mode == Mode.BOOTSTRAP_SYNTAX_REPAIR) {
            String source = syntaxTextArea != null ? syntaxTextArea.getText() : "";
            saveButton.setEnabled(false);
            reloadButton.setEnabled(false);
            closeButton.setEnabled(false);

            new SwingWorker<ConfigSnapshot, Void>() {
                @Override
                protected ConfigSnapshot doInBackground() throws Exception {
                    return configStore.repairSyntax(currentSnapshot, source);
                }

                @Override
                protected void done() {
                    saveButton.setEnabled(true);
                    reloadButton.setEnabled(true);
                    closeButton.setEnabled(true);
                    try {
                        currentSnapshot = get();
                        savedSuccess = true;
                        dispose();
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        if (cause instanceof ConfigConflictException) {
                            showError("Conflict: configuration was modified externally. Please reload settings.");
                            reloadButton.setVisible(true);
                        } else {
                            showError("Save failed: " + cause.getMessage());
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }.execute();
            return;
        }

        Map<String, String> edits = new LinkedHashMap<>();
        checkAndPutEdit(edits, "peer.id", peerIdField.getText().trim());
        checkAndPutEdit(edits, "peer.name", displayNameField.getText().trim());
        checkAndPutEdit(edits, "peer.port", peerPortField.getText().trim());
        checkAndPutEdit(edits, "tracker.host", trackerHostField.getText().trim());
        checkAndPutEdit(edits, "tracker.port", trackerPortField.getText().trim());
        checkAndPutEdit(edits, "download.dir", downloadDirField.getText().trim());
        checkAndPutEdit(edits, "shared.dir", sharedDirField.getText().trim());
        checkAndPutEdit(edits, "transfer.autoAccept", String.valueOf(autoAcceptBox.isSelected()));
        checkAndPutEdit(edits, "updates.checkOnStartup", String.valueOf(checkOnStartupBox.isSelected()));

        checkAndPutEdit(edits, "chunk.size.bytes", chunkSizeField.getText().trim());
        checkAndPutEdit(edits, "tracker.read.timeout.ms", trackerReadTimeoutField.getText().trim());
        checkAndPutEdit(edits, "transfer.read.timeout.ms", transferReadTimeoutField.getText().trim());
        checkAndPutEdit(edits, "transfer.prompt.timeout.ms", transferPromptTimeoutField.getText().trim());
        checkAndPutEdit(edits, "transfer.offer.response.timeout.ms", transferOfferTimeoutField.getText().trim());
        checkAndPutEdit(edits, "transfer.verify.timeout.ms", transferVerifyTimeoutField.getText().trim());
        checkAndPutEdit(edits, "transfer.max.concurrent", maxConcurrentField.getText().trim());

        Map<String, String> merged = new LinkedHashMap<>(currentSnapshot.properties());
        merged.putAll(edits);

        try {
            configStore.validate(merged);
        } catch (IllegalArgumentException ex) {
            showError("Invalid settings: " + ex.getMessage());
            return;
        }

        saveButton.setEnabled(false);
        reloadButton.setEnabled(false);
        closeButton.setEnabled(false);

        new SwingWorker<ConfigSnapshot, Void>() {
            @Override
            protected ConfigSnapshot doInBackground() throws Exception {
                return configStore.save(currentSnapshot, edits);
            }

            @Override
            protected void done() {
                saveButton.setEnabled(true);
                reloadButton.setEnabled(true);
                closeButton.setEnabled(true);
                try {
                    currentSnapshot = get();
                    savedSuccess = true;
                    reloadButton.setVisible(false);

                    if (mode == Mode.BOOTSTRAP) {
                        dispose();
                    } else {
                        showSuccess("Saved; applies after restart.");
                        updateDifferingFieldsNotice();
                        if (restartCoordinator != null && restartCoordinator.isAutomatedRestartSupported()) {
                            restartButton.setVisible(true);
                        }
                    }
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof ConfigConflictException) {
                        showError("Conflict: configuration was modified externally. Please reload settings.");
                        reloadButton.setVisible(true);
                    } else {
                        showError("Save failed: " + cause.getMessage());
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void onReload() {
        hideBanner();
        saveButton.setEnabled(false);
        reloadButton.setEnabled(false);

        new SwingWorker<ConfigSnapshot, Void>() {
            @Override
            protected ConfigSnapshot doInBackground() throws Exception {
                return configStore.read();
            }

            @Override
            protected void done() {
                saveButton.setEnabled(true);
                reloadButton.setEnabled(true);
                try {
                    currentSnapshot = get();
                    reloadButton.setVisible(false);
                    if (mode == Mode.BOOTSTRAP_SYNTAX_REPAIR) {
                        mode = Mode.BOOTSTRAP;
                        syntaxException = null;
                        buildUi();
                        populateFields();
                        revalidate();
                        repaint();
                        showSuccess("Configuration on disk is now valid. Review settings and click Save and start.");
                    } else {
                        populateFields();
                        showSuccess("Reloaded latest configuration from disk.");
                    }
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof ConfigStore.SyntaxException synEx) {
                        currentSnapshot = synEx.snapshot();
                        syntaxException = synEx;
                        reloadButton.setVisible(false);
                        if (mode == Mode.BOOTSTRAP_SYNTAX_REPAIR && syntaxTextArea != null) {
                            syntaxTextArea.setText(synEx.sourceText());
                            syntaxTextArea.setCaretPosition(0);
                            showSuccess("Reloaded external changes. Please repair and click Save and start.");
                        } else {
                            mode = Mode.BOOTSTRAP_SYNTAX_REPAIR;
                            buildUi();
                            revalidate();
                            repaint();
                            showSuccess("Configuration on disk has syntax errors. Please repair.");
                        }
                    } else {
                        showError("Reload failed: " + cause.getMessage());
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }
    private void onRestartNow() {
        if (restartCoordinator == null || runtime == null) {
            return;
        }

        if (hasUnsavedChanges()) {
            showError("Save or cancel settings first");
            return;
        }

        int choice = JOptionPane.showConfirmDialog(
                this,
                "Restart P2P File Sharing now to apply saved settings?\nActive transfers or prompt decisions will prevent restart.",
                "Confirm Restart",
                JOptionPane.YES_NO_OPTION
        );
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        saveButton.setEnabled(false);
        restartButton.setEnabled(false);
        closeButton.setEnabled(false);
        if (getOwner() instanceof MainFrame mainFrame) {
            mainFrame.freezeForRestart(true);
        }

        new Thread(() -> {
            try {
                restartCoordinator.restartForSettings(runtime, configStore, msg -> {
                    SwingUtilities.invokeLater(() -> showSuccess(msg));
                });
            } catch (RestartCoordinator.RestartFailure ex) {
                SwingUtilities.invokeLater(() -> {
                    showError("Restart stopped: " + ex.getMessage());
                    if (getOwner() instanceof MainFrame mainFrame) {
                        mainFrame.setStopped("Stopped — relaunch manually\nConfig: " + configStore.targetPath() + "\nCwd: " + configStore.workingDirectory() + "\nError: " + ex.getMessage());
                    }
                    dispose();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    saveButton.setEnabled(true);
                    restartButton.setEnabled(true);
                    closeButton.setEnabled(true);
                    if (getOwner() instanceof MainFrame mainFrame) {
                        mainFrame.freezeForRestart(false);
                    }
                    showError("Restart failed: " + ex.getMessage());
                });
            }
        }, "settings-restart-worker").start();
    }

    public void freezeEditing(boolean freeze) {
        SwingUtilities.invokeLater(() -> {
            saveButton.setEnabled(!freeze);
            if (restartButton != null) restartButton.setEnabled(!freeze);
            if (reloadButton != null) reloadButton.setEnabled(!freeze);
        });
    }

    public boolean hasUnsavedChanges() {
        if (mode == Mode.BOOTSTRAP_SYNTAX_REPAIR) {
            return syntaxTextArea != null && syntaxException != null
                    && !Objects.equals(syntaxException.sourceText(), syntaxTextArea.getText());
        }
        if (currentSnapshot == null) {
            return false;
        }
        if (!currentSnapshot.exists()) {
            return true;
        }
        if (!Objects.equals(currentSnapshot.getProperty("peer.id", "").trim(), peerIdField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("peer.name", "").trim(), displayNameField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("peer.port", "6001").trim(), peerPortField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("tracker.host", "127.0.0.1").trim(), trackerHostField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("tracker.port", "5000").trim(), trackerPortField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("download.dir", "downloads").trim(), downloadDirField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("shared.dir", "shared").trim(), sharedDirField.getText().trim())) return true;
        boolean autoAccept = "true".equalsIgnoreCase(currentSnapshot.getProperty("transfer.autoAccept", "false"));
        if (autoAccept != autoAcceptBox.isSelected()) return true;
        boolean checkStartup = !"false".equalsIgnoreCase(currentSnapshot.getProperty("updates.checkOnStartup", "true"));
        if (checkStartup != checkOnStartupBox.isSelected()) return true;

        if (!Objects.equals(currentSnapshot.getProperty("chunk.size.bytes", "1048576").trim(), chunkSizeField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("tracker.read.timeout.ms", "15000").trim(), trackerReadTimeoutField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("transfer.read.timeout.ms", "15000").trim(), transferReadTimeoutField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("transfer.prompt.timeout.ms", "120000").trim(), transferPromptTimeoutField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("transfer.offer.response.timeout.ms", "135000").trim(), transferOfferTimeoutField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("transfer.verify.timeout.ms", "300000").trim(), transferVerifyTimeoutField.getText().trim())) return true;
        if (!Objects.equals(currentSnapshot.getProperty("transfer.max.concurrent", "4").trim(), maxConcurrentField.getText().trim())) return true;

        return false;
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

    private void showInfo(String message) {
        statusBanner.setBackground(new Color(230, 240, 255));
        statusBanner.setForeground(new Color(0, 70, 160));
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
