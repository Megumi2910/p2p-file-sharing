package vn.edu.p2p.peer.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtil {
    private HashUtil() {
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Hashing interrupted");
                }
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
