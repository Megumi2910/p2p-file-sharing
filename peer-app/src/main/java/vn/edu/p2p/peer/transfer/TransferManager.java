package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.config.AppConfig;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransferManager implements AutoCloseable {
    private final AppConfig config;
    private final ThreadPoolExecutor executor;
    private final Set<TransferSession> activeSessions = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private volatile TransferListener listener = TransferListener.noOp();
    private volatile IncomingFilePrompt prompt = (metadata, sender, timeout) -> false;
    private volatile boolean closed = false;

    public TransferManager(AppConfig config) {
        this.config = config;
        this.executor = new ThreadPoolExecutor(
                0,
                config.maxConcurrentTransfers(),
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "transfer-worker-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                }
        );
    }

    public void setListener(TransferListener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    public void setIncomingFilePrompt(IncomingFilePrompt prompt) {
        this.prompt = Objects.requireNonNull(prompt);
    }

    public void sendFile(PeerInfo target, Path file) {
        TransferSession session = new TransferSession();
        synchronized (lifecycleLock) {
            if (closed) {
                throw new RejectedExecutionException("TransferManager is closed");
            }
            activeSessions.add(session);
            try {
                executor.submit(() -> {
                    try {
                        new FileSender(target, file, config, listener, session).run();
                    } finally {
                        synchronized (lifecycleLock) {
                            activeSessions.remove(session);
                        }
                    }
                });
            } catch (RejectedExecutionException ex) {
                activeSessions.remove(session);
                throw ex;
            }
        }
    }

    public void handleIncoming(Socket socket) {
        TransferSession session = new TransferSession();
        try {
            session.attach(socket);
        } catch (IOException ex) {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            return;
        }

        synchronized (lifecycleLock) {
            if (closed) {
                session.cancel();
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                return;
            }
            activeSessions.add(session);
            try {
                executor.submit(() -> {
                    try {
                        new FileReceiver(socket, config, prompt, listener, session).run();
                    } finally {
                        synchronized (lifecycleLock) {
                            activeSessions.remove(session);
                        }
                    }
                });
            } catch (RejectedExecutionException ex) {
                activeSessions.remove(session);
                session.cancel();
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }
    public void requestDownload(PeerInfo provider, vn.edu.p2p.common.model.FileRecord targetFile) {
        TransferSession session = new TransferSession();
        synchronized (lifecycleLock) {
            if (closed) {
                throw new RejectedExecutionException("TransferManager is closed");
            }
            activeSessions.add(session);
            try {
                executor.submit(() -> {
                    Socket socket = null;
                    try {
                        socket = new Socket();
                        session.attach(socket);
                        socket.connect(new java.net.InetSocketAddress(provider.host(), provider.port()), 7000);
                        socket.setTcpNoDelay(true);

                        vn.edu.p2p.common.protocol.FrameIO.write(socket.getOutputStream(), new vn.edu.p2p.common.protocol.Frame(
                                vn.edu.p2p.common.protocol.MessageType.FILE_REQUEST,
                                java.util.Map.of("fileId", targetFile.fileId(), "fileName", targetFile.fileName())
                        ));

                        new FileReceiver(socket, config, prompt, listener, session, targetFile.fileId()).run();
                    } catch (Exception ex) {
                        if (socket != null && !socket.isClosed()) {
                            try {
                                socket.close();
                            } catch (IOException ignored) {
                            }
                        }
                        listener.onUpdate(new TransferUpdate(
                                java.util.UUID.randomUUID().toString(), targetFile.fileName(), provider.displayName(),
                                TransferDirection.RECEIVE, TransferStatus.FAILED, 0, targetFile.fileSize(), 0,
                                ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()
                        ));
                    } finally {
                        synchronized (lifecycleLock) {
                            activeSessions.remove(session);
                        }
                    }
                });
            } catch (RejectedExecutionException ex) {
                activeSessions.remove(session);
                throw ex;
            }
        }
    }

    public void shutdown() throws IOException {
        synchronized (lifecycleLock) {
            closed = true;
            for (TransferSession session : activeSessions) {
                session.cancel();
            }
            activeSessions.clear();
        }
        executor.shutdownNow();
    }

    public void awaitTermination(long deadlineNanos) throws IOException, InterruptedException {
        long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
        if (remainingNanos > 0) {
            boolean terminated = executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
            if (!terminated && closed) {
                throw new IOException("TransferManager workers did not terminate within deadline");
            }
        }
    }

    @Override
    public void close() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        shutdown();
        try {
            awaitTermination(deadline);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during TransferManager shutdown", ex);
        }
    }
}
