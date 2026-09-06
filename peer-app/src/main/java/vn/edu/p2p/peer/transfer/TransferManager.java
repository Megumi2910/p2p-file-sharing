package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.config.AppConfig;

import java.net.Socket;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TransferManager implements AutoCloseable {
    private final AppConfig config;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile TransferListener listener = TransferListener.noOp();
    private volatile IncomingFilePrompt prompt = (metadata, sender) -> false;

    public TransferManager(AppConfig config) {
        this.config = config;
    }

    public void setListener(TransferListener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    public void setIncomingFilePrompt(IncomingFilePrompt prompt) {
        this.prompt = Objects.requireNonNull(prompt);
    }

    public void sendFile(PeerInfo target, Path file) {
        executor.submit(new FileSender(
                target,
                file,
                config.displayName(),
                config.chunkSizeBytes(),
                listener
        ));
    }

    public void handleIncoming(Socket socket) {
        executor.submit(new FileReceiver(
                socket,
                config.downloadDir(),
                config.autoAccept(),
                prompt,
                listener
        ));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
