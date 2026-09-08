package vn.edu.p2p.peer.config;

import vn.edu.p2p.peer.util.HashUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

public class ConfigStore {

    private final Path configPath;
    private final Path workingDirectory;
    private final Path targetPath;
    private final Path lockPath;

    FileSystemSeam fileSystemSeam;

    public interface FileSystemSeam {
        void beforeAtomicMove(Path tempFile, Path target) throws IOException;
    }

    public ConfigStore(Path configPath, Path workingDirectory) {
        this.configPath = Objects.requireNonNull(configPath, "configPath cannot be null");
        this.workingDirectory = workingDirectory != null
                ? workingDirectory.toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize();

        if (configPath.isAbsolute()) {
            this.targetPath = configPath.normalize();
        } else {
            this.targetPath = this.workingDirectory.resolve(configPath).normalize();
        }

        Path parent = targetPath.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        this.lockPath = parent.resolve(targetPath.getFileName().toString() + ".lock");
    }

    public Path configPath() {
        return configPath;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public Path targetPath() {
        return targetPath;
    }

    public ConfigSnapshot read() throws IOException {
        boolean exists = Files.exists(targetPath);
        Path resolved = exists ? targetPath.toRealPath() : targetPath;

        if (!exists) {
            return new ConfigSnapshot(configPath, resolved, false, null, Map.of());
        }

        byte[] bytes = Files.readAllBytes(targetPath);
        String sha256 = HashUtil.sha256(bytes);

        Properties props = new Properties();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            props.load(in);
        }

        Map<String, String> map = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            map.put(name, props.getProperty(name));
        }

        return new ConfigSnapshot(configPath, resolved, true, sha256, Collections.unmodifiableMap(map));
    }

    public AppConfig validate(Map<String, String> properties) {
        Objects.requireNonNull(properties, "properties cannot be null");

        if (properties.containsKey("updates.checkOnStartup")) {
            String check = properties.get("updates.checkOnStartup");
            if (check != null && !check.isBlank()) {
                String trimmed = check.trim();
                if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
                    throw new IllegalArgumentException("Invalid boolean for updates.checkOnStartup: " + check);
                }
            }
        }

        Properties p = new Properties();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getValue() != null) {
                p.setProperty(entry.getKey(), entry.getValue());
            }
        }

        return AppConfig.fromProperties(p);
    }

    public ConfigSnapshot save(ConfigSnapshot expected, Map<String, String> edits) throws IOException {
        Objects.requireNonNull(expected, "expected snapshot cannot be null");

        Map<String, String> mergedForValidation = new LinkedHashMap<>(expected.properties());
        if (edits != null) {
            for (Map.Entry<String, String> entry : edits.entrySet()) {
                if (entry.getValue() == null) {
                    mergedForValidation.remove(entry.getKey());
                } else {
                    mergedForValidation.put(entry.getKey(), entry.getValue());
                }
            }
        }

        validate(mergedForValidation);

        Path parent = targetPath.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }

        if (!Files.exists(parent)) {
            throw new IOException("Configuration parent directory does not exist: " + parent);
        }
        if (!Files.isDirectory(parent)) {
            throw new IOException("Configuration parent is not a directory: " + parent);
        }
        if (!Files.isWritable(parent)) {
            throw new IOException("Configuration parent directory is not writable: " + parent);
        }

        try (FileChannel lockChannel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ);
             FileLock ignored = lockChannel.lock()) {

            boolean currentExists = Files.exists(targetPath);
            boolean linkExists = Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS);

            if (expected.exists() != currentExists) {
                if (!expected.exists()) {
                    throw new ConfigConflictException("Configuration file was created externally since snapshot");
                } else {
                    throw new ConfigConflictException("Configuration file was deleted externally since snapshot");
                }
            }

            if (expected.exists()) {
                Path currentResolved = targetPath.toRealPath();
                if (!Objects.equals(expected.resolvedPath(), currentResolved)) {
                    throw new ConfigConflictException("Configuration target resolution changed since snapshot: expected "
                            + expected.resolvedPath() + " but found " + currentResolved);
                }

                byte[] currentBytes = Files.readAllBytes(targetPath);
                String currentSha = HashUtil.sha256(currentBytes);
                if (!Objects.equals(expected.sha256(), currentSha)) {
                    throw new ConfigConflictException("Configuration file modified externally since snapshot");
                }
            }

            Properties toStore = new Properties();
            if (expected.exists()) {
                byte[] currentBytes = Files.readAllBytes(targetPath);
                try (InputStream in = new ByteArrayInputStream(currentBytes)) {
                    toStore.load(in);
                }
            }

            if (edits != null) {
                for (Map.Entry<String, String> entry : edits.entrySet()) {
                    if (entry.getValue() == null) {
                        toStore.remove(entry.getKey());
                    } else {
                        toStore.setProperty(entry.getKey(), entry.getValue());
                    }
                }
            }

            Path tempFile = Files.createTempFile(parent, targetPath.getFileName().toString() + ".", ".tmp");
            try {
                if (currentExists) {
                    try {
                        Set<PosixFilePermission> posix = Files.getPosixFilePermissions(targetPath);
                        Files.setPosixFilePermissions(tempFile, posix);
                    } catch (UnsupportedOperationException | IOException ignoredPerms) {
                    }
                }

                try (OutputStream out = Files.newOutputStream(
                        tempFile,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    toStore.store(out, null);
                }

                try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }

                if (fileSystemSeam != null) {
                    fileSystemSeam.beforeAtomicMove(tempFile, targetPath);
                }

                Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignoredCleanup) {
                }
                throw ex;
            }

            byte[] newBytes = Files.readAllBytes(targetPath);
            String newSha256 = HashUtil.sha256(newBytes);
            Path newResolved = targetPath.toRealPath();

            Map<String, String> updatedProperties = new LinkedHashMap<>();
            for (String key : toStore.stringPropertyNames()) {
                updatedProperties.put(key, toStore.getProperty(key));
            }

            return new ConfigSnapshot(
                    configPath,
                    newResolved,
                    true,
                    newSha256,
                    Collections.unmodifiableMap(updatedProperties)
            );
        }
    }
}
