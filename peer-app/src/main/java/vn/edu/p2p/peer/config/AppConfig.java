package vn.edu.p2p.peer.config;

import vn.edu.p2p.common.protocol.TransferProtocol;

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
        Path sharedDir,
        int chunkSizeBytes,
        boolean autoAccept,
        int trackerReadTimeoutMillis,
        int transferReadTimeoutMillis,
        int transferPromptTimeoutMillis,
        int transferOfferResponseTimeoutMillis,
        int transferVerifyTimeoutMillis,
        int maxConcurrentTransfers
) {
    public AppConfig(
            String peerId,
            String displayName,
            int peerPort,
            String trackerHost,
            int trackerPort,
            Path downloadDir,
            int chunkSizeBytes,
            boolean autoAccept,
            int trackerReadTimeoutMillis,
            int transferReadTimeoutMillis,
            int transferPromptTimeoutMillis,
            int transferOfferResponseTimeoutMillis,
            int transferVerifyTimeoutMillis,
            int maxConcurrentTransfers
    ) {
        this(
                peerId, displayName, peerPort, trackerHost, trackerPort,
                downloadDir,
                downloadDir != null ? downloadDir.resolveSibling("shared") : Path.of("shared"),
                chunkSizeBytes, autoAccept,
                trackerReadTimeoutMillis, transferReadTimeoutMillis, transferPromptTimeoutMillis,
                transferOfferResponseTimeoutMillis, transferVerifyTimeoutMillis, maxConcurrentTransfers
        );
    }

    public AppConfig {
        if (peerId == null || peerId.isBlank()) {
            throw new IllegalArgumentException("peerId cannot be blank");
        }
        if (peerId.length() > 255) {
            throw new IllegalArgumentException("peerId exceeds 255 characters");
        }
        for (int i = 0; i < peerId.length(); i++) {
            if (Character.isISOControl(peerId.charAt(i))) {
                throw new IllegalArgumentException("peerId cannot contain ISO control characters");
            }
        }

        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName cannot be blank");
        }
        if (displayName.length() > 255) {
            throw new IllegalArgumentException("displayName exceeds 255 characters");
        }
        for (int i = 0; i < displayName.length(); i++) {
            if (Character.isISOControl(displayName.charAt(i))) {
                throw new IllegalArgumentException("displayName cannot contain ISO control characters");
            }
        }

        if (peerPort < 1 || peerPort > 65535) {
            throw new IllegalArgumentException("peerPort must be between 1 and 65535: " + peerPort);
        }

        if (trackerHost == null || trackerHost.isBlank()) {
            throw new IllegalArgumentException("trackerHost cannot be blank");
        }

        if (trackerPort < 1 || trackerPort > 65535) {
            throw new IllegalArgumentException("trackerPort must be between 1 and 65535: " + trackerPort);
        }

        if (downloadDir == null) {
            throw new IllegalArgumentException("downloadDir cannot be null");
        }
        if (sharedDir == null) {
            sharedDir = downloadDir.resolveSibling("shared");
        }

        if (chunkSizeBytes < 1 || chunkSizeBytes > TransferProtocol.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("chunkSizeBytes must be between 1 and " + TransferProtocol.MAX_CHUNK_BYTES + ": " + chunkSizeBytes);
        }

        if (trackerReadTimeoutMillis < 1) {
            throw new IllegalArgumentException("trackerReadTimeoutMillis must be positive: " + trackerReadTimeoutMillis);
        }
        if (transferReadTimeoutMillis < 1) {
            throw new IllegalArgumentException("transferReadTimeoutMillis must be positive: " + transferReadTimeoutMillis);
        }
        if (transferPromptTimeoutMillis < 1) {
            throw new IllegalArgumentException("transferPromptTimeoutMillis must be positive: " + transferPromptTimeoutMillis);
        }
        if (transferOfferResponseTimeoutMillis < 1) {
            throw new IllegalArgumentException("transferOfferResponseTimeoutMillis must be positive: " + transferOfferResponseTimeoutMillis);
        }
        if (transferOfferResponseTimeoutMillis <= transferPromptTimeoutMillis) {
            throw new IllegalArgumentException("transfer.offer.response.timeout.ms (" + transferOfferResponseTimeoutMillis
                    + ") must be strictly greater than transfer.prompt.timeout.ms (" + transferPromptTimeoutMillis + ")");
        }
        if (transferVerifyTimeoutMillis < 1) {
            throw new IllegalArgumentException("transferVerifyTimeoutMillis must be positive: " + transferVerifyTimeoutMillis);
        }
        if (maxConcurrentTransfers < 1 || maxConcurrentTransfers > 64) {
            throw new IllegalArgumentException("maxConcurrentTransfers must be between 1 and 64: " + maxConcurrentTransfers);
        }
    }

    public static AppConfig load(Path path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        }
        return fromProperties(p);
    }

    static AppConfig fromProperties(Properties p) {
        String rawPeerId = p.getProperty("peer.id");
        validateNoIsoControls("peer.id", rawPeerId);
        String rawDisplayName = p.getProperty("peer.name");
        validateNoIsoControls("peer.name", rawDisplayName);

        int peerPort = Integer.parseInt(p.getProperty("peer.port", "6001"));
        if (peerPort < 1 || peerPort > 65535) {
            throw new IllegalArgumentException("Property peer.port must be between 1 and 65535: " + peerPort);
        }

        String trackerHost = p.getProperty("tracker.host", "127.0.0.1");
        if (trackerHost.isBlank()) {
            throw new IllegalArgumentException("Property tracker.host cannot be blank");
        }

        int trackerPort = Integer.parseInt(p.getProperty("tracker.port", "5000"));
        if (trackerPort < 1 || trackerPort > 65535) {
            throw new IllegalArgumentException("Property tracker.port must be between 1 and 65535: " + trackerPort);
        }

        Path downloadDir = Path.of(p.getProperty("download.dir", "downloads"));
        Path sharedDir = Path.of(p.getProperty("shared.dir", "shared"));

        int chunkSizeBytes = Integer.parseInt(p.getProperty("chunk.size.bytes", Integer.toString(TransferProtocol.DEFAULT_CHUNK_BYTES)));
        if (chunkSizeBytes < 1 || chunkSizeBytes > TransferProtocol.MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Property chunk.size.bytes must be between 1 and " + TransferProtocol.MAX_CHUNK_BYTES + ": " + chunkSizeBytes);
        }

        String autoAcceptRaw = p.getProperty("transfer.autoAccept", "false");
        boolean autoAccept;
        if ("true".equalsIgnoreCase(autoAcceptRaw)) {
            autoAccept = true;
        } else if ("false".equalsIgnoreCase(autoAcceptRaw)) {
            autoAccept = false;
        } else {
            throw new IllegalArgumentException("Invalid boolean for transfer.autoAccept: " + autoAcceptRaw);
        }

        int trackerReadTimeout = Integer.parseInt(p.getProperty("tracker.read.timeout.ms", "15000"));
        int transferReadTimeout = Integer.parseInt(p.getProperty("transfer.read.timeout.ms", "15000"));
        int transferPromptTimeout = Integer.parseInt(p.getProperty("transfer.prompt.timeout.ms", "120000"));
        int transferOfferResponseTimeout = Integer.parseInt(p.getProperty("transfer.offer.response.timeout.ms", "135000"));
        int transferVerifyTimeout = Integer.parseInt(p.getProperty("transfer.verify.timeout.ms", "300000"));
        int maxConcurrent = Integer.parseInt(p.getProperty("transfer.max.concurrent", "4"));

        return new AppConfig(
                rawPeerId.trim(),
                rawDisplayName.trim(),
                peerPort,
                trackerHost.trim(),
                trackerPort,
                downloadDir,
                sharedDir,
                chunkSizeBytes,
                autoAccept,
                trackerReadTimeout,
                transferReadTimeout,
                transferPromptTimeout,
                transferOfferResponseTimeout,
                transferVerifyTimeout,
                maxConcurrent
        );
    }

    private static void validateNoIsoControls(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + propertyName);
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("Property " + propertyName + " contains ISO control character");
            }
        }
    }
}
