package vn.edu.p2p.peer.update;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record UpdateJournal(
        int schema,
        String repository,
        String installId,
        Operation operation,
        String transactionId,
        Phase phase,
        long parentPid,
        Instant parentStartInstant,
        Long helperPid,
        Instant helperStartInstant,
        Long childPid,
        Instant childStartInstant,
        String configPath,
        String workingDirectory,
        String javaPath,
        String allowedJvmArgs,
        String currentVersion,
        String targetVersion,
        String currentSha256,
        String candidateSha256,
        String lastError
) {

    public enum Operation {
        RESTART,
        UPDATE
    }

    public enum Phase {
        PREPARED,
        SWITCHING,
        LAUNCHING,
        COMMITTED,
        ROLLED_BACK,
        FAILED;

        public boolean isTerminal() {
            return this == COMMITTED || this == ROLLED_BACK || this == FAILED;
        }
    }

    public static final String OWNER_FILE = "owner.properties";
    public static final String TRANSACTION_FILE = "transaction.properties";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_RECORD_BYTES = 16384;
    public static final int MAX_ERROR_CHARS = 2048;

    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private static final Set<String> KNOWN_OWNER_KEYS = Set.of(
            "schema", "repository", "installRoot", "installId"
    );

    private static final Set<String> KNOWN_JOURNAL_KEYS = Set.of(
            "schema", "repository", "installId", "operation", "transactionId", "phase",
            "parentPid", "parentStartInstant", "helperPid", "helperStartInstant",
            "childPid", "childStartInstant", "configPath", "workingDirectory", "javaPath",
            "allowedJvmArgs", "currentVersion", "targetVersion", "currentSha256",
            "candidateSha256", "lastError"
    );

    public UpdateJournal {
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported journal schema version: " + schema);
        }
        Objects.requireNonNull(repository, "repository cannot be null");
        if (!BuildInfo.DEFAULT_REPOSITORY.equals(repository)) {
            throw new IllegalArgumentException("Invalid repository in journal: " + repository);
        }
        Objects.requireNonNull(installId, "installId cannot be null");
        UUID.fromString(installId); // validate UUID format

        Objects.requireNonNull(operation, "operation cannot be null");
        Objects.requireNonNull(transactionId, "transactionId cannot be null");
        if (!TRANSACTION_ID_PATTERN.matcher(transactionId).matches()) {
            throw new IllegalArgumentException("Invalid transactionId (must be 64 lowercase hex): " + transactionId);
        }
        Objects.requireNonNull(phase, "phase cannot be null");

        if (parentPid <= 0) {
            throw new IllegalArgumentException("parentPid must be positive: " + parentPid);
        }
        Objects.requireNonNull(parentStartInstant, "parentStartInstant cannot be null");

        if ((helperPid == null) != (helperStartInstant == null)) {
            throw new IllegalArgumentException("helperPid and helperStartInstant must both be present or absent");
        }
        if (helperPid != null && helperPid <= 0) {
            throw new IllegalArgumentException("helperPid must be positive: " + helperPid);
        }

        if ((childPid == null) != (childStartInstant == null)) {
            throw new IllegalArgumentException("childPid and childStartInstant must both be present or absent");
        }
        if (childPid != null && childPid <= 0) {
            throw new IllegalArgumentException("childPid must be positive: " + childPid);
        }

        // Phase requirements
        if (phase == Phase.SWITCHING || phase == Phase.LAUNCHING || phase == Phase.COMMITTED) {
            if (helperPid == null || helperStartInstant == null) {
                throw new IllegalArgumentException("helper identity is mandatory for phase " + phase);
            }
        }
        if (phase == Phase.COMMITTED) {
            if (childPid == null || childStartInstant == null) {
                throw new IllegalArgumentException("child identity is mandatory for phase COMMITTED");
            }
        }

        Objects.requireNonNull(configPath, "configPath cannot be null");
        if (!Path.of(configPath).isAbsolute()) {
            throw new IllegalArgumentException("configPath must be absolute: " + configPath);
        }
        Objects.requireNonNull(workingDirectory, "workingDirectory cannot be null");
        if (!Path.of(workingDirectory).isAbsolute()) {
            throw new IllegalArgumentException("workingDirectory must be absolute: " + workingDirectory);
        }
        Objects.requireNonNull(javaPath, "javaPath cannot be null");
        if (!Path.of(javaPath).isAbsolute()) {
            throw new IllegalArgumentException("javaPath must be absolute: " + javaPath);
        }

        Objects.requireNonNull(allowedJvmArgs, "allowedJvmArgs cannot be null");
        if (!allowedJvmArgs.isBlank()) {
            for (String arg : allowedJvmArgs.split(" ")) {
                if (!arg.isBlank() && !RestartCoordinator.isSupportedJvmArg(arg)) {
                    throw new IllegalArgumentException("Unsupported JVM argument in journal: " + arg);
                }
            }
        }

        Objects.requireNonNull(currentVersion, "currentVersion cannot be null");
        ClientVersion curVer = ClientVersion.parse(currentVersion);

        Objects.requireNonNull(targetVersion, "targetVersion cannot be null");
        ClientVersion tgtVer = ClientVersion.parse(targetVersion);

        Objects.requireNonNull(currentSha256, "currentSha256 cannot be null");
        if (!SHA256_PATTERN.matcher(currentSha256).matches()) {
            throw new IllegalArgumentException("Invalid currentSha256 format: " + currentSha256);
        }

        Objects.requireNonNull(candidateSha256, "candidateSha256 cannot be null");

        if (operation == Operation.UPDATE) {
            if (tgtVer.compareTo(curVer) <= 0) {
                throw new IllegalArgumentException("targetVersion must be greater than currentVersion for UPDATE: " + tgtVer + " <= " + curVer);
            }
            if (!SHA256_PATTERN.matcher(candidateSha256).matches()) {
                throw new IllegalArgumentException("Invalid candidateSha256 format for UPDATE: " + candidateSha256);
            }
        } else {
            if (!tgtVer.equals(curVer)) {
                throw new IllegalArgumentException("targetVersion must match currentVersion for RESTART");
            }
            if (!candidateSha256.isEmpty()) {
                throw new IllegalArgumentException("candidateSha256 must be empty for RESTART: " + candidateSha256);
            }
        }

        lastError = lastError == null ? "" : lastError;
        if (lastError.length() > MAX_ERROR_CHARS) {
            throw new IllegalArgumentException("lastError exceeds maximum length of " + MAX_ERROR_CHARS + ": " + lastError.length());
        }
    }

    public static String getOrCreateInstallId(Path updateDir, String expectedRepo, Path installRoot) throws IOException {
        Objects.requireNonNull(updateDir, "updateDir cannot be null");
        Objects.requireNonNull(expectedRepo, "expectedRepo cannot be null");
        Objects.requireNonNull(installRoot, "installRoot cannot be null");

        if (!Files.isDirectory(installRoot)) {
            throw new IOException("Installation root does not exist or is not a directory: " + installRoot);
        }
        if (Files.isSymbolicLink(installRoot)) {
            throw new SecurityException("Installation root cannot be a symbolic link: " + installRoot);
        }

        Path canonicalRoot = installRoot.toRealPath();
        String rootStr = canonicalRoot.toString();
        if (rootStr.startsWith("\\\\") || rootStr.startsWith("//")) {
            throw new SecurityException("UNC and remote network paths are not supported: " + canonicalRoot);
        }

        if (Files.isSymbolicLink(updateDir)) {
            throw new SecurityException("Update directory cannot be a symbolic link: " + updateDir);
        }

        Path ownerPath = updateDir.resolve(OWNER_FILE);

        if (!Files.exists(updateDir)) {
            // Atomically create updateDir with owner-private permissions
            try {
                Files.createDirectory(updateDir);
            } catch (IOException ex) {
                // If racing with concurrent creator, check if it was created
                if (!Files.isDirectory(updateDir)) {
                    throw ex;
                }
            }
            applyOwnerOnlyPermissions(updateDir, true);

            String newInstallId = UUID.randomUUID().toString();
            Properties p = new Properties();
            p.setProperty("schema", "1");
            p.setProperty("repository", expectedRepo);
            p.setProperty("installRoot", canonicalRoot.toString());
            p.setProperty("installId", newInstallId);

            Path tmp = Files.createTempFile(updateDir, "owner.", ".tmp");
            try {
                applyOwnerOnlyPermissions(tmp, false);
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    p.store(out, null);
                }
                try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    fc.force(true);
                }
                long moveDeadline = System.nanoTime() + 2_000_000_000L;
                while (true) {
                    try {
                        Files.move(tmp, ownerPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        break;
                    } catch (AccessDeniedException ex) {
                        if (System.nanoTime() >= moveDeadline) throw ex;
                        try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ex; }
                    }
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            return newInstallId;
        }

        // Directory already exists: bounded wait for owner.properties if concurrent initialization
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!Files.exists(ownerPath) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for owner marker", ex);
            }
        }

        if (!Files.exists(ownerPath)) {
            throw new SecurityException("Update directory exists but lacks owner marker (unowned directory adoption refused): " + updateDir);
        }

        if (Files.isSymbolicLink(ownerPath)) {
            throw new SecurityException("Owner marker cannot be a symbolic link: " + ownerPath);
        }

        Properties p = loadBoundedProperties(ownerPath, KNOWN_OWNER_KEYS);
        String schemaStr = p.getProperty("schema");
        if (!"1".equals(schemaStr)) {
            throw new SecurityException("Owner schema mismatch: " + schemaStr);
        }

        String repo = p.getProperty("repository", "");
        if (!expectedRepo.equals(repo)) {
            throw new SecurityException("Owner repository mismatch: expected " + expectedRepo + " but got " + repo);
        }

        String recordedRoot = p.getProperty("installRoot", "");
        if (!canonicalRoot.toString().equals(recordedRoot)) {
            throw new SecurityException("Owner installRoot mismatch: expected " + canonicalRoot + " but got " + recordedRoot);
        }

        String installId = p.getProperty("installId", "");
        if (installId.isBlank()) {
            throw new SecurityException("Owner marker missing installId");
        }
        UUID.fromString(installId); // validate format

        return installId;
    }

    public static UpdateJournal read(Path updateDir) throws IOException {
        Path txPath = updateDir.resolve(TRANSACTION_FILE);
        if (!Files.exists(txPath)) {
            return null;
        }
        if (Files.isSymbolicLink(txPath)) {
            throw new SecurityException("Journal file cannot be a symbolic link: " + txPath);
        }

        Properties p = loadBoundedProperties(txPath, KNOWN_JOURNAL_KEYS);

        int schema = Integer.parseInt(requireProperty(p, "schema"));
        String repo = requireProperty(p, "repository");
        String installId = requireProperty(p, "installId");
        Operation op = Operation.valueOf(requireProperty(p, "operation"));
        String txId = requireProperty(p, "transactionId");
        Phase phase = Phase.valueOf(requireProperty(p, "phase"));

        long parentPid = Long.parseLong(requireProperty(p, "parentPid"));
        Instant parentStart = parseRequiredInstant(p, "parentStartInstant");

        Long helperPid = parseOptionalLong(p, "helperPid");
        Instant helperStart = parseOptionalInstant(p, "helperStartInstant");

        Long childPid = parseOptionalLong(p, "childPid");
        Instant childStart = parseOptionalInstant(p, "childStartInstant");

        String configPath = requireProperty(p, "configPath");
        String workingDirectory = requireProperty(p, "workingDirectory");
        String javaPath = requireProperty(p, "javaPath");
        String allowedJvmArgs = p.getProperty("allowedJvmArgs", "");

        String curVer = requireProperty(p, "currentVersion");
        String targetVer = requireProperty(p, "targetVersion");
        String curSha = requireProperty(p, "currentSha256");
        String candSha = p.getProperty("candidateSha256", "");
        String err = p.getProperty("lastError", "");

        return new UpdateJournal(
                schema, repo, installId, op, txId, phase,
                parentPid, parentStart,
                helperPid, helperStart,
                childPid, childStart,
                configPath, workingDirectory, javaPath, allowedJvmArgs,
                curVer, targetVer, curSha, candSha, err
        );
    }

    public void writeAtomic(Path updateDir) throws IOException {
        if (!Files.isDirectory(updateDir)) {
            throw new IOException("Update directory does not exist: " + updateDir);
        }
        if (Files.isSymbolicLink(updateDir)) {
            throw new SecurityException("Update directory cannot be a symbolic link: " + updateDir);
        }

        Properties p = new Properties();
        p.setProperty("schema", String.valueOf(schema));
        p.setProperty("repository", repository);
        p.setProperty("installId", installId);
        p.setProperty("operation", operation.name());
        p.setProperty("transactionId", transactionId);
        p.setProperty("phase", phase.name());
        p.setProperty("parentPid", String.valueOf(parentPid));
        p.setProperty("parentStartInstant", parentStartInstant.toString());
        if (helperPid != null) p.setProperty("helperPid", String.valueOf(helperPid));
        if (helperStartInstant != null) p.setProperty("helperStartInstant", helperStartInstant.toString());
        if (childPid != null) p.setProperty("childPid", String.valueOf(childPid));
        if (childStartInstant != null) p.setProperty("childStartInstant", childStartInstant.toString());
        p.setProperty("configPath", configPath);
        p.setProperty("workingDirectory", workingDirectory);
        p.setProperty("javaPath", javaPath);
        p.setProperty("allowedJvmArgs", allowedJvmArgs != null ? allowedJvmArgs : "");
        p.setProperty("currentVersion", currentVersion);
        p.setProperty("targetVersion", targetVersion);
        p.setProperty("currentSha256", currentSha256);
        p.setProperty("candidateSha256", candidateSha256 != null ? candidateSha256 : "");
        p.setProperty("lastError", lastError != null ? lastError : "");

        Path target = updateDir.resolve(TRANSACTION_FILE);
        Path tmp = Files.createTempFile(updateDir, "tx.", ".tmp");
        try {
            applyOwnerOnlyPermissions(tmp, false);
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, null);
            }
            try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                fc.force(true);
            }
            long moveDeadline = System.nanoTime() + 2_000_000_000L;
            while (true) {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    break;
                } catch (AccessDeniedException ex) {
                    if (System.nanoTime() >= moveDeadline) throw ex;
                    try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ex; }
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Properties loadBoundedProperties(Path file, Set<String> knownKeys) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_RECORD_BYTES) {
            throw new IOException("Properties file exceeds maximum allowed size of " + MAX_RECORD_BYTES + " bytes: " + file);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.ISO_8859_1));
        Set<String> seenKeys = new HashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int sepIndex = -1;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '=' || c == ':') {
                    sepIndex = i;
                    break;
                }
            }
            String key = sepIndex != -1 ? trimmed.substring(0, sepIndex).trim() : trimmed;
            if (!seenKeys.add(key)) {
                throw new IOException("Duplicate property key in " + file.getFileName() + ": " + key);
            }
            if (!knownKeys.contains(key)) {
                throw new IOException("Unknown property key in " + file.getFileName() + ": " + key);
            }
        }

        Properties props = new Properties();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            props.load(in);
        }
        return props;
    }

    private static String requireProperty(Properties p, String key) {
        String val = p.getProperty(key);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Missing required journal property: " + key);
        }
        return val.trim();
    }

    private static Instant parseRequiredInstant(Properties p, String key) {
        String val = requireProperty(p, key);
        try {
            return Instant.parse(val);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Malformed instant property " + key + ": " + val, ex);
        }
    }

    private static Long parseOptionalLong(Properties p, String key) {
        String val = p.getProperty(key);
        if (val == null || val.isBlank()) return null;
        try {
            return Long.parseLong(val.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Malformed integer property " + key + ": " + val, ex);
        }
    }

    private static Instant parseOptionalInstant(Properties p, String key) {
        String val = p.getProperty(key);
        if (val == null || val.isBlank()) return null;
        try {
            return Instant.parse(val.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Malformed instant property " + key + ": " + val, ex);
        }
    }

    public static void applyOwnerOnlyPermissions(Path path, boolean isDirectory) {
        try {
            var perms = isDirectory
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
        } catch (IOException ignored) {
        }

        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view != null) {
                UserPrincipal owner = view.getOwner();
                UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();

                List<AclEntry> entries = new ArrayList<>();
                entries.add(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build());

                try {
                    GroupPrincipal admins = lookup.lookupPrincipalByGroupName("Administrators");
                    entries.add(AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(admins)
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                            .build());
                } catch (Exception ignored) {
                }

                try {
                    UserPrincipal system = lookup.lookupPrincipalByName("SYSTEM");
                    entries.add(AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(system)
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                            .build());
                } catch (Exception ignored) {
                }

                view.setAcl(entries);
            }
        } catch (UnsupportedOperationException ignored) {
        } catch (IOException ignored) {
        }
    }
}
