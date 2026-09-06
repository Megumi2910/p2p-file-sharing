package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class FileSender implements Runnable {
    private static final int MAX_CHUNK_RETRIES = 3;

    private final PeerInfo target;
    private final Path file;
    private final AppConfig config;
    private final TransferListener listener;
    private final TransferSession session;

    public FileSender(PeerInfo target, Path file, AppConfig config, TransferListener listener, TransferSession session) {
        this.target = target;
        this.file = file;
        this.config = config;
        this.listener = listener;
        this.session = session;
    }

    @Override
    public void run() {
        String transferId = UUID.randomUUID().toString();
        String fileName = file.getFileName().toString();
        long fileSize = 0;
        long transferred = 0;

        try {
            if (session.isCancelled()) {
                update(transferId, fileName, TransferStatus.CANCELLED, 0, 0, 0, "Cancelled before start");
                return;
            }

            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new IOException("Source is not a readable regular file: " + file);
            }
            fileSize = Files.size(file);
            update(transferId, fileName, TransferStatus.PREPARING, 0, fileSize, 0, "Calculating SHA-256...");
            String fileHash = HashUtil.sha256(file);
            long totalChunks = fileSize == 0 ? 0 : (fileSize / config.chunkSizeBytes() + (fileSize % config.chunkSizeBytes() == 0 ? 0 : 1));
            FileMetadata metadata = new FileMetadata(
                    transferId,
                    fileHash,
                    fileName,
                    fileSize,
                    config.chunkSizeBytes(),
                    totalChunks,
                    fileHash,
                    config.displayName()
            );

            try (Socket socket = new Socket()) {
                session.attach(socket);
                socket.connect(new InetSocketAddress(target.host(), target.port()), 7_000);
                socket.setTcpNoDelay(true);

                FrameIO.write(socket.getOutputStream(), new Frame(MessageType.FILE_OFFER, metadata.toHeaders()));
                update(transferId, fileName, TransferStatus.WAITING_FOR_ACCEPTANCE, 0, fileSize, 0,
                        "Waiting for " + target.displayName() + "...");

                // Wait for decision using offer response timeout
                socket.setSoTimeout(config.transferOfferResponseTimeoutMillis());
                Frame decision = FrameIO.read(socket.getInputStream(), 0);
                if (!transferId.equalsIgnoreCase(decision.requireHeader("transferId"))) {
                    throw new IOException("Mismatched transferId in decision: " + decision.requireHeader("transferId"));
                }
                if (decision.type() == MessageType.FILE_REJECT) {
                    update(transferId, fileName, TransferStatus.REJECTED, 0, fileSize, 0, "Receiver rejected the file");
                    return;
                }
                if (decision.type() != MessageType.FILE_ACCEPT) {
                    throw new IOException("Expected FILE_ACCEPT, got " + decision.type());
                }

                // Restore active read timeout for chunk transfers
                socket.setSoTimeout(config.transferReadTimeoutMillis());
                long startedAt = System.nanoTime();

                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    for (long chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                        if (session.isCancelled() || Thread.currentThread().isInterrupted()) {
                            update(transferId, fileName, TransferStatus.CANCELLED, transferred, fileSize, 0, "Transfer cancelled");
                            return;
                        }

                        long offset = chunkIndex * (long) config.chunkSizeBytes();
                        int wanted = (int) Math.min(config.chunkSizeBytes(), fileSize - offset);
                        byte[] buffer = new byte[wanted];
                        raf.seek(offset);
                        raf.readFully(buffer);

                        String chunkSha256 = HashUtil.sha256(buffer);

                        boolean acknowledged = false;
                        for (int attempt = 1; attempt <= MAX_CHUNK_RETRIES && !acknowledged; attempt++) {
                            if (session.isCancelled() || Thread.currentThread().isInterrupted()) {
                                update(transferId, fileName, TransferStatus.CANCELLED, transferred, fileSize, 0, "Transfer cancelled");
                                return;
                            }

                            Map<String, String> headers = new LinkedHashMap<>();
                            headers.put("transferId", transferId);
                            headers.put("chunkIndex", Long.toString(chunkIndex));
                            headers.put("offset", Long.toString(offset));
                            headers.put("chunkSha256", chunkSha256);

                            FrameIO.write(socket.getOutputStream(), new Frame(MessageType.CHUNK_DATA, headers, buffer));
                            Frame ack = FrameIO.read(socket.getInputStream(), 0);
                            if (ack.type() != MessageType.CHUNK_ACK) {
                                throw new IOException("Expected CHUNK_ACK, got " + ack.type());
                            }
                            if (!transferId.equalsIgnoreCase(ack.requireHeader("transferId"))) {
                                throw new IOException("Mismatched transferId in CHUNK_ACK: " + ack.requireHeader("transferId"));
                            }
                            long ackChunkIndex = Long.parseLong(ack.requireHeader("chunkIndex"));
                            if (ackChunkIndex != chunkIndex) {
                                throw new IOException("Mismatched chunkIndex in CHUNK_ACK: got " + ackChunkIndex + ", expected " + chunkIndex);
                            }

                            String status = ack.requireHeader("status");
                            if ("OK".equals(status)) {
                                acknowledged = true;
                            } else if ("RETRY".equals(status)) {
                                if (attempt == MAX_CHUNK_RETRIES) {
                                    throw new IOException("Chunk " + chunkIndex + " failed after " + MAX_CHUNK_RETRIES + " retries");
                                }
                            } else {
                                throw new IOException("Unknown CHUNK_ACK status: " + status);
                            }
                        }

                        transferred += buffer.length;
                        double seconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1_000_000_000.0);
                        update(transferId, fileName, TransferStatus.TRANSFERRING, transferred, fileSize,
                                transferred / seconds,
                                "Chunk " + (chunkIndex + 1) + "/" + totalChunks);
                    }
                }

                // Verify file did not change during transmission
                String finalSourceHash = HashUtil.sha256(file);
                if (!finalSourceHash.equalsIgnoreCase(fileHash)) {
                    throw new IOException("Source file modified during transmission");
                }

                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.TRANSFER_COMPLETE,
                        Map.of("transferId", transferId)
                ));
                update(transferId, fileName, TransferStatus.VERIFYING, transferred, fileSize, 0, "Receiver is verifying file...");

                // Wait for whole-file verification with verify timeout
                socket.setSoTimeout(config.transferVerifyTimeoutMillis());
                Frame verify = FrameIO.read(socket.getInputStream(), 0);
                if (verify.type() != MessageType.VERIFY_RESULT) {
                    throw new IOException("Expected VERIFY_RESULT, got " + verify.type());
                }
                if (!transferId.equalsIgnoreCase(verify.requireHeader("transferId"))) {
                    throw new IOException("Mismatched transferId in VERIFY_RESULT: " + verify.requireHeader("transferId"));
                }
                String verifyStatus = verify.requireHeader("status");
                if ("OK".equals(verifyStatus)) {
                    update(transferId, fileName, TransferStatus.COMPLETED, transferred, fileSize, 0, "Transfer complete");
                } else if ("MISMATCH".equals(verifyStatus)) {
                    throw new IOException("Receiver reported SHA-256 mismatch");
                } else {
                    throw new IOException("Unknown VERIFY_RESULT status: " + verifyStatus);
                }
            }
        } catch (Exception ex) {
            if (session.isCancelled()) {
                update(transferId, fileName, TransferStatus.CANCELLED, transferred, fileSize, 0, "Transfer cancelled");
            } else {
                String cause = (ex instanceof SocketTimeoutException)
                        ? "Socket read timeout: " + ex.getMessage()
                        : (ex.getMessage() != null && !ex.getMessage().isBlank() ? ex.getMessage() : ex.getClass().getSimpleName());
                update(transferId, fileName, TransferStatus.FAILED, transferred, fileSize, 0, cause);
            }
        }
    }

    private void update(String transferId, String fileName, TransferStatus status,
                        long bytes, long total, double speed, String message) {
        listener.onUpdate(new TransferUpdate(
                transferId, fileName, target.displayName(), TransferDirection.SEND,
                status, bytes, total, speed, message
        ));
    }
}
