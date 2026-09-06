package vn.edu.p2p.peer.ui;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.peer.transfer.IncomingFilePrompt;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SwingIncomingFilePrompt implements IncomingFilePrompt {
    private final Component parent;

    public SwingIncomingFilePrompt(Component parent) {
        this.parent = parent;
    }

    @Override
    public boolean accept(FileMetadata metadata, InetSocketAddress sender) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        Runnable dialog = () -> {
            String text = "%s wants to send:%n%n%s%n%s%n%d chunks%n%nFrom: %s"
                    .formatted(
                            metadata.senderName(),
                            metadata.fileName(),
                            humanBytes(metadata.fileSize()),
                            metadata.totalChunks(),
                            sender
                    );
            int result = JOptionPane.showConfirmDialog(
                    parent,
                    text,
                    "Incoming file",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            accepted.set(result == JOptionPane.YES_OPTION);
        };

        if (SwingUtilities.isEventDispatchThread()) {
            dialog.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(dialog);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            } catch (InvocationTargetException ex) {
                return false;
            }
        }
        return accepted.get();
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
