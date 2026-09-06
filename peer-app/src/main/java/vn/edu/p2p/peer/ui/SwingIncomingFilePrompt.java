package vn.edu.p2p.peer.ui;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.peer.transfer.IncomingFilePrompt;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SwingIncomingFilePrompt implements IncomingFilePrompt {
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
            String text = "%s wants to send:%n%n%s%n%s%n%d chunks%n%nFrom: %s"
                    .formatted(
                            metadata.senderName(),
                            metadata.fileName(),
                            humanBytes(metadata.fileSize()),
                            metadata.totalChunks(),
                            sender
                    );
            JOptionPane pane = new JOptionPane(
                    text,
                    JOptionPane.QUESTION_MESSAGE,
                    JOptionPane.YES_NO_OPTION
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
                if (value instanceof Integer intVal) {
                    if (resolved.compareAndSet(false, true)) {
                        timer.stop();
                        dialog.dispose();
                        decisionFuture.complete(intVal == JOptionPane.YES_OPTION);
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

    private static void cleanupDialogOnEdt(AtomicBoolean resolved, AtomicReference<Timer> timerRef, AtomicReference<JDialog> dialogRef) {
        resolved.set(true);
        SwingUtilities.invokeLater(() -> {
            Timer t = timerRef.get();
            if (t != null) {
                t.stop();
            }
            JDialog d = dialogRef.get();
            if (d != null) {
                d.dispose();
            }
        });
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return "%.1f KiB".formatted(kib);
        double mib = kib / 1024.0;
        if (mib < 1024) return "%.1f MiB".formatted(mib);
        return "%.2f GiB".formatted(mib / 1024.0);
    }
}
