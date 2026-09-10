package vn.edu.p2p.peer.ui;
import com.formdev.flatlaf.util.UIScale;

import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.update.ClientVersion;
import vn.edu.p2p.peer.update.RestartCoordinator;
import vn.edu.p2p.peer.update.UpdateService;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

public class UpdateDialog extends JDialog {

    public static final String GITHUB_RELEASES_URL = "https://github.com/Megumi2910/p2p-file-sharing/releases";
    private static JTextArea createWrappingDetailArea() {
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


    private final UpdateService updateService;
    private final PeerRuntime runtime;
    private final RestartCoordinator restartCoordinator;
    private final ConfigStore configStore;
    private final JLabel currentVersionLabel = new JLabel();
    private final JLabel statusLabel = new JLabel("Status: Not checked");
    private final JTextArea detailLabel = createWrappingDetailArea();
    private final JProgressBar progressBar = new JProgressBar();
    private final JTextArea notesArea = new JTextArea();
    private final JScrollPane notesScrollPane;

    private final JButton checkButton = new JButton("Check now");
    private final JButton downloadButton = new JButton("Update now");
    private final JButton cancelButton = new JButton("Cancel download");
    private final JButton installButton = new JButton("Restart and install");
    private final JButton closeButton = new JButton("Close");

    private final Consumer<UpdateService.UpdateSnapshot> updateListener;

    public UpdateDialog(
            JFrame owner,
            UpdateService updateService,
            PeerRuntime runtime,
            RestartCoordinator restartCoordinator,
            ConfigStore configStore
    ) {
        super(owner, "Software Updates", false);
        this.updateService = Objects.requireNonNull(updateService, "updateService cannot be null");
        this.runtime = runtime;
        this.restartCoordinator = restartCoordinator;
        this.configStore = Objects.requireNonNull(configStore, "configStore cannot be null");
        this.notesScrollPane = new JScrollPane(notesArea);
        this.updateListener = snapshot -> SwingUtilities.invokeLater(() -> applySnapshot(snapshot));

        init();
    }

    private void init() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUi();

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
                updateService.removeListener(updateListener);
                dispose();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                updateService.removeListener(updateListener);
            }
        });

        updateService.addListener(updateListener);

        DesktopLayout.fitWindow(this, new Dimension(600, 480), new Dimension(540, 420));
        setLocationRelativeTo(getOwner());
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(0, UIScale.scale(8)));
        content.setBorder(BorderFactory.createEmptyBorder(
                UIScale.scale(12), UIScale.scale(16), UIScale.scale(12), UIScale.scale(16)
        ));

        // Header
        JPanel headerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(UIScale.scale(3), UIScale.scale(4), UIScale.scale(3), UIScale.scale(4));

        int row = 0;
        currentVersionLabel.setText("Installed Version: " + updateService.releaseClient().buildInfo().displayVersion());
        currentVersionLabel.putClientProperty("html.disable", Boolean.TRUE);
        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        headerPanel.add(currentVersionLabel, gbc);

        JButton releasesLinkBtn = new JButton("View releases on GitHub");
        releasesLinkBtn.putClientProperty("html.disable", Boolean.TRUE);
        releasesLinkBtn.addActionListener(e -> openReleasesPage());
        gbc.gridy = row++;
        headerPanel.add(releasesLinkBtn, gbc);

        statusLabel.putClientProperty("html.disable", Boolean.TRUE);
        statusLabel.setFont(statusLabel.getFont().deriveFont(java.awt.Font.BOLD));
        gbc.gridy = row++;
        headerPanel.add(statusLabel, gbc);

        gbc.gridy = row++;
        headerPanel.add(detailLabel, gbc);

        progressBar.setVisible(false);
        progressBar.setStringPainted(true);
        gbc.gridy = row++;
        headerPanel.add(progressBar, gbc);

        // Center: Release Notes
        notesArea.setEditable(false);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        notesArea.putClientProperty("html.disable", Boolean.TRUE);
        notesScrollPane.setBorder(BorderFactory.createTitledBorder("Release Notes"));

        content.add(headerPanel, BorderLayout.NORTH);
        content.add(notesScrollPane, BorderLayout.CENTER);
        content.add(buildFooterPanel(), BorderLayout.SOUTH);

        setContentPane(content);
    }

    private JPanel buildFooterPanel() {
        JPanel footer = new JPanel(new GridBagLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), 0, 0, 0));

        // Row 0: Check now + Action button (Download/Cancel/Install)
        JPanel topRow = new JPanel(new GridBagLayout());
        topRow.setOpaque(false);
        GridBagConstraints tgbc = new GridBagConstraints();
        tgbc.insets = new Insets(0, UIScale.scale(4), UIScale.scale(4), UIScale.scale(4));

        checkButton.putClientProperty("html.disable", Boolean.TRUE);
        checkButton.addActionListener(e -> updateService.checkForUpdates());
        tgbc.gridx = 0;
        topRow.add(checkButton, tgbc);

        downloadButton.putClientProperty("FlatLaf.styleClass", "primary");
        downloadButton.putClientProperty("html.disable", Boolean.TRUE);
        downloadButton.addActionListener(e -> updateService.downloadAvailableUpdate());
        tgbc.gridx = 1;
        topRow.add(downloadButton, tgbc);

        cancelButton.putClientProperty("html.disable", Boolean.TRUE);
        cancelButton.addActionListener(e -> updateService.cancelDownload());
        tgbc.gridx = 2;
        topRow.add(cancelButton, tgbc);

        installButton.putClientProperty("FlatLaf.styleClass", "primary");
        installButton.putClientProperty("html.disable", Boolean.TRUE);
        installButton.addActionListener(e -> onInstallAndRestart());
        tgbc.gridx = 3;
        topRow.add(installButton, tgbc);

        GridBagConstraints fgbc0 = new GridBagConstraints();
        fgbc0.gridx = 0;
        fgbc0.gridy = 0;
        fgbc0.weightx = 1.0;
        fgbc0.anchor = GridBagConstraints.EAST;
        fgbc0.fill = GridBagConstraints.NONE;
        footer.add(topRow, fgbc0);

        // Row 1: Close
        JPanel bottomRow = new JPanel(new GridBagLayout());
        bottomRow.setOpaque(false);
        GridBagConstraints bgbc = new GridBagConstraints();
        bgbc.insets = new Insets(0, UIScale.scale(4), 0, UIScale.scale(4));

        closeButton.putClientProperty("html.disable", Boolean.TRUE);
        closeButton.addActionListener(e -> dispose());
        bgbc.gridx = 0;
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

    private void applySnapshot(UpdateService.UpdateSnapshot snapshot) {
        switch (snapshot.state()) {
            case NOT_CHECKED -> {
                statusLabel.setText("Status: Not checked");
                detailLabel.setText("Click 'Check now' to query GitHub Releases for new updates.");
                progressBar.setVisible(false);
                checkButton.setEnabled(true);
                downloadButton.setVisible(false);
                cancelButton.setVisible(false);
                installButton.setVisible(false);
            }
            case CHECKING -> {
                statusLabel.setText("Status: Checking for updates...");
                detailLabel.setText("Querying GitHub Releases API...");
                progressBar.setIndeterminate(true);
                progressBar.setVisible(true);
                checkButton.setEnabled(false);
                downloadButton.setVisible(false);
                cancelButton.setVisible(false);
                installButton.setVisible(false);
            }
            case UP_TO_DATE -> {
                statusLabel.setText("Status: Up to date");
                detailLabel.setText("You are running the latest version (" + snapshot.installedVersion() + ").");
                progressBar.setVisible(false);
                checkButton.setEnabled(true);
                downloadButton.setVisible(false);
                cancelButton.setVisible(false);
                installButton.setVisible(false);
            }
            case NO_RELEASE -> {
                statusLabel.setText("Status: No published release");
                detailLabel.setText("No official releases have been published yet.");
                progressBar.setVisible(false);
                checkButton.setEnabled(true);
                downloadButton.setVisible(false);
                cancelButton.setVisible(false);
                installButton.setVisible(false);
            }
            case UPDATE_AVAILABLE -> {
                statusLabel.setText("Status: Update available (" + snapshot.availableVersion() + ")");
                detailLabel.setText("A signed update is available for installation (" + formatSize(snapshot.totalBytes()) + ").");
                progressBar.setVisible(false);
                checkButton.setEnabled(true);
                downloadButton.setVisible(true);
                downloadButton.setEnabled(true);
                cancelButton.setVisible(false);
                installButton.setVisible(false);
            }
            case DOWNLOADING -> {
                statusLabel.setText("Status: Downloading update...");
                detailLabel.setText(snapshot.detail());
                progressBar.setIndeterminate(false);
                progressBar.setVisible(true);
                if (snapshot.totalBytes() > 0) {
                    int pct = (int) (snapshot.bytesDownloaded() * 100 / snapshot.totalBytes());
                    progressBar.setValue(pct);
                    progressBar.setString(pct + "% (" + formatSize(snapshot.bytesDownloaded()) + " / " + formatSize(snapshot.totalBytes()) + ")");
                }
                checkButton.setEnabled(false);
                downloadButton.setVisible(false);
                cancelButton.setVisible(true);
                cancelButton.setEnabled(true);
                installButton.setVisible(false);
            }
            case VERIFYING -> {
                statusLabel.setText("Status: Verifying update package...");
                detailLabel.setText("Validating SHA-256 and digital signature...");
                progressBar.setIndeterminate(true);
                progressBar.setVisible(true);
                checkButton.setEnabled(false);
                downloadButton.setVisible(false);
                cancelButton.setVisible(false);
                installButton.setVisible(false);
            }
            case READY_TO_RESTART -> {
                statusLabel.setText("Status: Ready to restart and install");
                detailLabel.setText("Update " + snapshot.availableVersion() + " is verified and staged.");
                progressBar.setVisible(false);
                checkButton.setEnabled(false);
                downloadButton.setVisible(false);
                cancelButton.setVisible(false);
                installButton.setVisible(true);
                installButton.setEnabled(true);
            }
            case RESTARTING -> {
                statusLabel.setText("Status: Restarting application...");
                detailLabel.setText("Please wait while the update helper restarts the client.");
                progressBar.setIndeterminate(true);
                progressBar.setVisible(true);
                checkButton.setEnabled(false);
                downloadButton.setVisible(false);
                cancelButton.setVisible(false);
                installButton.setEnabled(false);
            }
            case UNSUPPORTED -> {
                statusLabel.setText("Status: Installation unsupported");
                detailLabel.setText(snapshot.detail());
                progressBar.setVisible(false);
                checkButton.setEnabled(true);
                downloadButton.setVisible(false);
                cancelButton.setVisible(false);
                installButton.setVisible(false);
            }
            case FAILED -> {
                statusLabel.setText("Status: Update check failed");
                detailLabel.setText(snapshot.detail());
                progressBar.setVisible(false);
                checkButton.setEnabled(true);
                downloadButton.setVisible(false);
                cancelButton.setVisible(false);
                installButton.setVisible(false);
            }
        }

        if (snapshot.releaseNotes() != null && !snapshot.releaseNotes().isBlank()) {
            notesArea.setText(snapshot.releaseNotes());
            notesArea.setCaretPosition(0);
        } else {
            notesArea.setText("");
        }
        revalidate();
        repaint();
    }

    private void onInstallAndRestart() {
        if (restartCoordinator == null || runtime == null || configStore == null) {
            return;
        }

        if (getOwner() instanceof MainFrame mainFrame && mainFrame.hasUnsavedSettings()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Save or cancel settings first",
                    "Settings Not Saved",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        int choice = JOptionPane.showConfirmDialog(
                this,
                "Restart and install update now?\nActive transfers or prompt decisions will prevent restart.",
                "Confirm Update",
                JOptionPane.YES_NO_OPTION
        );
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        installButton.setEnabled(false);
        checkButton.setEnabled(false);
        closeButton.setEnabled(false);
        if (getOwner() instanceof MainFrame mainFrame) {
            mainFrame.freezeForRestart(true);
        }

        new Thread(() -> {
            try {
                restartCoordinator.installPreparedUpdate(runtime, configStore, updateService, msg -> {
                    SwingUtilities.invokeLater(() -> detailLabel.setText(msg));
                });
            } catch (RestartCoordinator.RestartFailure ex) {
                SwingUtilities.invokeLater(() -> {
                    detailLabel.setText("Update stopped: " + ex.getMessage());
                    if (getOwner() instanceof MainFrame mainFrame) {
                        mainFrame.setStopped("Stopped — relaunch manually\nConfig: " + configStore.targetPath() + "\nCwd: " + configStore.workingDirectory() + "\nError: " + ex.getMessage());
                    }
                    dispose();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (getOwner() instanceof MainFrame mainFrame) {
                        mainFrame.freezeForRestart(false);
                    }
                    installButton.setEnabled(true);
                    closeButton.setEnabled(true);
                    detailLabel.setText("Update failed: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this, "Could not restart: " + ex.getMessage(), "Update Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "update-install-worker").start();
    }

    private void openReleasesPage() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(GITHUB_RELEASES_URL));
            } else {
                JOptionPane.showMessageDialog(this, "Visit: " + GITHUB_RELEASES_URL, "GitHub Releases", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Visit: " + GITHUB_RELEASES_URL, "GitHub Releases", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
