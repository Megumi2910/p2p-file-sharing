package vn.edu.p2p.peer.ui;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.peer.transfer.IncomingFilePrompt;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SwingIncomingFilePrompt implements IncomingFilePrompt {
    private static final String OPTION_ACCEPT = "Accept";
    private static final String OPTION_REJECT = "Reject";

    private final Component parent;

    public SwingIncomingFilePrompt(Component parent) {
        this.parent = parent;
    }

    @Override
    public boolean accept(FileMetadata metadata, InetSocketAddress sender, long timeoutMillis) {
        CompletableFuture<Boolean> decisionFuture = new CompletableFuture<>();
        AtomicBoolean resolved = new AtomicBoolean(false);
        AtomicReference<JDialog> dialogRef = new AtomicReference<>();
        AtomicReference<Timer> timerRef = new AtomicReference<>();

        SwingUtilities.invokeLater(() -> {
            if (resolved.get()) {
                return;
            }

            JPanel panel = buildMessagePanel(metadata, sender);

            Object[] options = new Object[]{OPTION_ACCEPT, OPTION_REJECT};
            JOptionPane pane = new JOptionPane(
                    panel,
                    JOptionPane.QUESTION_MESSAGE,
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    options,
                    OPTION_REJECT
            );

            JDialog dialog = pane.createDialog(parent, "Incoming file");
            dialog.setModal(false);
            dialogRef.set(dialog);

            Timer timer = new Timer((int) Math.min(timeoutMillis, (long) Integer.MAX_VALUE), e -> {
                if (resolved.compareAndSet(false, true)) {
                    dialog.dispose();
                    decisionFuture.complete(false);
                }
            });
            timer.setRepeats(false);
            timerRef.set(timer);
            timer.start();

            pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, evt -> {
                Object value = pane.getValue();
                if (value == null || value == JOptionPane.UNINITIALIZED_VALUE) {
                    return;
                }
                if (resolved.compareAndSet(false, true)) {
                    timer.stop();
                    dialog.dispose();
                    decisionFuture.complete(OPTION_ACCEPT.equals(value));
                }
            });

            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    if (resolved.compareAndSet(false, true)) {
                        timer.stop();
                        dialog.dispose();
                        decisionFuture.complete(false);
                    }
                }
            });

            if (resolved.get()) {
                timer.stop();
                dialog.dispose();
                return;
            }

            dialog.setVisible(true);
        });

        try {
            return decisionFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            cleanupDialogOnEdt(resolved, timerRef, dialogRef);
            return false;
        } catch (InterruptedException ex) {
            cleanupDialogOnEdt(resolved, timerRef, dialogRef);
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            cleanupDialogOnEdt(resolved, timerRef, dialogRef);
            return false;
        }
    }

    private static JPanel buildMessagePanel(FileMetadata metadata, InetSocketAddress sender) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(2, 4, 4, 4);

        // Heading
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        JLabel heading = new JLabel("Incoming file offer");
        heading.putClientProperty("FlatLaf.styleClass", "h3");
        heading.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(heading, gbc);

        // Filename
        gbc.gridy = 1;
        JTextArea fnArea = new JTextArea(metadata.fileName());
        fnArea.setEditable(false);
        fnArea.setLineWrap(true);
        fnArea.setWrapStyleWord(true);
        fnArea.setOpaque(false);
        fnArea.putClientProperty("FlatLaf.styleClass", "h4");
        fnArea.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(fnArea, gbc);

        // Subtitle
        gbc.gridy = 2;
        JLabel sub = new JLabel("Do you want to accept this file transfer?");
        sub.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(sub, gbc);

        gbc.gridwidth = 1;

        // Sender
        gbc.gridy = 3;
        gbc.gridx = 0;
        gbc.weightx = 0;
        JLabel senderLabel = new JLabel("Sender:");
        senderLabel.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(senderLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JLabel senderVal = new JLabel(metadata.senderName());
        senderVal.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(senderVal, gbc);

        // Size
        gbc.gridy = 4;
        gbc.gridx = 0;
        gbc.weightx = 0;
        JLabel sizeLabel = new JLabel("Size:");
        sizeLabel.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(sizeLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JLabel sizeVal = new JLabel(humanBytes(metadata.fileSize()));
        sizeVal.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(sizeVal, gbc);

        // Chunks
        gbc.gridy = 5;
        gbc.gridx = 0;
        gbc.weightx = 0;
        JLabel chunksLabel = new JLabel("Chunks:");
        chunksLabel.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(chunksLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JLabel chunksVal = new JLabel(String.valueOf(metadata.totalChunks()));
        chunksVal.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(chunksVal, gbc);

        // Endpoint
        gbc.gridy = 6;
        gbc.gridx = 0;
        gbc.weightx = 0;
        JLabel epLabel = new JLabel("Endpoint:");
        epLabel.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(epLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JTextArea epVal = new JTextArea(sender != null ? sender.toString() : "-");
        epVal.setEditable(false);
        epVal.setLineWrap(true);
        epVal.setOpaque(false);
        epVal.setFont(UIManager.getFont("Label.font"));
        epVal.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(epVal, gbc);

        return panel;
    }

    private static void cleanupDialogOnEdt(AtomicBoolean resolved, AtomicReference<Timer> timerRef, AtomicReference<JDialog> dialogRef) {
        resolved.set(true);
        SwingUtilities.invokeLater(() -> {
            Timer timer = timerRef.get();
            if (timer != null) {
                timer.stop();
            }
            JDialog dialog = dialogRef.get();
            if (dialog != null) {
                dialog.dispose();
            }
        });
    }

    private static String humanBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return "%.2f %s".formatted(bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
