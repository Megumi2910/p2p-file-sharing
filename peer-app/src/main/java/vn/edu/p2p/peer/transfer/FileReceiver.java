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
    private final String expectedFileId;

    public FileReceiver(Socket socket, AppConfig config, IncomingFilePrompt prompt,
                        TransferListener listener, TransferSession session) {
        this(socket, config, prompt, listener, session, null);
    }

    public FileReceiver(Socket socket, AppConfig config, IncomingFilePrompt prompt,
                        TransferListener listener, TransferSession session, String expectedFileId) {
        this.socket = socket;
        this.config = config;
        this.prompt = prompt;
        this.listener = listener;
        this.session = session;
        this.expectedFileId = expectedFileId;
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
            if (offer.type() == MessageType.FILE_REJECT) {
                String reason = offer.headers().getOrDefault("reason", "Provider rejected file request");
                String tid = offer.headers().getOrDefault("transferId", java.util.UUID.randomUUID().toString());
                listener.onUpdate(new TransferUpdate(
                        tid, "Requested file", "Provider", TransferDirection.RECEIVE,
                        TransferStatus.REJECTED, 0, 0, 0, reason
                ));
                return;
            }
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
            if (offer.type() == MessageType.CHUNK_REQUEST) {
                serveChunkRequests(socket, offer);
                return;
            }
            if (offer.type() != MessageType.FILE_OFFER) {
                throw new IOException("Expected FILE_OFFER, FILE_REQUEST, CHUNK_REQUEST, or FILE_REJECT, got " + offer.type());
            }
            metadata = FileMetadata.fromOffer(offer);
            FileNameUtil.safeBaseName(metadata.fileName());

            if (expectedFileId != null && !expectedFileId.equalsIgnoreCase(metadata.fileId())) {
                FrameIO.write(socket.getOutputStream(), new Frame(
                        MessageType.FILE_REJECT,
                        Map.of("transferId", metadata.transferId(), "reason", "Offered fileId does not match requested fileId")
                ));
                update(metadata, TransferStatus.FAILED, 0, 0, "Offered fileId mismatch: expected " + expectedFileId + ", got " + metadata.fileId());
                return;
            }
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
                if (!Files.isRegularFile(p) || !Files.isReadable(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (name.startsWith(".p2p-") || name.endsWith(".part") || name.endsWith(".meta")) {
                    continue;
                }
                try {
                    FileNameUtil.safeBaseName(name);
                } catch (Exception ex) {
                    continue;
                }
                if (fileId.equalsIgnoreCase(HashUtil.sha256(p))) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void serveChunkRequests(Socket socket, Frame initialRequest) {
        Frame request = initialRequest;
        try {
            while (true) {
                if (session.isCancelled() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (request.type() != MessageType.CHUNK_REQUEST) {
                    return;
                }
                String fileId = request.requireHeader("fileId");
                long chunkIndex = Long.parseLong(request.requireHeader("chunkIndex"));
                String tid = request.headers().getOrDefault("transferId", "pull-session");

                Path source = findSharedFile(fileId);
                if (source == null) {
                    FrameIO.write(socket.getOutputStream(), new Frame(
                            MessageType.FILE_REJECT,
                            Map.of("reason", "Requested file not found in shared folder", "transferId", tid)
                    ));
                    return;
                }

                long fileSize = Files.size(source);
                int chunkSize = Integer.parseInt(request.headers().getOrDefault("chunkSize", Integer.toString(config.chunkSizeBytes())));
                if (chunkSize < 1 || chunkSize > vn.edu.p2p.common.protocol.TransferProtocol.MAX_CHUNK_BYTES) {
                    FrameIO.write(socket.getOutputStream(), new Frame(
                            MessageType.FILE_REJECT,
                            Map.of("reason", "Invalid chunkSize: " + chunkSize, "transferId", tid)
                    ));
                    return;
                }

                long totalChunks = fileSize == 0 ? 0 : (fileSize / chunkSize + (fileSize % chunkSize == 0 ? 0 : 1));
                if (chunkIndex < 0 || (totalChunks > 0 && chunkIndex >= totalChunks) || (totalChunks == 0 && chunkIndex != 0)) {
                    FrameIO.write(socket.getOutputStream(), new Frame(
                            MessageType.FILE_REJECT,
                            Map.of("reason", "Chunk index out of bounds: " + chunkIndex, "transferId", tid)
                    ));
                    return;
                }

                long offset = chunkIndex * (long) chunkSize;
                int length = fileSize == 0 ? 0 : (int) Math.min((long) chunkSize, fileSize - offset);
                byte[] buffer = new byte[length];
                if (length > 0) {
                    try (RandomAccessFile raf = new RandomAccessFile(source.toFile(), "r")) {
                        raf.seek(offset);
                        raf.readFully(buffer);
                    }
                }

                String chunkSha256 = HashUtil.sha256(buffer);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("transferId", tid);
                headers.put("chunkIndex", Long.toString(chunkIndex));
                headers.put("offset", Long.toString(offset));
                headers.put("chunkSha256", chunkSha256);

                FrameIO.write(socket.getOutputStream(), new Frame(MessageType.CHUNK_DATA, headers, buffer));
                listener.onUpdate(new TransferUpdate(
                        tid, source.getFileName().toString(), "Downloader", TransferDirection.SEND,
                        TransferStatus.TRANSFERRING, offset + length, fileSize, 0,
                        "Served chunk " + (chunkIndex + 1)
                ));

                socket.setSoTimeout(config.transferReadTimeoutMillis());
                request = FrameIO.read(socket.getInputStream(), 0);
            }
        } catch (java.io.EOFException ignored) {
        } catch (Exception ex) {
            System.err.println("[PEER] Pull chunk serving ended: " + ex.getMessage());
        }
    }
}
