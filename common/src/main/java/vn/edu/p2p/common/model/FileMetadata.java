package vn.edu.p2p.common.model;

import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.TransferProtocol;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public record FileMetadata(
        String transferId,
        String fileId,
        String fileName,
        long fileSize,
        int chunkSize,
        long totalChunks,
        String fileSha256,
        String senderName
) {
    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    public FileMetadata {
        if (transferId == null || transferId.isBlank()) {
            throw new IllegalArgumentException("transferId cannot be blank");
        }
        try {
            UUID parsed = UUID.fromString(transferId);
            if (!parsed.toString().equalsIgnoreCase(transferId)) {
                throw new IllegalArgumentException("transferId is not a canonical UUID: " + transferId);
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid transferId UUID: " + transferId, ex);
        }

        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId cannot be blank");
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
        if (chunkSize < 1 || chunkSize > TransferProtocol.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("chunkSize must be between 1 and " + TransferProtocol.MAX_CHUNK_BYTES + ": " + chunkSize);
        }

        long expectedChunks = fileSize == 0 ? 0 : (fileSize / chunkSize + (fileSize % chunkSize == 0 ? 0 : 1));
        if (totalChunks != expectedChunks) {
            throw new IllegalArgumentException("Mismatched totalChunks: declared " + totalChunks + ", expected " + expectedChunks);
        }

        if (fileSha256 == null || !HEX_64_PATTERN.matcher(fileSha256).matches()) {
            throw new IllegalArgumentException("fileSha256 must be a 64-character hex string: " + fileSha256);
        }
        if (!fileId.equalsIgnoreCase(fileSha256)) {
            throw new IllegalArgumentException("fileId must match fileSha256 case-insensitively");
        }

        // Normalize hashes to lowercase
        fileSha256 = fileSha256.toLowerCase(Locale.ROOT);
        fileId = fileId.toLowerCase(Locale.ROOT);

        if (senderName == null || senderName.isBlank()) {
            throw new IllegalArgumentException("senderName cannot be blank");
        }
    }

    public Map<String, String> toHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("transferId", transferId);
        headers.put("fileId", fileId);
        headers.put("fileName", fileName);
        headers.put("fileSize", Long.toString(fileSize));
        headers.put("chunkSize", Integer.toString(chunkSize));
        headers.put("totalChunks", Long.toString(totalChunks));
        headers.put("fileSha256", fileSha256);
        headers.put("senderName", senderName);
        return headers;
    }

    public static FileMetadata fromOffer(Frame frame) {
        return new FileMetadata(
                frame.requireHeader("transferId"),
                frame.requireHeader("fileId"),
                frame.requireHeader("fileName"),
                Long.parseLong(frame.requireHeader("fileSize")),
                Integer.parseInt(frame.requireHeader("chunkSize")),
                Long.parseLong(frame.requireHeader("totalChunks")),
                frame.requireHeader("fileSha256"),
                frame.requireHeader("senderName")
        );
    }
}
