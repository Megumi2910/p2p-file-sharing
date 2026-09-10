package vn.edu.p2p.peer.ui;
import com.formdev.flatlaf.util.UIScale;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.peer.transfer.IncomingFilePrompt;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Dimension;
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

            JComponent messageComponent = buildMessagePanel(metadata, sender);

            Object[] options = new Object[]{OPTION_ACCEPT, OPTION_REJECT};
            JOptionPane pane = new JOptionPane(
                    messageComponent,
                    JOptionPane.QUESTION_MESSAGE,
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    options,
                    OPTION_REJECT
            );

            JDialog dialog = pane.createDialog(parent, "Incoming file");
            dialog.setModal(false);
            DesktopLayout.fitWindow(dialog, new Dimension(520, 360), new Dimension(420, 260));
            dialog.setLocationRelativeTo(parent);
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

    private static final class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel(GridBagLayout layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return UIScale.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return UIScale.scale(48);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static JComponent buildMessagePanel(FileMetadata metadata, InetSocketAddress sender) {
        JPanel panel = new ScrollablePanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(
                UIScale.scale(6), UIScale.scale(8), UIScale.scale(6), UIScale.scale(8)
        ));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(UIScale.scale(2), UIScale.scale(4), UIScale.scale(4), UIScale.scale(4));

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
        fnArea.setFocusable(false);
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
        JTextArea senderVal = new JTextArea(metadata.senderName());
        senderVal.setEditable(false);
        senderVal.setFocusable(false);
        senderVal.setLineWrap(true);
        senderVal.setWrapStyleWord(true);
        senderVal.setOpaque(false);
        senderVal.putClientProperty("html.disable", Boolean.TRUE);

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
        epVal.setFocusable(false);
        epVal.setLineWrap(true);
        epVal.setWrapStyleWord(true);
        epVal.setOpaque(false);
        epVal.putClientProperty("FlatLaf.styleClass", "muted");
        epVal.putClientProperty("html.disable", Boolean.TRUE);
        panel.add(epVal, gbc);

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(12));
        scroll.setPreferredSize(new Dimension(UIScale.scale(460), UIScale.scale(230)));
        return scroll;
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
