package vn.edu.p2p.peer.ui;

import vn.edu.p2p.peer.PeerRuntime;
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

    private final UpdateService updateService;
    private final PeerRuntime runtime;
    private final RestartCoordinator restartCoordinator;

    private final JLabel currentVersionLabel = new JLabel();
    private final JLabel statusLabel = new JLabel("Status: Not checked");
    private final JLabel detailLabel = new JLabel();
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
            RestartCoordinator restartCoordinator
    ) {
        super(owner, "Software Updates", false);
        this.updateService = Objects.requireNonNull(updateService, "updateService cannot be null");
        this.runtime = runtime;
        this.restartCoordinator = restartCoordinator;

        this.notesScrollPane = new JScrollPane(notesArea);
        this.updateListener = snapshot -> SwingUtilities.invokeLater(() -> applySnapshot(snapshot));

        init();
    }

    private void init() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(540, 420));
        setPreferredSize(new Dimension(600, 480));

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

        pack();
        setLocationRelativeTo(getOwner());
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        // Header
        JPanel headerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(3, 4, 3, 4);

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

        detailLabel.putClientProperty("html.disable", Boolean.TRUE);
        detailLabel.setForeground(Color.GRAY);
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

        // Bottom: Action buttons
        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));

        checkButton.addActionListener(e -> updateService.checkForUpdates());
        downloadButton.addActionListener(e -> updateService.downloadAvailableUpdate());
        cancelButton.addActionListener(e -> updateService.cancelDownload());
        installButton.addActionListener(e -> onInstallAndRestart());
        closeButton.addActionListener(e -> dispose());

        buttonBar.add(checkButton);
        buttonBar.add(downloadButton);
        buttonBar.add(cancelButton);
        buttonBar.add(installButton);
        buttonBar.add(closeButton);

        content.add(headerPanel, BorderLayout.NORTH);
        content.add(notesScrollPane, BorderLayout.CENTER);
        content.add(buttonBar, BorderLayout.SOUTH);

        setContentPane(content);
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
                checkButton.setEnabled(true);
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
        }
    }

    private void onInstallAndRestart() {
        if (restartCoordinator == null || runtime == null) {
            return;
        }

        int choice = JOptionPane.showConfirmDialog(
                this,
                "Restart and install update now?\nAll transfers will complete or close safely before restarting.",
                "Confirm Update",
                JOptionPane.YES_NO_OPTION
        );
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        updateService.markRestarting();

        new Thread(() -> {
            try {
                restartCoordinator.installPreparedUpdate(runtime, updateService, msg -> {
                    SwingUtilities.invokeLater(() -> detailLabel.setText(msg));
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
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
