package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class FileSender implements Runnable {
    private static final int MAX_CHUNK_RETRIES = 3;

    private final PeerInfo target;
    private final Path file;
    private final String senderName;
    private final int chunkSize;
    private final TransferListener listener;

    public FileSender(PeerInfo target, Path file, String senderName, int chunkSize, TransferListener listener) {
        this.target = target;
        this.file = file;
        this.senderName = senderName;
        this.chunkSize = chunkSize;
        this.listener = listener;
    }

    @Override
    public void run() {
        String transferId = UUID.randomUUID().toString();
        String fileName = file.getFileName().toString();
        long fileSize = 0;

        try {
            fileSize = Files.size(file);
            update(transferId, fileName, TransferStatus.PREPARING, 0, fileSize, 0, "Calculating SHA-256...");
            String fileHash = HashUtil.sha256(file);
            long totalChunks = (fileSize + chunkSize - 1L) / chunkSize;
            FileMetadata metadata = new FileMetadata(
                    transferId,
                    fileHash,
                    fileName,
                    fileSize,
                    chunkSize,
                    totalChunks,
                    fileHash,
                    senderName
            );

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(target.host(), target.port()), 7_000);
                socket.setTcpNoDelay(true);

                FrameIO.write(socket.getOutputStream(), new Frame(MessageType.FILE_OFFER, metadata.toHeaders()));
                update(transferId, fileName, TransferStatus.WAITING_FOR_ACCEPTANCE, 0, fileSize, 0,
                        "Waiting for " + target.displayName() + "...");

                Frame decision = FrameIO.read(socket.getInputStream());
                if (decision.type() == MessageType.FILE_REJECT) {
                    update(transferId, fileName, TransferStatus.REJECTED, 0, fileSize, 0, "Receiver rejected the file");
                    return;
                }
                if (decision.type() != MessageType.FILE_ACCEPT) {
                    throw new IOException("Expected FILE_ACCEPT, got " + decision.type());
                }

                long startedAt = System.nanoTime();
                long transferred = 0;

                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    for (long chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                        long offset = chunkIndex * (long) chunkSize;
                        int wanted = (int) Math.min(chunkSize, fileSize - offset);
                        byte[] buffer = new byte[wanted];
                        raf.seek(offset);
                        raf.readFully(buffer);

                        boolean acknowledged = false;
                        for (int attempt = 1; attempt <= MAX_CHUNK_RETRIES && !acknowledged; attempt++) {
                            Map<String, String> headers = new LinkedHashMap<>();
                            headers.put("transferId", transferId);
                            headers.put("chunkIndex", Long.toString(chunkIndex));
                            headers.put("offset", Long.toString(offset));
                            headers.put("chunkSha256", HashUtil.sha256(buffer));

                            FrameIO.write(socket.getOutputStream(), new Frame(MessageType.CHUNK_DATA, headers, buffer));
                            Frame ack = FrameIO.read(socket.getInputStream());
                            if (ack.type() != MessageType.CHUNK_ACK) {
                                throw new IOException("Expected CHUNK_ACK, got " + ack.type());
                            }
                            String status = ack.requireHeader("status");
                            acknowledged = "OK".equals(status);
                            if (!acknowledged && attempt == MAX_CHUNK_RETRIES) {
                                throw new IOException("Chunk " + chunkIndex + " failed after " + MAX_CHUNK_RETRIES + " retries");
                            }
                        }

                        transferred += buffer.length;
                        double seconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1_000_000_000.0);
                        update(transferId, fileName, TransferStatus.TRANSFERRING, transferred, fileSize,
                                transferred / seconds,
                                "Chunk " + (chunkIndex + 1) + "/" + totalChunks);
                    }
                }

                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.TRANSFER_COMPLETE,
                        Map.of("transferId", transferId)
                ));
                update(transferId, fileName, TransferStatus.VERIFYING, fileSize, fileSize, 0, "Receiver is verifying file...");

                Frame verify = FrameIO.read(socket.getInputStream());
                if (verify.type() != MessageType.VERIFY_RESULT) {
                    throw new IOException("Expected VERIFY_RESULT, got " + verify.type());
                }
                if (!"OK".equals(verify.requireHeader("status"))) {
                    throw new IOException("Receiver SHA-256 verification failed");
                }

                update(transferId, fileName, TransferStatus.COMPLETED, fileSize, fileSize, 0, "Transfer complete");
            }
        } catch (Exception ex) {
            update(transferId, fileName, TransferStatus.FAILED, 0, fileSize, 0, ex.getMessage());
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
