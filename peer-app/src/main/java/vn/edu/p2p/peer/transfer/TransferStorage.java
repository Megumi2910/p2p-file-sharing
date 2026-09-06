package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.FileMetadata;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TransferStorage {
    private TransferStorage() {
    }

    public record StagedTransfer(Path partPath, Path metaPath, TransferMeta meta) {
    }

    public static StagedTransfer createStaging(Path directory, FileMetadata metadata) throws IOException {
        Files.createDirectories(directory);
        Path partPath = Files.createTempFile(directory, ".p2p-", ".part");
        Path metaPath = Path.of(partPath + ".meta");

        TransferMeta meta = new TransferMeta(
                metadata.fileSha256(),
                metadata.fileSize(),
                metadata.chunkSize(),
                metadata.totalChunks()
        );
        meta.save(metaPath);
        return new StagedTransfer(partPath, metaPath, meta);
    }

    public static StagedTransfer findResumableStaging(Path directory, FileMetadata metadata) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, ".p2p-*.part.meta")) {
            for (Path metaCandidate : stream) {
                TransferMeta meta;
                try {
                    meta = TransferMeta.load(metaCandidate);
                } catch (Exception ex) {
                    // Corrupt or unreadable metadata sidecar, skip candidate
                    continue;
                }

                if (!meta.fileSha256().equalsIgnoreCase(metadata.fileSha256())
                        || meta.fileSize() != metadata.fileSize()
                        || meta.chunkSizeBytes() != metadata.chunkSize()
                        || meta.totalChunks() != metadata.totalChunks()) {
                    continue;
                }

                String metaStr = metaCandidate.toString();
                Path partPath = Path.of(metaStr.substring(0, metaStr.length() - ".meta".length()));

                if (!Files.isRegularFile(partPath) || !Files.isReadable(partPath) || !Files.isWritable(partPath)) {
                    continue;
                }

                long prefix = meta.contiguousReceivedPrefix();
                if (prefix == 0) {
                    // No contiguous chunks, return fresh or this empty staging
                    return new StagedTransfer(partPath, metaCandidate, meta);
                }

                // Verify file has at least the bytes for the contiguous prefix
                long expectedPrefixBytes = (prefix == metadata.totalChunks())
                        ? metadata.fileSize()
                        : prefix * (long) metadata.chunkSize();

                if (Files.size(partPath) >= expectedPrefixBytes) {
                    return new StagedTransfer(partPath, metaCandidate, meta);
                }
            }
        }
        return null;
    }

    public static void cleanupStaging(Path partPath, Path metaPath) {
        if (partPath != null) {
            try {
                Files.deleteIfExists(partPath);
            } catch (IOException ex) {
                System.err.println("[STORAGE] Warning: failed to delete partial: " + partPath + ": " + ex.getMessage());
            }
        }
        if (metaPath != null) {
            try {
                Files.deleteIfExists(metaPath);
            } catch (IOException ex) {
                System.err.println("[STORAGE] Warning: failed to delete metadata: " + metaPath + ": " + ex.getMessage());
            }
        }
    }
}
