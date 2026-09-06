package vn.edu.p2p.common.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class CatalogueCodec {
    public static final int MAX_CATALOGUE_ITEMS = 10_000;

    private CatalogueCodec() {
    }

    public static byte[] encodeFiles(List<FileRecord> files) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(files.size());
            for (FileRecord file : files) {
                out.writeUTF(file.fileId());
                out.writeUTF(file.fileName());
                out.writeLong(file.fileSize());
                out.writeInt(file.chunkSizeBytes());
                out.writeLong(file.totalChunks());
            }
        }
        return buffer.toByteArray();
    }

    public static List<FileRecord> decodeFiles(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = in.readInt();
            if (count < 0 || count > MAX_CATALOGUE_ITEMS) {
                throw new IOException("Invalid file catalogue count: " + count);
            }

            List<FileRecord> files = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                try {
                    String fileId = in.readUTF();
                    String fileName = in.readUTF();
                    long fileSize = in.readLong();
                    int chunkSizeBytes = in.readInt();
                    long totalChunks = in.readLong();
                    files.add(new FileRecord(fileId, fileName, fileSize, chunkSizeBytes, totalChunks));
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Invalid file record at index " + i + ": " + ex.getMessage(), ex);
                }
            }

            if (in.available() > 0) {
                throw new IOException("Trailing bytes in file catalogue payload");
            }
            return files;
        }
    }

    public static byte[] encodeSearchResults(List<SearchResult> results) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(results.size());
            for (SearchResult result : results) {
                FileRecord file = result.file();
                out.writeUTF(file.fileId());
                out.writeUTF(file.fileName());
                out.writeLong(file.fileSize());
                out.writeInt(file.chunkSizeBytes());
                out.writeLong(file.totalChunks());

                List<PeerInfo> providers = result.providers();
                out.writeInt(providers.size());
                for (PeerInfo peer : providers) {
                    out.writeUTF(peer.peerId());
                    out.writeUTF(peer.displayName());
                    out.writeUTF(peer.host());
                    out.writeInt(peer.port());
                }
            }
        }
        return buffer.toByteArray();
    }

    public static List<SearchResult> decodeSearchResults(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = in.readInt();
            if (count < 0 || count > MAX_CATALOGUE_ITEMS) {
                throw new IOException("Invalid search results count: " + count);
            }

            List<SearchResult> results = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                FileRecord file;
                try {
                    String fileId = in.readUTF();
                    String fileName = in.readUTF();
                    long fileSize = in.readLong();
                    int chunkSizeBytes = in.readInt();
                    long totalChunks = in.readLong();
                    file = new FileRecord(fileId, fileName, fileSize, chunkSizeBytes, totalChunks);
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Invalid file record in search result at index " + i + ": " + ex.getMessage(), ex);
                }

                int providerCount = in.readInt();
                if (providerCount < 0 || providerCount > 1000) {
                    throw new IOException("Invalid provider count: " + providerCount);
                }
                List<PeerInfo> providers = new ArrayList<>(providerCount);
                for (int p = 0; p < providerCount; p++) {
                    try {
                        String peerId = in.readUTF();
                        String displayName = in.readUTF();
                        String host = in.readUTF();
                        int port = in.readInt();
                        providers.add(new PeerInfo(peerId, displayName, host, port));
                    } catch (IllegalArgumentException ex) {
                        throw new IOException("Invalid provider record at result " + i + ", provider " + p + ": " + ex.getMessage(), ex);
                    }
                }
                results.add(new SearchResult(file, providers));
            }

            if (in.available() > 0) {
                throw new IOException("Trailing bytes in search results payload");
            }
            return results;
        }
    }
}
