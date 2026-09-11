package vn.edu.p2p.peer.ui;
import com.formdev.flatlaf.util.UIScale;

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
import javax.swing.UIManager;
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
import java.awt.Rectangle;
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

    private static JTextArea createWrappingPreviewArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.putClientProperty("FlatLaf.styleClass", "muted");
        area.putClientProperty("html.disable", Boolean.TRUE);
        return area;
    }

    private static JTextArea createWrappingBannerArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(true);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.putClientProperty("html.disable", Boolean.TRUE);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        area.setVisible(false);
        return area;
    }

    private static JTextArea createWrappingLabel(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.putClientProperty("html.disable", Boolean.TRUE);
        return area;
    }

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
    private final JTextArea downloadDirPreview = createWrappingPreviewArea();
    private final JTextField sharedDirField = new JTextField(24);
    private final JTextArea sharedDirPreview = createWrappingPreviewArea();
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
    private final JTextArea statusBanner = createWrappingBannerArea();
    private final JButton saveButton;
    private final JButton restartButton = new JButton("Restart now");
    private final JButton reloadButton = new JButton("Reload from disk");
    private final JButton closeButton;
    private volatile boolean operationInProgress = false;

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

        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        // Escape closes
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeDialog");
        getRootPane().getActionMap().put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                requestClose();
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });
        closeButton.addActionListener(e -> requestClose());
        DesktopLayout.fitWindow(this, new Dimension(680, 600), new Dimension(600, 480));
        setLocationRelativeTo(getOwner());
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(0, UIScale.scale(8)));
        content.setBorder(BorderFactory.createEmptyBorder(
                UIScale.scale(12), UIScale.scale(16), UIScale.scale(12), UIScale.scale(16)
        ));

        if (mode == Mode.BOOTSTRAP_SYNTAX_REPAIR) {
            JTextArea repairLabel = createWrappingLabel(
                    "Configuration syntax error. Please repair the properties source below and click '" + saveButton.getText() + "':"
            );

            syntaxTextArea = new JTextArea(syntaxException != null ? syntaxException.sourceText() : "");
            syntaxTextArea.putClientProperty("FlatLaf.styleClass", "monospaced");
            syntaxTextArea.setTabSize(4);
            syntaxTextArea.setCaretPosition(0);
            JScrollPane scrollPane = new JScrollPane(syntaxTextArea);

            JPanel topWrapper = new JPanel(new BorderLayout(0, UIScale.scale(4)));
            topWrapper.add(statusBanner, BorderLayout.NORTH);
            topWrapper.add(repairLabel, BorderLayout.SOUTH);

            content.add(topWrapper, BorderLayout.NORTH);
            content.add(scrollPane, BorderLayout.CENTER);
            content.add(buildFooterPanel(), BorderLayout.SOUTH);
            setContentPane(content);
            return;
        }

        JPanel mainForm = new JPanel(new GridBagLayout());
        mainForm.setBorder(BorderFactory.createEmptyBorder(
                UIScale.scale(4), UIScale.scale(4), UIScale.scale(4), UIScale.scale(4)
        ));

        int row = 0;

        // Configuration Path (readonly)
        configPathField.setEditable(false);
        configPathField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(mainForm, row++, "Config File:", configPathField, null);

        // Peer ID
        peerIdField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(mainForm, row++, "Peer ID:", peerIdField, "(unique identity)");

        // Display Name
        displayNameField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(mainForm, row++, "Display Name:", displayNameField, null);

        // Listen Port
        peerPortField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(mainForm, row++, "Listen Port:", peerPortField, "(1-65535)");

        // Tracker Host & Port
        JPanel trackerPanel = new JPanel(new GridBagLayout());
        trackerPanel.setOpaque(false);
        trackerHostField.putClientProperty("html.disable", Boolean.TRUE);
        trackerPortField.putClientProperty("html.disable", Boolean.TRUE);
        trackerPortField.setColumns(6);

        GridBagConstraints tgbc1 = new GridBagConstraints();
        tgbc1.gridx = 0;
        tgbc1.gridy = 0;
        tgbc1.weightx = 1.0;
        tgbc1.fill = GridBagConstraints.HORIZONTAL;
        tgbc1.insets = new Insets(0, 0, 0, UIScale.scale(4));
        trackerPanel.add(trackerHostField, tgbc1);

        JLabel colonLabel = new JLabel(":");
        colonLabel.putClientProperty("html.disable", Boolean.TRUE);
        GridBagConstraints tgbc2 = new GridBagConstraints();
        tgbc2.gridx = 1;
        tgbc2.gridy = 0;
        tgbc2.weightx = 0.0;
        tgbc2.fill = GridBagConstraints.NONE;
        tgbc2.insets = new Insets(0, 0, 0, UIScale.scale(4));
        trackerPanel.add(colonLabel, tgbc2);

        GridBagConstraints tgbc3 = new GridBagConstraints();
        tgbc3.gridx = 2;
        tgbc3.gridy = 0;
        tgbc3.weightx = 0.0;
        tgbc3.fill = GridBagConstraints.NONE;
        trackerPanel.add(trackerPortField, tgbc3);

        addFormRow(mainForm, row++, "Tracker Address:", trackerPanel, null);

        // Download Directory + Browse
        JPanel downloadPanel = new JPanel(new BorderLayout(UIScale.scale(4), 0));
        downloadPanel.setOpaque(false);
        downloadDirField.putClientProperty("html.disable", Boolean.TRUE);
        JButton browseDownloadBtn = new JButton("Browse...");
        browseDownloadBtn.addActionListener(e -> browseDirectory(downloadDirField));
        downloadPanel.add(downloadDirField, BorderLayout.CENTER);
        downloadPanel.add(browseDownloadBtn, BorderLayout.EAST);
        downloadDirField.getDocument().addDocumentListener(new SimpleDocumentListener(this::updatePathPreviews));

        JPanel downloadWrapper = new JPanel(new BorderLayout(0, UIScale.scale(2)));
        downloadWrapper.setOpaque(false);
        downloadWrapper.add(downloadPanel, BorderLayout.NORTH);
        downloadWrapper.add(downloadDirPreview, BorderLayout.SOUTH);
        addFormRow(mainForm, row++, "Download Folder:", downloadWrapper, null);

        // Shared Directory + Browse
        JPanel sharedPanel = new JPanel(new BorderLayout(UIScale.scale(4), 0));
        sharedPanel.setOpaque(false);
        sharedDirField.putClientProperty("html.disable", Boolean.TRUE);
        JButton browseSharedBtn = new JButton("Browse...");
        browseSharedBtn.addActionListener(e -> browseDirectory(sharedDirField));
        sharedPanel.add(sharedDirField, BorderLayout.CENTER);
        sharedPanel.add(browseSharedBtn, BorderLayout.EAST);
        sharedDirField.getDocument().addDocumentListener(new SimpleDocumentListener(this::updatePathPreviews));

        JPanel sharedWrapper = new JPanel(new BorderLayout(0, UIScale.scale(2)));
        sharedWrapper.setOpaque(false);
        sharedWrapper.add(sharedPanel, BorderLayout.NORTH);
        sharedWrapper.add(sharedDirPreview, BorderLayout.SOUTH);
        addFormRow(mainForm, row++, "Shared Folder:", sharedWrapper, null);

        // Checkboxes
        autoAcceptBox.putClientProperty("html.disable", Boolean.TRUE);
        checkOnStartupBox.putClientProperty("html.disable", Boolean.TRUE);

        GridBagConstraints cbgc1 = new GridBagConstraints();
        cbgc1.gridx = 1;
        cbgc1.gridy = row++;
        cbgc1.weightx = 1.0;
        cbgc1.anchor = GridBagConstraints.WEST;
        cbgc1.fill = GridBagConstraints.HORIZONTAL;
        cbgc1.insets = new Insets(UIScale.scale(4), UIScale.scale(4), UIScale.scale(2), UIScale.scale(4));
        mainForm.add(autoAcceptBox, cbgc1);

        GridBagConstraints cbgc2 = new GridBagConstraints();
        cbgc2.gridx = 1;
        cbgc2.gridy = row++;
        cbgc2.weightx = 1.0;
        cbgc2.anchor = GridBagConstraints.WEST;
        cbgc2.fill = GridBagConstraints.HORIZONTAL;
        cbgc2.insets = new Insets(UIScale.scale(2), UIScale.scale(4), UIScale.scale(4), UIScale.scale(4));
        mainForm.add(checkOnStartupBox, cbgc2);

        // Advanced collapsible section
        buildAdvancedPanel();
        toggleAdvancedBtn.addActionListener(e -> toggleAdvancedSection());

        GridBagConstraints tgbc = new GridBagConstraints();
        tgbc.gridx = 0;
        tgbc.gridy = row++;
        tgbc.gridwidth = 2;
        tgbc.weightx = 1.0;
        tgbc.anchor = GridBagConstraints.WEST;
        tgbc.fill = GridBagConstraints.HORIZONTAL;
        tgbc.insets = new Insets(UIScale.scale(8), UIScale.scale(4), UIScale.scale(4), UIScale.scale(4));
        mainForm.add(toggleAdvancedBtn, tgbc);

        GridBagConstraints agbc = new GridBagConstraints();
        agbc.gridx = 0;
        agbc.gridy = row++;
        agbc.gridwidth = 2;
        agbc.weightx = 1.0;
        agbc.anchor = GridBagConstraints.WEST;
        agbc.fill = GridBagConstraints.HORIZONTAL;
        agbc.insets = new Insets(UIScale.scale(4), UIScale.scale(4), UIScale.scale(4), UIScale.scale(4));
        advancedPanel.setVisible(false);
        mainForm.add(advancedPanel, agbc);

        JScrollPane scrollPane = new JScrollPane(mainForm);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(UIScale.scale(16));

        JPanel topWrapper = new JPanel(new BorderLayout(0, UIScale.scale(4)));
        topWrapper.add(statusBanner, BorderLayout.NORTH);

        content.add(topWrapper, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(buildFooterPanel(), BorderLayout.SOUTH);

        setContentPane(content);
    }

    private void buildAdvancedPanel() {
        advancedPanel.setBorder(BorderFactory.createTitledBorder("Advanced Transfer Settings"));
        int arow = 0;
        chunkSizeField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, arow++, "Chunk Size (bytes):", chunkSizeField, "(max " + TransferProtocol.MAX_CHUNK_BYTES + ")");

        trackerReadTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, arow++, "Tracker Timeout (ms):", trackerReadTimeoutField, null);

        transferReadTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, arow++, "Transfer Read Timeout (ms):", transferReadTimeoutField, null);

        transferPromptTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, arow++, "Prompt Timeout (ms):", transferPromptTimeoutField, null);

        transferOfferTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, arow++, "Offer Response Timeout (ms):", transferOfferTimeoutField, "(must be > Prompt Timeout)");

        transferVerifyTimeoutField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, arow++, "Verify Timeout (ms):", transferVerifyTimeoutField, null);

        maxConcurrentField.putClientProperty("html.disable", Boolean.TRUE);
        addFormRow(advancedPanel, arow++, "Max Concurrent Transfers:", maxConcurrentField, "(1 - 64)");
    }

    private void toggleAdvancedSection() {
        advancedVisible = !advancedVisible;
        advancedPanel.setVisible(advancedVisible);
        toggleAdvancedBtn.setText(advancedVisible ? "Hide Advanced Settings ▲" : "Show Advanced Settings ▼");
        revalidate();
        repaint();
        if (advancedVisible) {
            SwingUtilities.invokeLater(() -> toggleAdvancedBtn.scrollRectToVisible(
                    new Rectangle(0, 0, toggleAdvancedBtn.getWidth(), toggleAdvancedBtn.getHeight())
            ));
        }
    }

    private void addFormRow(JPanel panel, int row, String labelText, Component field, String hint) {
        GridBagConstraints lgbc = new GridBagConstraints();
        lgbc.gridx = 0;
        lgbc.gridy = row;
        lgbc.gridwidth = 1;
        lgbc.weightx = 0.0;
        lgbc.anchor = GridBagConstraints.NORTHWEST;
        lgbc.fill = GridBagConstraints.NONE;
        lgbc.insets = new Insets(UIScale.scale(4), UIScale.scale(4), UIScale.scale(4), UIScale.scale(8));

        JLabel lbl = new JLabel(labelText);
        lbl.putClientProperty("html.disable", Boolean.TRUE);
        lbl.setLabelFor(field);
        panel.add(lbl, lgbc);

        GridBagConstraints fgbc = new GridBagConstraints();
        fgbc.gridx = 1;
        fgbc.gridy = row;
        fgbc.gridwidth = 1;
        fgbc.weightx = 1.0;
        fgbc.anchor = GridBagConstraints.NORTHWEST;
        fgbc.fill = GridBagConstraints.HORIZONTAL;
        fgbc.insets = new Insets(UIScale.scale(4), UIScale.scale(4), UIScale.scale(4), UIScale.scale(4));

        if (hint != null && !hint.isBlank()) {
            JPanel fieldWithHint = new JPanel(new GridBagLayout());
            fieldWithHint.setOpaque(false);

            GridBagConstraints c1 = new GridBagConstraints();
            c1.gridx = 0;
            c1.gridy = 0;
            c1.weightx = 1.0;
            c1.fill = GridBagConstraints.HORIZONTAL;
            c1.anchor = GridBagConstraints.WEST;
            c1.insets = new Insets(0, 0, UIScale.scale(2), 0);
            fieldWithHint.add(field, c1);

            JTextArea hintArea = new JTextArea(hint);
            hintArea.setEditable(false);
            hintArea.setFocusable(false);
            hintArea.setOpaque(false);
            hintArea.setLineWrap(true);
            hintArea.setWrapStyleWord(true);
            hintArea.putClientProperty("FlatLaf.styleClass", "muted");
            hintArea.putClientProperty("html.disable", Boolean.TRUE);

            GridBagConstraints c2 = new GridBagConstraints();
            c2.gridx = 0;
            c2.gridy = 1;
            c2.weightx = 1.0;
            c2.fill = GridBagConstraints.HORIZONTAL;
            c2.anchor = GridBagConstraints.WEST;
            c2.insets = new Insets(0, 0, 0, 0);
            fieldWithHint.add(hintArea, c2);

            panel.add(fieldWithHint, fgbc);
        } else {
            panel.add(field, fgbc);
        }
    }

    private JPanel buildFooterPanel() {
        JPanel footer = new JPanel(new GridBagLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(6), 0, 0, 0));

        // Row 0: conditional actions (Reload from disk, Restart now)
        JPanel topRow = new JPanel(new GridBagLayout());
        topRow.setOpaque(false);
        GridBagConstraints tgbc = new GridBagConstraints();
        tgbc.insets = new Insets(0, UIScale.scale(4), UIScale.scale(4), UIScale.scale(4));

        reloadButton.setVisible(false);
        reloadButton.addActionListener(e -> onReload());
        tgbc.gridx = 0;
        topRow.add(reloadButton, tgbc);

        if (mode == Mode.RUNTIME) {
            restartButton.setVisible(false);
            restartButton.addActionListener(e -> onRestartNow());
            tgbc.gridx = 1;
            topRow.add(restartButton, tgbc);
        }

        GridBagConstraints fgbc0 = new GridBagConstraints();
        fgbc0.gridx = 0;
        fgbc0.gridy = 0;
        fgbc0.weightx = 1.0;
        fgbc0.anchor = GridBagConstraints.EAST;
        fgbc0.fill = GridBagConstraints.NONE;
        footer.add(topRow, fgbc0);

        // Row 1: main actions (Save, Close)
        JPanel bottomRow = new JPanel(new GridBagLayout());
        bottomRow.setOpaque(false);
        GridBagConstraints bgbc = new GridBagConstraints();
        bgbc.insets = new Insets(0, UIScale.scale(4), 0, UIScale.scale(4));

        saveButton.putClientProperty("FlatLaf.styleClass", "primary");
        saveButton.addActionListener(e -> onSave());
        bgbc.gridx = 0;
        bottomRow.add(saveButton, bgbc);
        bgbc.gridx = 1;
        bottomRow.add(closeButton, bgbc);

        GridBagConstraints fgbc1 = new GridBagConstraints();
        fgbc1.gridx = 0;
        fgbc1.gridy = 1;
        fgbc1.weightx = 1.0;
        fgbc1.anchor = GridBagConstraints.EAST;
        fgbc1.fill = GridBagConstraints.NONE;
        footer.add(bottomRow, fgbc1);

        return footer;
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
            operationInProgress = true;

            new SwingWorker<ConfigSnapshot, Void>() {
                @Override
                protected ConfigSnapshot doInBackground() throws Exception {
                    return configStore.repairSyntax(currentSnapshot, source);
                }

                @Override
                protected void done() {
                    try {
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
                    } finally {
                        operationInProgress = false;
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
        operationInProgress = true;

        new SwingWorker<ConfigSnapshot, Void>() {
            @Override
            protected ConfigSnapshot doInBackground() throws Exception {
                return configStore.save(currentSnapshot, edits);
            }

            @Override
            protected void done() {
                try {
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
                } finally {
                    operationInProgress = false;
                }
            }
        }.execute();
    }
    private void onReload() {
        hideBanner();
        saveButton.setEnabled(false);
        reloadButton.setEnabled(false);
        operationInProgress = true;

        new SwingWorker<ConfigSnapshot, Void>() {
            @Override
            protected ConfigSnapshot doInBackground() throws Exception {
                return configStore.read();
            }

            @Override
            protected void done() {
                try {
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
                } finally {
                    operationInProgress = false;
                }
            }
        }.execute();
    }

    private void onRestartNow() {
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
        operationInProgress = true;
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
                    operationInProgress = false;
                    showError("Restart stopped: " + ex.getMessage());
                    if (getOwner() instanceof MainFrame mainFrame) {
                        mainFrame.setStopped("Stopped — relaunch manually\nConfig: " + configStore.targetPath() + "\nCwd: " + configStore.workingDirectory() + "\nError: " + ex.getMessage());
                    }
                    dispose();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    operationInProgress = false;
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

    public boolean confirmDiscardChanges() {
        if (operationInProgress) {
            showError("Wait for the current settings operation to finish.");
            return false;
        }
        if (!hasUnsavedChanges()) {
            return true;
        }
        Object[] options = {"Keep editing", "Discard changes"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "You have unsaved changes. Discard them?",
                "Unsaved settings",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]
        );
        return choice == 1;
    }

    private void requestClose() {
        if (confirmDiscardChanges()) {
            dispose();
        }
    }

    private void showError(String message) {
        Color bg = MainFrame.getSemanticColor("P2P.pending.background", new Color(255, 230, 230));
        Color fg = MainFrame.getSemanticColor("P2P.error.foreground", new Color(180, 0, 0));
        statusBanner.setBackground(bg);
        statusBanner.setForeground(fg);
        statusBanner.setText(message);
        statusBanner.setVisible(true);
    }

    private void showSuccess(String message) {
        Color bg = MainFrame.getSemanticColor("P2P.pending.background", new Color(230, 255, 230));
        Color fg = MainFrame.getSemanticColor("P2P.success.foreground", new Color(0, 140, 0));
        statusBanner.setBackground(bg);
        statusBanner.setForeground(fg);
        statusBanner.setText(message);
        statusBanner.setVisible(true);
    }

    private void showInfo(String message) {
        Color bg = MainFrame.getSemanticColor("P2P.pending.background", new Color(230, 240, 255));
        Color fg = MainFrame.getSemanticColor("P2P.accent.foreground", new Color(0, 70, 160));
        statusBanner.setBackground(bg);
        statusBanner.setForeground(fg);
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
