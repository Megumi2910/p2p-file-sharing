package vn.edu.p2p.peer.transfer;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

final class TransferSession {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Object lock = new Object();
    private volatile Socket socket;

    void attach(Socket socket) throws IOException {
        synchronized (lock) {
            if (cancelled.get()) {
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                throw new IOException("Transfer session was cancelled before socket attach");
            }
            this.socket = socket;
        }
    }

    void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            synchronized (lock) {
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    Path publishIfActive(PublicationAction action) throws IOException {
        synchronized (lock) {
            if (cancelled.get()) {
                return null;
            }
            return action.publish();
        }
    }

    @FunctionalInterface
    interface PublicationAction {
        Path publish() throws IOException;
    }
}
