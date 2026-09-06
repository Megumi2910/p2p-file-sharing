package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.peer.util.FileNameUtil;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class FileReceiver implements Runnable {
    private final Socket socket;
    private final Path downloadDir;
    private final boolean autoAccept;
    private final IncomingFilePrompt prompt;
    private final TransferListener listener;

    public FileReceiver(Socket socket, Path downloadDir, boolean autoAccept,
                        IncomingFilePrompt prompt, TransferListener listener) {
        this.socket = socket;
        this.downloadDir = downloadDir;
        this.autoAccept = autoAccept;
        this.prompt = prompt;
        this.listener = listener;
    }

    @Override
    public void run() {
        FileMetadata metadata = null;
        Path finalPath = null;
        Path partPath = null;

        try (socket) {
            Frame offer = FrameIO.read(socket.getInputStream());
            if (offer.type() != MessageType.FILE_OFFER) {
                throw new IOException("Expected FILE_OFFER, got " + offer.type());
            }
            metadata = FileMetadata.fromOffer(offer);

            boolean accepted = autoAccept || prompt.accept(
                    metadata,
                    (InetSocketAddress) socket.getRemoteSocketAddress()
            );
            if (!accepted) {
                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.FILE_REJECT,
                        Map.of("transferId", metadata.transferId())
                ));
                update(metadata, TransferStatus.REJECTED, 0, 0, "Rejected");
                return;
            }

            finalPath = FileNameUtil.uniqueDestination(downloadDir, metadata.fileName());
            partPath = Path.of(finalPath + ".part");
            Files.createDirectories(downloadDir);

            FrameIO.write(socket.getOutputStream(), new Frame(
                    MessageType.FILE_ACCEPT,
                    Map.of("transferId", metadata.transferId())
            ));

            long receivedBytes = 0;
            long startedAt = System.nanoTime();

            try (RandomAccessFile out = new RandomAccessFile(partPath.toFile(), "rw")) {
                out.setLength(metadata.fileSize());

                while (true) {
                    Frame frame = FrameIO.read(socket.getInputStream());
                    if (frame.type() == MessageType.TRANSFER_COMPLETE) {
                        break;
                    }
                    if (frame.type() != MessageType.CHUNK_DATA) {
                        throw new IOException("Expected CHUNK_DATA, got " + frame.type());
                    }

                    long chunkIndex = Long.parseLong(frame.requireHeader("chunkIndex"));
                    long offset = Long.parseLong(frame.requireHeader("offset"));
                    String expectedChunkHash = frame.requireHeader("chunkSha256");
                    byte[] data = frame.payload();
                    String actualChunkHash = HashUtil.sha256(data);

                    if (!actualChunkHash.equalsIgnoreCase(expectedChunkHash)) {
                        FrameIO.write(socket.getOutputStream(), new Frame(
                                MessageType.CHUNK_ACK,
                                Map.of(
                                        "transferId", metadata.transferId(),
                                        "chunkIndex", Long.toString(chunkIndex),
                                        "status", "RETRY"
                                )
                        ));
                        continue;
                    }

                    if (offset < 0 || offset + data.length > metadata.fileSize()) {
                        throw new IOException("Chunk writes outside declared file size");
                    }

                    out.seek(offset);
                    out.write(data);
                    receivedBytes += data.length;

                    FrameIO.write(socket.getOutputStream(), new Frame(
                            MessageType.CHUNK_ACK,
                            Map.of(
                                    "transferId", metadata.transferId(),
                                    "chunkIndex", Long.toString(chunkIndex),
                                    "status", "OK"
                            )
                    ));

                    double seconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1_000_000_000.0);
                    update(metadata, TransferStatus.TRANSFERRING, receivedBytes,
                            receivedBytes / seconds,
                            "Received chunk " + (chunkIndex + 1) + "/" + metadata.totalChunks());
                }
            }

            update(metadata, TransferStatus.VERIFYING, metadata.fileSize(), 0, "Checking SHA-256...");
            String actualFileHash = HashUtil.sha256(partPath);
            boolean verified = actualFileHash.equalsIgnoreCase(metadata.fileSha256());

            if (verified) {
                try {
                    Files.move(partPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(partPath, finalPath);
                }
                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.VERIFY_RESULT,
                        Map.of("transferId", metadata.transferId(), "status", "OK")
                ));
                update(metadata, TransferStatus.COMPLETED, metadata.fileSize(), 0,
                        "Saved to " + finalPath.toAbsolutePath());
            } else {
                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.VERIFY_RESULT,
                        Map.of("transferId", metadata.transferId(), "status", "MISMATCH")
                ));
                update(metadata, TransferStatus.FAILED, metadata.fileSize(), 0, "Whole-file SHA-256 mismatch");
            }
        } catch (Exception ex) {
            if (metadata != null) {
                update(metadata, TransferStatus.FAILED, 0, 0, ex.getMessage());
            } else {
                System.err.println("[PEER] Incoming connection failed: " + ex.getMessage());
            }
        }
    }

    private void update(FileMetadata metadata, TransferStatus status, long bytes, double speed, String message) {
        listener.onUpdate(new TransferUpdate(
                metadata.transferId(), metadata.fileName(), metadata.senderName(), TransferDirection.RECEIVE,
                status, bytes, metadata.fileSize(), speed, message
        ));
    }
}
