package vn.edu.p2p.peer.transfer;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class TransferSession {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Object lock = new Object();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

    void attach(Socket socket) throws IOException {
        if (socket == null) {
            return;
        }
        synchronized (lock) {
            if (cancelled.get()) {
                if (!socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                throw new IOException("Transfer session was cancelled before socket attach");
            }
            sockets.add(socket);
        }
    }

    void detach(Socket socket) {
        if (socket != null) {
            sockets.remove(socket);
        }
    }

    void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            synchronized (lock) {
                for (Socket s : sockets) {
                    if (s != null && !s.isClosed()) {
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                sockets.clear();
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
