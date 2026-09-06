package vn.edu.p2p.peer.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record AppConfig(
        String peerId,
        String displayName,
        int peerPort,
        String trackerHost,
        int trackerPort,
        Path downloadDir,
        int chunkSizeBytes,
        boolean autoAccept
) {
    public static AppConfig load(Path path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        }

        return new AppConfig(
                required(p, "peer.id"),
                required(p, "peer.name"),
                Integer.parseInt(p.getProperty("peer.port", "6001")),
                p.getProperty("tracker.host", "127.0.0.1"),
                Integer.parseInt(p.getProperty("tracker.port", "5000")),
                Path.of(p.getProperty("download.dir", "downloads")),
                Integer.parseInt(p.getProperty("chunk.size.bytes", "1048576")),
                Boolean.parseBoolean(p.getProperty("transfer.autoAccept", "false"))
        );
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return value.trim();
    }
}
