package vn.edu.p2p.common.model;

import vn.edu.p2p.common.protocol.TransferProtocol;

import java.util.Locale;
import java.util.regex.Pattern;

public record FileRecord(
        String fileId,
        String fileName,
        long fileSize,
        int chunkSizeBytes,
        long totalChunks
) {
    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    public FileRecord {
        if (fileId == null || !HEX_64_PATTERN.matcher(fileId).matches()) {
            throw new IllegalArgumentException("fileId must be a 64-character hex string: " + fileId);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName cannot be blank");
        }
        for (int i = 0; i < fileName.length(); i++) {
            if (Character.isISOControl(fileName.charAt(i))) {
                throw new IllegalArgumentException("fileName contains control character");
            }
        }
        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize cannot be negative: " + fileSize);
        }
        if (chunkSizeBytes < 1 || chunkSizeBytes > TransferProtocol.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("chunkSizeBytes out of bounds: " + chunkSizeBytes);
        }
        long expectedChunks = fileSize == 0 ? 0 : (fileSize / chunkSizeBytes + (fileSize % chunkSizeBytes == 0 ? 0 : 1));
        if (totalChunks != expectedChunks) {
            throw new IllegalArgumentException("Mismatched totalChunks: declared " + totalChunks + ", expected " + expectedChunks);
        }
        fileId = fileId.toLowerCase(Locale.ROOT);
    }
}
