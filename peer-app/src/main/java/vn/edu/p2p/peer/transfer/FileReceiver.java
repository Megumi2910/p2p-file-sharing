package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.util.FileNameUtil;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class FileReceiver implements Runnable {
    private final Socket socket;
    private final AppConfig config;
    private final IncomingFilePrompt prompt;
    private final TransferListener listener;
    private final TransferSession session;

    public FileReceiver(Socket socket, AppConfig config, IncomingFilePrompt prompt,
                        TransferListener listener, TransferSession session) {
        this.socket = socket;
        this.config = config;
        this.prompt = prompt;
        this.listener = listener;
        this.session = session;
    }

    @Override
    public void run() {
        FileMetadata metadata = null;
        Path finalPath = null;
        Path partPath = null;
        Path metaPath = null;
        TransferMeta transferMeta = null;
        long receivedBytes = 0;
        long nextChunkIndex = 0;
        String lastAcceptedSha256 = null;

        try (socket) {
            session.attach(socket);
            if (session.isCancelled()) {
                return;
            }

            socket.setSoTimeout(config.transferReadTimeoutMillis());
            Frame offer = FrameIO.read(socket.getInputStream(), 0);
            if (offer.type() == MessageType.FILE_REQUEST) {
                String requestedFileId = offer.requireHeader("fileId");
                Path source = findSharedFile(requestedFileId);
                if (source == null) {
                    FrameIO.write(socket.getOutputStream(), new Frame(
                            MessageType.FILE_REJECT,
                            Map.of("reason", "Requested file not found in shared folder")
                    ));
                    return;
                }
                new FileSender(socket, "Downloader", source, config, listener, session).run();
                return;
            }
            if (offer.type() != MessageType.FILE_OFFER) {
                throw new IOException("Expected FILE_OFFER or FILE_REQUEST, got " + offer.type());
            }
            metadata = FileMetadata.fromOffer(offer);
            FileNameUtil.safeBaseName(metadata.fileName());

            boolean accepted = config.autoAccept() || prompt.accept(
                    metadata,
                    (InetSocketAddress) socket.getRemoteSocketAddress(),
                    config.transferPromptTimeoutMillis()
            );
            if (!accepted) {
                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.FILE_REJECT,
                        Map.of("transferId", metadata.transferId())
                ));
                update(metadata, TransferStatus.REJECTED, 0, 0, "Rejected");
                return;
            }

            if (session.isCancelled()) {
                update(metadata, TransferStatus.CANCELLED, 0, 0, "Cancelled before staging");
                return;
            }

            // Advisory usable space check
            long usableSpace = config.downloadDir().toFile().getUsableSpace();
            if (metadata.fileSize() > usableSpace) {
                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.FILE_REJECT,
                        Map.of("transferId", metadata.transferId(), "reason", "Insufficient storage space")
                ));
                update(metadata, TransferStatus.REJECTED, 0, 0, "Rejected: Insufficient storage space");
                return;
            }

            // Check for existing resumable staging
            TransferStorage.StagedTransfer existingStaging = TransferStorage.findResumableStaging(config.downloadDir(), metadata);
            boolean isResume = false;
            if (existingStaging != null && existingStaging.meta().contiguousReceivedPrefix() > 0) {
                partPath = existingStaging.partPath();
                metaPath = existingStaging.metaPath();
                transferMeta = existingStaging.meta();
                nextChunkIndex = transferMeta.contiguousReceivedPrefix();
                receivedBytes = (nextChunkIndex == metadata.totalChunks())
                        ? metadata.fileSize()
                        : nextChunkIndex * (long) metadata.chunkSize();
                isResume = true;

                Map<String, String> acceptHeaders = new LinkedHashMap<>();
                acceptHeaders.put("transferId", metadata.transferId());
                acceptHeaders.put("resumed", "true");
                acceptHeaders.put("resumeChunkIndex", Long.toString(nextChunkIndex));
                FrameIO.write(socket.getOutputStream(), new Frame(MessageType.FILE_ACCEPT, acceptHeaders));

                update(metadata, TransferStatus.TRANSFERRING, receivedBytes, 0,
                        "Resuming from chunk " + (nextChunkIndex + 1) + "/" + metadata.totalChunks());
            } else {
                TransferStorage.StagedTransfer newStaging = TransferStorage.createStaging(config.downloadDir(), metadata);
                partPath = newStaging.partPath();
                metaPath = newStaging.metaPath();
                transferMeta = newStaging.meta();
                nextChunkIndex = 0;
                receivedBytes = 0;

                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.FILE_ACCEPT,
                        Map.of("transferId", metadata.transferId(), "resumed", "false")
                ));
            }

            long startedAt = System.nanoTime();

            try (RandomAccessFile out = new RandomAccessFile(partPath.toFile(), "rw")) {
                // Sequential write growth through verified chunk writes; no arbitrary length preallocation

                while (true) {
                    if (session.isCancelled() || Thread.currentThread().isInterrupted()) {
                        update(metadata, TransferStatus.CANCELLED, receivedBytes, 0, "Transfer cancelled");
                        return;
                    }

                    socket.setSoTimeout(config.transferReadTimeoutMillis());
                    Frame frame = FrameIO.read(socket.getInputStream(), metadata.chunkSize());
                    if (frame.type() == MessageType.TRANSFER_COMPLETE) {
                        if (frame.payload().length != 0) {
                            throw new IOException("TRANSFER_COMPLETE frame must have empty payload");
                        }
                        if (!metadata.transferId().equalsIgnoreCase(frame.requireHeader("transferId"))) {
                            throw new IOException("Mismatched transferId in TRANSFER_COMPLETE");
                        }
                        if (nextChunkIndex != metadata.totalChunks()) {
                            throw new IOException("Early completion: received " + nextChunkIndex + " chunks of " + metadata.totalChunks());
                        }
                        if (receivedBytes != metadata.fileSize()) {
                            throw new IOException("Early completion: received " + receivedBytes + " bytes of " + metadata.fileSize());
                        }
                        break;
                    }
                    if (frame.type() != MessageType.CHUNK_DATA) {
                        throw new IOException("Expected CHUNK_DATA, got " + frame.type());
                    }

                    if (!metadata.transferId().equalsIgnoreCase(frame.requireHeader("transferId"))) {
                        throw new IOException("Mismatched transferId in CHUNK_DATA: " + frame.requireHeader("transferId"));
                    }

                    long chunkIndex = Long.parseLong(frame.requireHeader("chunkIndex"));
                    if (chunkIndex < 0 || chunkIndex >= metadata.totalChunks()) {
                        throw new IOException("chunkIndex outside bounds: " + chunkIndex);
                    }

                    long offset = Long.parseLong(frame.requireHeader("offset"));
                    long expectedOffset = chunkIndex * (long) metadata.chunkSize();
                    if (offset != expectedOffset) {
                        throw new IOException("Mismatched chunk offset: declared " + offset + ", expected " + expectedOffset);
                    }

                    int expectedLength = (int) Math.min(metadata.chunkSize(), metadata.fileSize() - expectedOffset);
                    byte[] data = frame.payload();
                    if (data.length != expectedLength) {
                        throw new IOException("Mismatched chunk payload length: got " + data.length + ", expected " + expectedLength);
                    }

                    String expectedChunkHash = frame.requireHeader("chunkSha256").toLowerCase(Locale.ROOT);
                    String actualChunkHash = HashUtil.sha256(data).toLowerCase(Locale.ROOT);

                    // Check for repeat of immediately accepted chunk
                    if (nextChunkIndex > 0 && chunkIndex == nextChunkIndex - 1) {
                        if (actualChunkHash.equals(expectedChunkHash) && (lastAcceptedSha256 == null || actualChunkHash.equals(lastAcceptedSha256))) {
                            // Re-ACK without rewriting or incrementing progress
                            FrameIO.write(socket.getOutputStream(), new Frame(
                                    MessageType.CHUNK_ACK,
                                    Map.of(
                                            "transferId", metadata.transferId(),
                                            "chunkIndex", Long.toString(chunkIndex),
                                            "status", "OK"
                                    )
                            ));
                            continue;
                        } else {
                            throw new IOException("Conflicting payload on repeated chunk " + chunkIndex);
                        }
                    }

                    // Check for next expected chunk
                    if (chunkIndex == nextChunkIndex) {
                        if (!actualChunkHash.equals(expectedChunkHash)) {
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

                        // Valid new chunk: write, update bitmap, and flush metadata
                        out.seek(expectedOffset);
                        out.write(data);
                        transferMeta.markChunkReceived(chunkIndex);
                        transferMeta.save(metaPath);

                        receivedBytes += data.length;
                        nextChunkIndex++;
                        lastAcceptedSha256 = actualChunkHash;

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
                    } else {
                        throw new IOException("Out-of-order chunk: got " + chunkIndex + ", expected " + nextChunkIndex);
                    }
                }
            } // Writer closed before whole-file verification and publication

            if (session.isCancelled()) {
                update(metadata, TransferStatus.CANCELLED, receivedBytes, 0, "Transfer cancelled before publication");
                return;
            }

            update(metadata, TransferStatus.VERIFYING, receivedBytes, 0, "Checking SHA-256...");
            String actualFileHash = HashUtil.sha256(partPath);
            boolean verified = actualFileHash.equalsIgnoreCase(metadata.fileSha256());

            if (verified) {
                final Path currentPart = partPath;
                final FileMetadata currentMeta = metadata;
                finalPath = session.publishIfActive(() ->
                        FileNameUtil.publishVerified(currentPart, config.downloadDir(), currentMeta.fileName())
                );
                if (finalPath == null) {
                    update(metadata, TransferStatus.CANCELLED, receivedBytes, 0, "Transfer cancelled before publication");
                    return;
                }

                // Remove both temporary .part and .part.meta upon successful publication
                TransferStorage.cleanupStaging(partPath, metaPath);

                update(metadata, TransferStatus.COMPLETED, receivedBytes, 0,
                        "Saved to " + finalPath.toAbsolutePath());

                try {
                    FrameIO.write(socket.getOutputStream(), new Frame(
                            MessageType.VERIFY_RESULT,
                            Map.of("transferId", metadata.transferId(), "status", "OK")
                    ));
                } catch (IOException ex) {
                    System.err.println("[PEER] Warning: failed to deliver VERIFY_RESULT OK to sender: " + ex.getMessage());
                }
            } else {
                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.VERIFY_RESULT,
                        Map.of("transferId", metadata.transferId(), "status", "MISMATCH")
                ));
                update(metadata, TransferStatus.FAILED, receivedBytes, 0, "Whole-file SHA-256 mismatch (partial: " + partPath.getFileName() + ")");
            }
        } catch (Exception ex) {
            if (session.isCancelled()) {
                if (metadata != null) {
                    update(metadata, TransferStatus.CANCELLED, receivedBytes, 0, "Transfer cancelled");
                }
            } else {
                String cause = (ex instanceof SocketTimeoutException)
                        ? "Socket read timeout: " + ex.getMessage()
                        : (ex.getMessage() != null && !ex.getMessage().isBlank() ? ex.getMessage() : ex.getClass().getSimpleName());
                if (metadata != null) {
                    String msg = partPath != null ? (cause + " (partial: " + partPath.getFileName() + ")") : cause;
                    update(metadata, TransferStatus.FAILED, receivedBytes, 0, msg);
                } else {
                    System.err.println("[PEER] Incoming connection failed: " + cause);
                }
            }
        }
    }

    private void update(FileMetadata metadata, TransferStatus status, long bytes, double speed, String message) {
        listener.onUpdate(new TransferUpdate(
                metadata.transferId(), metadata.fileName(), metadata.senderName(), TransferDirection.RECEIVE,
                status, bytes, metadata.fileSize(), speed, message
        ));
    }

    private Path findSharedFile(String fileId) {
        Path shared = config.sharedDir();
        if (!Files.isDirectory(shared)) {
            return null;
        }
        try (var stream = Files.newDirectoryStream(shared)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && Files.isReadable(p)) {
                    if (fileId.equalsIgnoreCase(HashUtil.sha256(p))) {
                        return p;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
