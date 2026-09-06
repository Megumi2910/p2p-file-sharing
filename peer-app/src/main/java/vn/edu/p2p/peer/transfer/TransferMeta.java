package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.protocol.TransferProtocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class TransferMeta {
    public static final int MAGIC = 0x50325052; // "P2PR"
    public static final short VERSION = 1;
    private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final String fileSha256;
    private final long fileSize;
    private final int chunkSizeBytes;
    private final long totalChunks;
    private final BitSet receivedChunks;

    public TransferMeta(String fileSha256, long fileSize, int chunkSizeBytes, long totalChunks, BitSet receivedChunks) {
        if (fileSha256 == null || !HEX_64_PATTERN.matcher(fileSha256).matches()) {
            throw new IllegalArgumentException("fileSha256 must be a 64-character hex string: " + fileSha256);
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

        this.fileSha256 = fileSha256.toLowerCase(Locale.ROOT);
        this.fileSize = fileSize;
        this.chunkSizeBytes = chunkSizeBytes;
        this.totalChunks = totalChunks;
        this.receivedChunks = receivedChunks == null ? new BitSet((int) Math.min(totalChunks, Integer.MAX_VALUE)) : (BitSet) receivedChunks.clone();
    }

    public TransferMeta(String fileSha256, long fileSize, int chunkSizeBytes, long totalChunks) {
        this(fileSha256, fileSize, chunkSizeBytes, totalChunks, new BitSet((int) Math.min(totalChunks, Integer.MAX_VALUE)));
    }

    public String fileSha256() {
        return fileSha256;
    }

    public long fileSize() {
        return fileSize;
    }

    public int chunkSizeBytes() {
        return chunkSizeBytes;
    }

    public long totalChunks() {
        return totalChunks;
    }

    public synchronized void markChunkReceived(long chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IndexOutOfBoundsException("chunkIndex out of bounds: " + chunkIndex);
        }
        receivedChunks.set((int) chunkIndex);
    }

    public synchronized boolean isChunkReceived(long chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            return false;
        }
        return receivedChunks.get((int) chunkIndex);
    }
    public synchronized long[] getBitmapMask() {
        return receivedChunks.toLongArray();
    }

    public synchronized long contiguousReceivedPrefix() {
        if (totalChunks == 0) {
            return 0;
        }
        int nextClear = receivedChunks.nextClearBit(0);
        return Math.min(nextClear, totalChunks);
    }

    public synchronized long countReceivedChunks() {
        return receivedChunks.cardinality();
    }

    public synchronized boolean allChunksReceived() {
        if (totalChunks == 0) {
            return true;
        }
        return contiguousReceivedPrefix() == totalChunks;
    }

    public synchronized void save(Path metaPath) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeShort(VERSION);
            out.write(fileSha256.getBytes(StandardCharsets.US_ASCII));
            out.writeLong(fileSize);
            out.writeInt(chunkSizeBytes);
            out.writeLong(totalChunks);

            byte[] bitmapBytes = receivedChunks.toByteArray();
            out.writeInt(bitmapBytes.length);
            out.write(bitmapBytes);
            out.flush();
        }

        byte[] payload = buffer.toByteArray();
        byte[] checksum = sha256Bytes(payload);

        Path tmpPath = metaPath.resolveSibling(metaPath.getFileName().toString() + ".tmp");
        try (var out = Files.newOutputStream(tmpPath)) {
            out.write(payload);
            out.write(checksum);
            out.flush();
        }

        try {
            Files.move(tmpPath, metaPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmpPath, metaPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static TransferMeta load(Path metaPath) throws IOException {
        byte[] fileBytes = Files.readAllBytes(metaPath);
        if (fileBytes.length < 4 + 2 + 64 + 8 + 4 + 8 + 4 + 32) {
            throw new IOException("Metadata file too short: " + fileBytes.length + " bytes");
        }

        int payloadLen = fileBytes.length - 32;
        byte[] payload = Arrays.copyOfRange(fileBytes, 0, payloadLen);
        byte[] expectedChecksum = Arrays.copyOfRange(fileBytes, payloadLen, fileBytes.length);

        byte[] actualChecksum = sha256Bytes(payload);
        if (!MessageDigest.isEqual(actualChecksum, expectedChecksum)) {
            throw new IOException("Corrupted metadata checksum mismatch in " + metaPath);
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid metadata magic: 0x" + Integer.toHexString(magic));
            }
            short version = in.readShort();
            if (version != VERSION) {
                throw new IOException("Unsupported metadata version: " + version);
            }

            byte[] shaBytes = new byte[64];
            in.readFully(shaBytes);
            String sha = new String(shaBytes, StandardCharsets.US_ASCII);

            long fileSize = in.readLong();
            int chunkSize = in.readInt();
            long totalChunks = in.readLong();

            int bitmapLen = in.readInt();
            if (bitmapLen < 0 || bitmapLen > 10 * 1024 * 1024) {
                throw new IOException("Invalid bitmap length: " + bitmapLen);
            }
            byte[] bitmapBytes = new byte[bitmapLen];
            in.readFully(bitmapBytes);

            BitSet bits = BitSet.valueOf(bitmapBytes);
            return new TransferMeta(sha, fileSize, chunkSize, totalChunks, bits);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid metadata invariants in " + metaPath + ": " + ex.getMessage(), ex);
        }
    }

    private static byte[] sha256Bytes(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
