package vn.edu.p2p.peer.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

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

    public static String getOrCreateInstallId(Path updateDir, String expectedRepo, Path installRoot) throws IOException {
        Files.createDirectories(updateDir);
        Path ownerPath = updateDir.resolve(OWNER_FILE);
        if (Files.exists(ownerPath)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(ownerPath)) {
                p.load(in);
            }
            String repo = p.getProperty("repository", "");
            if (!expectedRepo.equals(repo)) {
                throw new SecurityException("Owner properties repository mismatch: " + repo);
            }
            return p.getProperty("installId", UUID.randomUUID().toString());
        }

        String installId = UUID.randomUUID().toString();
        Properties p = new Properties();
        p.setProperty("schema", "1");
        p.setProperty("repository", expectedRepo);
        p.setProperty("installRoot", installRoot.toAbsolutePath().normalize().toString());
        p.setProperty("installId", installId);

        Path tmp = Files.createTempFile(updateDir, "owner.", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, null);
            }
            try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                fc.force(true);
            }
            Files.move(tmp, ownerPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return installId;
    }

    public static UpdateJournal read(Path updateDir) throws IOException {
        Path txPath = updateDir.resolve(TRANSACTION_FILE);
        if (!Files.exists(txPath)) {
            return null;
        }

        byte[] bytes = Files.readAllBytes(txPath);
        Properties p = new Properties();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            p.load(in);
        }

        int schema = Integer.parseInt(p.getProperty("schema", "1"));
        String repo = p.getProperty("repository", "");
        String installId = p.getProperty("installId", "");
        Operation op = Operation.valueOf(p.getProperty("operation", "RESTART"));
        String txId = p.getProperty("transactionId", "");
        Phase phase = Phase.valueOf(p.getProperty("phase", "FAILED"));

        long parentPid = Long.parseLong(p.getProperty("parentPid", "0"));
        Instant parentStart = parseInstant(p.getProperty("parentStartInstant"));

        Long helperPid = parseLong(p.getProperty("helperPid"));
        Instant helperStart = parseInstant(p.getProperty("helperStartInstant"));

        Long childPid = parseLong(p.getProperty("childPid"));
        Instant childStart = parseInstant(p.getProperty("childStartInstant"));

        String configPath = p.getProperty("configPath", "");
        String workingDirectory = p.getProperty("workingDirectory", "");
        String javaPath = p.getProperty("javaPath", "");
        String allowedJvmArgs = p.getProperty("allowedJvmArgs", "");

        String curVer = p.getProperty("currentVersion", "");
        String targetVer = p.getProperty("targetVersion", "");
        String curSha = p.getProperty("currentSha256", "");
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
        Files.createDirectories(updateDir);
        Properties p = new Properties();
        p.setProperty("schema", String.valueOf(schema));
        p.setProperty("repository", repository);
        p.setProperty("installId", installId);
        p.setProperty("operation", operation.name());
        p.setProperty("transactionId", transactionId);
        p.setProperty("phase", phase.name());
        p.setProperty("parentPid", String.valueOf(parentPid));
        if (parentStartInstant != null) p.setProperty("parentStartInstant", parentStartInstant.toString());
        if (helperPid != null) p.setProperty("helperPid", String.valueOf(helperPid));
        if (helperStartInstant != null) p.setProperty("helperStartInstant", helperStartInstant.toString());
        if (childPid != null) p.setProperty("childPid", String.valueOf(childPid));
        if (childStartInstant != null) p.setProperty("childStartInstant", childStartInstant.toString());
        p.setProperty("configPath", configPath != null ? configPath : "");
        p.setProperty("workingDirectory", workingDirectory != null ? workingDirectory : "");
        p.setProperty("javaPath", javaPath != null ? javaPath : "");
        p.setProperty("allowedJvmArgs", allowedJvmArgs != null ? allowedJvmArgs : "");
        p.setProperty("currentVersion", currentVersion != null ? currentVersion : "");
        p.setProperty("targetVersion", targetVersion != null ? targetVersion : "");
        p.setProperty("currentSha256", currentSha256 != null ? currentSha256 : "");
        p.setProperty("candidateSha256", candidateSha256 != null ? candidateSha256 : "");
        p.setProperty("lastError", lastError != null ? lastError : "");

        Path target = updateDir.resolve(TRANSACTION_FILE);
        Path tmp = Files.createTempFile(updateDir, "tx.", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, null);
            }
            try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                fc.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception ex) {
            return null;
        }
    }
}
