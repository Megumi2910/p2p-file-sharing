package vn.edu.p2p.peer.config;

import vn.edu.p2p.peer.util.HashUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
public class ConfigStore {
    private static final Object JVM_LOCK = new Object();

    private final Path configPath;
    private final Path workingDirectory;
    private final Path targetPath;

    FileSystemSeam fileSystemSeam;

    public interface FileSystemSeam {
        void beforeAtomicMove(Path tempFile, Path target) throws IOException;
    }

    public static class SyntaxException extends IOException {
        private final ConfigSnapshot snapshot;
        private final String sourceText;

        public SyntaxException(ConfigSnapshot snapshot, String sourceText, Throwable cause) {
            super(cause != null ? cause.getMessage() : "Syntax error in configuration", cause);
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot cannot be null");
            this.sourceText = Objects.requireNonNull(sourceText, "sourceText cannot be null");
        }

        public ConfigSnapshot snapshot() {
            return snapshot;
        }

        public String sourceText() {
            return sourceText;
        }
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

    public static boolean checkOnStartup(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (!snapshot.properties().containsKey("updates.checkOnStartup")) {
            return true;
        }
        String raw = snapshot.getProperty("updates.checkOnStartup");
        return parseCheckOnStartup(raw);
    }

    public static boolean parseCheckOnStartup(String raw) {
        if (raw == null) {
            return true;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean for updates.checkOnStartup: " + raw);
    }

    public ConfigSnapshot read() throws IOException {
        boolean exists = Files.exists(targetPath);
        if (!exists) {
            if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(targetPath)) {
                throw new NoSuchFileException("Configuration target does not exist (broken link): " + targetPath);
            }
            Path parent = targetPath.getParent();
            if (parent == null) {
                parent = workingDirectory;
            }
            if (!Files.exists(parent)) {
                throw new NoSuchFileException("Configuration parent directory does not exist: " + parent);
            }
            Path realParent = parent.toRealPath();
            Path resolved = realParent.resolve(targetPath.getFileName());
            return new ConfigSnapshot(configPath, resolved, false, null, Map.of());
        }

        Path resolved = targetPath.toRealPath();
        byte[] bytes = Files.readAllBytes(resolved);
        String sha256 = HashUtil.sha256(bytes);

        Properties props = new Properties();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            props.load(in);
        } catch (IllegalArgumentException ex) {
            String sourceText = new String(bytes, StandardCharsets.ISO_8859_1);
            ConfigSnapshot syntaxSnapshot = new ConfigSnapshot(configPath, resolved, true, sha256, Map.of());
            throw new SyntaxException(syntaxSnapshot, sourceText, ex);
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
            parseCheckOnStartup(check);
        }

        if (properties.containsKey("peer.id")) {
            AppConfig.parsePeerId(properties.get("peer.id"));
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
        if (!Objects.equals(expected.logicalPath(), this.configPath)) {
            throw new IllegalArgumentException("Snapshot logical path does not match store config path: expected "
                    + this.configPath + " but got " + expected.logicalPath());
        }

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

        Properties toStore = new Properties();
        for (Map.Entry<String, String> entry : expected.properties().entrySet()) {
            toStore.setProperty(entry.getKey(), entry.getValue());
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

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        toStore.store(baos, null);
        byte[] bytes = baos.toByteArray();

        return publishInternal(expected, bytes);
    }

    public ConfigSnapshot repairSyntax(ConfigSnapshot expected, String source) throws IOException {
        Objects.requireNonNull(expected, "expected snapshot cannot be null");
        Objects.requireNonNull(source, "source cannot be null");
        if (!Objects.equals(expected.logicalPath(), this.configPath)) {
            throw new IllegalArgumentException("Snapshot logical path does not match store config path: expected "
                    + this.configPath + " but got " + expected.logicalPath());
        }

        Properties props = new Properties();
        try (Reader r = new StringReader(source)) {
            props.load(r);
        } catch (IllegalArgumentException | IOException ex) {
            throw new IOException("Failed to parse configuration properties: " + ex.getMessage(), ex);
        }

        Map<String, String> map = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            map.put(name, props.getProperty(name));
        }
        validate(map);

        String encodedSource = encodeNonIso8859(source);
        byte[] bytes = encodedSource.getBytes(StandardCharsets.ISO_8859_1);
        return publishInternal(expected, bytes);
    }

    private ConfigSnapshot publishInternal(ConfigSnapshot expected, byte[] bytesToPublish) throws IOException {
        synchronized (JVM_LOCK) {
            Path destination;
            boolean initialExists = Files.exists(targetPath);
            if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) && !initialExists) {
                throw new ConfigConflictException("Configuration file is a broken symlink: " + targetPath);
            }

            if (initialExists) {
                destination = targetPath.toRealPath();
            } else {
                Path parent = targetPath.getParent();
                if (parent == null) {
                    parent = workingDirectory;
                }
                if (!Files.exists(parent)) {
                    throw new IOException("Configuration parent directory does not exist: " + parent);
                }
                Path realParent = parent.toRealPath();
                destination = realParent.resolve(targetPath.getFileName());
            }

            Path resolvedParent = destination.getParent();
            if (resolvedParent == null) {
                throw new IOException("Configuration destination has no parent directory: " + destination);
            }
            if (!Files.isDirectory(resolvedParent)) {
                throw new IOException("Configuration parent is not a directory: " + resolvedParent);
            }
            if (!Files.isWritable(resolvedParent)) {
                throw new IOException("Configuration parent directory is not writable: " + resolvedParent);
            }

            Path lockFile = resolvedParent.resolve(destination.getFileName().toString() + ".lock");

            try (FileChannel lockChannel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ);
                 FileLock ignored = lockChannel.lock()) {

                boolean currentExists = Files.exists(targetPath);
                if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) && !currentExists) {
                    throw new ConfigConflictException("Configuration file is a broken symlink");
                }

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

                    byte[] currentBytes = Files.readAllBytes(destination);
                    String currentSha = HashUtil.sha256(currentBytes);
                    if (!Objects.equals(expected.sha256(), currentSha)) {
                        throw new ConfigConflictException("Configuration file modified externally since snapshot");
                    }
                } else {
                    if (Files.exists(destination)) {
                        throw new ConfigConflictException("Configuration file was created externally since snapshot");
                    }
                }

                Path tempFile = Files.createTempFile(resolvedParent, destination.getFileName().toString() + ".", ".tmp");
                try {
                    if (currentExists) {
                        copyFilePermissions(destination, tempFile);
                    }

                    Files.write(tempFile, bytesToPublish, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

                    try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }

                    if (fileSystemSeam != null) {
                        fileSystemSeam.beforeAtomicMove(tempFile, destination);
                    }

                    // Late recheck immediately before atomic publish
                    boolean recheckExists = Files.exists(targetPath);
                    if (expected.exists() != recheckExists) {
                        throw new ConfigConflictException("Configuration file existence changed before publish");
                    }
                    if (expected.exists()) {
                        Path recheckResolved = targetPath.toRealPath();
                        if (!Objects.equals(destination, recheckResolved)) {
                            throw new ConfigConflictException("Configuration target resolution changed before publish: expected "
                                    + destination + " but found " + recheckResolved);
                        }
                        byte[] recheckBytes = Files.readAllBytes(destination);
                        String recheckSha = HashUtil.sha256(recheckBytes);
                        if (!Objects.equals(expected.sha256(), recheckSha)) {
                            throw new ConfigConflictException("Configuration file modified externally before publish");
                        }
                    } else {
                        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) || Files.exists(destination)) {
                            throw new ConfigConflictException("Configuration file was created externally before publish");
                        }
                        Path recheckParent = targetPath.getParent();
                        if (recheckParent == null) recheckParent = workingDirectory;
                        if (!Files.exists(recheckParent) || !Objects.equals(resolvedParent, recheckParent.toRealPath())) {
                            throw new ConfigConflictException("Configuration parent directory changed before publish");
                        }
                    }
                    Files.move(tempFile, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignoredCleanup) {
                    }
                    throw ex;
                }

                byte[] newBytes = Files.readAllBytes(destination);
                String newSha256 = HashUtil.sha256(newBytes);
                Path newResolved = targetPath.toRealPath();

                Properties readBack = new Properties();
                try (InputStream in = new ByteArrayInputStream(newBytes)) {
                    readBack.load(in);
                }
                Map<String, String> updatedProperties = new LinkedHashMap<>();
                for (String key : readBack.stringPropertyNames()) {
                    updatedProperties.put(key, readBack.getProperty(key));
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

    private static void copyFilePermissions(Path source, Path target) throws IOException {
        try {
            PosixFileAttributeView posixView = Files.getFileAttributeView(source, PosixFileAttributeView.class);
            if (posixView != null) {
                PosixFileAttributes attrs = posixView.readAttributes();
                Set<PosixFilePermission> permissions = attrs.permissions();
                Files.setPosixFilePermissions(target, permissions);
            }
        } catch (UnsupportedOperationException ignored) {
        }

        try {
            AclFileAttributeView sourceAclView = Files.getFileAttributeView(source, AclFileAttributeView.class);
            if (sourceAclView != null) {
                List<AclEntry> acl = sourceAclView.getAcl();
                AclFileAttributeView targetAclView = Files.getFileAttributeView(target, AclFileAttributeView.class);
                if (targetAclView != null) {
                    targetAclView.setAcl(acl);
                }
            }
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static String encodeNonIso8859(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c > 255) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
