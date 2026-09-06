package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.FileMetadata;
import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.common.model.PeerInfo;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class MultiSourceDownloader implements Runnable {
    private final List<PeerInfo> providers;
    private final FileRecord targetFile;
    private final AppConfig config;
    private final TransferListener listener;
    private final TransferSession session;

    public MultiSourceDownloader(List<PeerInfo> providers, FileRecord targetFile, AppConfig config,
                                 TransferListener listener, TransferSession session) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("Providers list cannot be empty");
        }
        this.providers = List.copyOf(providers);
        this.targetFile = targetFile;
        this.config = config;
        this.listener = listener;
        this.session = session;
    }

    @Override
    public void run() {
        String transferId = UUID.randomUUID().toString();
        String fileName = targetFile.fileName();
        long fileSize = targetFile.fileSize();
        AtomicLong confirmedBytes = new AtomicLong(0);
        String providerNames = providers.stream().map(PeerInfo::displayName).collect(Collectors.joining(", "));

        Path partPath = null;
        Path metaPath = null;
        TransferMeta transferMeta = null;

        try {
            if (session.isCancelled()) {
                update(transferId, fileName, providerNames, TransferStatus.CANCELLED, 0, fileSize, 0, "Cancelled before start");
                return;
            }

            long usableSpace = config.downloadDir().toFile().getUsableSpace();
            if (fileSize > usableSpace) {
                update(transferId, fileName, providerNames, TransferStatus.FAILED, 0, fileSize, 0, "Insufficient storage space");
                return;
            }

            // Map targetFile into FileMetadata for staging query
            FileMetadata metaDescriptor = new FileMetadata(
                    transferId, targetFile.fileId(), fileName, fileSize,
                    targetFile.chunkSizeBytes(), targetFile.totalChunks(),
                    targetFile.fileId(), "MultiSource"
            );

            // Staging discovery or creation
            TransferStorage.StagedTransfer staged = TransferStorage.findResumableStaging(config.downloadDir(), metaDescriptor);
            if (staged == null) {
                staged = TransferStorage.createStaging(config.downloadDir(), metaDescriptor);
            }
            partPath = staged.partPath();
            metaPath = staged.metaPath();
            transferMeta = staged.meta();
            final Path activeMetaPath = metaPath;
            final TransferMeta activeTransferMeta = transferMeta;

            // Calculate initially received bytes from staging
            long initialReceived = 0;
            for (long i = 0; i < targetFile.totalChunks(); i++) {
                if (transferMeta.isChunkReceived(i)) {
                    long offset = i * (long) targetFile.chunkSizeBytes();
                    long len = Math.min(targetFile.chunkSizeBytes(), fileSize - offset);
                    initialReceived += len;
                }
            }
            confirmedBytes.set(initialReceived);

            if (initialReceived > 0) {
                update(transferId, fileName, providerNames, TransferStatus.TRANSFERRING, initialReceived, fileSize, 0,
                        "Resumed: already have " + transferMeta.countReceivedChunks() + "/" + targetFile.totalChunks() + " chunks");
            } else {
                update(transferId, fileName, providerNames, TransferStatus.TRANSFERRING, 0, fileSize, 0,
                        "Starting download from " + providers.size() + " peer(s)...");
            }

            // If empty file
            if (fileSize == 0) {
                verifyAndPublish(transferId, fileName, providerNames, partPath, metaPath);
                return;
            }

            ChunkScheduler scheduler = new ChunkScheduler(targetFile.totalChunks(), transferMeta);
            Object fileWriteLock = new Object();
            long startedAt = System.nanoTime();

            try (RandomAccessFile out = new RandomAccessFile(partPath.toFile(), "rw")) {
                int workerCount = Math.min(providers.size(), config.maxConcurrentTransfers());
                CountDownLatch workersLatch = new CountDownLatch(workerCount);
                List<Thread> workerThreads = new ArrayList<>(workerCount);

                for (int w = 0; w < workerCount; w++) {
                    final PeerInfo peer = providers.get(w % providers.size());
                    Thread worker = new Thread(() -> {
                        Socket socket = null;
                        Long currentAssignedChunk = null;
                        try {
                            if (session.isCancelled()) {
                                return;
                            }
                            socket = new Socket();
                            session.attach(socket);
                            socket.connect(new InetSocketAddress(peer.host(), peer.port()), 7000);
                            socket.setTcpNoDelay(true);

                            while (!scheduler.isDone() && !session.isCancelled()) {
                                Long chunkIndex = scheduler.pollNextChunk(peer.peerId());
                                if (chunkIndex == null) {
                                    if (scheduler.isDone()) {
                                        break;
                                    }
                                    Thread.sleep(100);
                                    continue;
                                }
                                currentAssignedChunk = chunkIndex;

                                socket.setSoTimeout(config.transferReadTimeoutMillis());
                                FrameIO.write(socket.getOutputStream(), new Frame(
                                        MessageType.CHUNK_REQUEST,
                                        Map.of(
                                                "transferId", transferId,
                                                "fileId", targetFile.fileId(),
                                                "chunkIndex", Long.toString(chunkIndex),
                                                "chunkSize", Integer.toString(targetFile.chunkSizeBytes())
                                        )
                                ));

                                Frame reply = FrameIO.read(socket.getInputStream(), targetFile.chunkSizeBytes());
                                if (reply.type() != MessageType.CHUNK_DATA) {
                                    scheduler.markFailure(chunkIndex, peer.peerId());
                                    currentAssignedChunk = null;
                                    break;
                                }

                                long replyIndex = Long.parseLong(reply.requireHeader("chunkIndex"));
                                long replyOffset = Long.parseLong(reply.requireHeader("offset"));
                                long expectedOffset = chunkIndex * (long) targetFile.chunkSizeBytes();
                                int expectedLen = (int) Math.min(targetFile.chunkSizeBytes(), targetFile.fileSize() - expectedOffset);

                                if (replyIndex != chunkIndex || replyOffset != expectedOffset || reply.payload().length != expectedLen) {
                                    scheduler.markFailure(chunkIndex, peer.peerId());
                                    currentAssignedChunk = null;
                                    break;
                                }

                                String replySha = reply.requireHeader("chunkSha256").toLowerCase(Locale.ROOT);
                                byte[] payload = reply.payload();
                                String actualSha = HashUtil.sha256(payload).toLowerCase(Locale.ROOT);

                                if (!actualSha.equals(replySha)) {
                                    scheduler.markFailure(chunkIndex, peer.peerId());
                                    currentAssignedChunk = null;
                                    break;
                                }

                                // Write verified chunk to shared file
                                synchronized (fileWriteLock) {
                                    out.seek(expectedOffset);
                                    out.write(payload);
                                    activeTransferMeta.markChunkReceived(chunkIndex);
                                    activeTransferMeta.save(activeMetaPath);
                                }

                                scheduler.markSuccess(chunkIndex);
                                currentAssignedChunk = null;

                                long totalSoFar = confirmedBytes.addAndGet(payload.length);
                                double seconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1_000_000_000.0);
                                update(transferId, fileName, providerNames, TransferStatus.TRANSFERRING, totalSoFar, fileSize,
                                        totalSoFar / seconds,
                                        "Downloaded " + scheduler.remainingCount() + " remaining chunks");
                            }
                        } catch (Exception ex) {
                            if (currentAssignedChunk != null) {
                                scheduler.markFailure(currentAssignedChunk, peer.peerId());
                            } else {
                                scheduler.markFailure(-1, peer.peerId());
                            }
                        } finally {
                            if (socket != null) {
                                session.detach(socket);
                                if (!socket.isClosed()) {
                                    try {
                                        socket.close();
                                    } catch (IOException ignored) {
                                    }
                                }
                            }
                            workersLatch.countDown();
                        }
                    }, "multi-worker-" + peer.displayName());
                    worker.setDaemon(true);
                    workerThreads.add(worker);
                    worker.start();
                }

                boolean finishedInTime = workersLatch.await(config.transferVerifyTimeoutMillis(), TimeUnit.MILLISECONDS);
                if (!finishedInTime) {
                    session.cancel(); // Abort all worker sockets
                    for (Thread t : workerThreads) {
                        try {
                            t.join(1000);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            } // Writer closed before whole-file verification

            if (session.isCancelled()) {
                update(transferId, fileName, providerNames, TransferStatus.CANCELLED, confirmedBytes.get(), fileSize, 0, "Download cancelled");
                return;
            }

            if (!scheduler.isDone()) {
                throw new IOException("Multi-source download could not complete all chunks (providers exhausted)");
            }

            verifyAndPublish(transferId, fileName, providerNames, partPath, metaPath);

        } catch (Exception ex) {
            if (session.isCancelled()) {
                update(transferId, fileName, providerNames, TransferStatus.CANCELLED, confirmedBytes.get(), fileSize, 0, "Download cancelled");
            } else {
                update(transferId, fileName, providerNames, TransferStatus.FAILED, confirmedBytes.get(), fileSize, 0, ex.getMessage());
            }
        }
    }

    private void verifyAndPublish(String transferId, String fileName, String providerNames, Path partPath, Path metaPath) throws IOException {
        update(transferId, fileName, providerNames, TransferStatus.VERIFYING, targetFile.fileSize(), targetFile.fileSize(), 0, "Checking whole-file SHA-256...");
        String actualSha = HashUtil.sha256(partPath);
        if (actualSha.equalsIgnoreCase(targetFile.fileId())) {
            final Path currentPart = partPath;
            final String currentName = fileName;
            Path finalPath = session.publishIfActive(() ->
                    FileNameUtil.publishVerified(currentPart, config.downloadDir(), currentName)
            );
            if (finalPath == null) {
                update(transferId, fileName, providerNames, TransferStatus.CANCELLED, targetFile.fileSize(), targetFile.fileSize(), 0, "Cancelled before publication");
                return;
            }
            TransferStorage.cleanupStaging(partPath, metaPath);
            update(transferId, fileName, providerNames, TransferStatus.COMPLETED, targetFile.fileSize(), targetFile.fileSize(), 0,
                    "Saved to " + finalPath.toAbsolutePath());
        } else {
            update(transferId, fileName, providerNames, TransferStatus.FAILED, targetFile.fileSize(), targetFile.fileSize(), 0,
                    "Whole-file SHA-256 mismatch (partial: " + partPath.getFileName() + ")");
        }
    }

    private void update(String transferId, String fileName, String peerName, TransferStatus status,
                        long bytes, long total, double speed, String message) {
        listener.onUpdate(new TransferUpdate(
                transferId, fileName, peerName, TransferDirection.RECEIVE,
                status, bytes, total, speed, message
        ));
    }
}
