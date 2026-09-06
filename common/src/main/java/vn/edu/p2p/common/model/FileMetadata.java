package vn.edu.p2p.common.model;

import vn.edu.p2p.common.protocol.Frame;

import java.util.LinkedHashMap;
import java.util.Map;

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
