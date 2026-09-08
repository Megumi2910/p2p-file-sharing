package vn.edu.p2p.peer.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record ConfigSnapshot(
        Path logicalPath,
        Path resolvedPath,
        boolean exists,
        String sha256,
        Map<String, String> properties
) {
    public ConfigSnapshot {
        Objects.requireNonNull(logicalPath, "logicalPath cannot be null");
        Objects.requireNonNull(resolvedPath, "resolvedPath cannot be null");
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public String getProperty(String key) {
        return properties.get(key);
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }
}
