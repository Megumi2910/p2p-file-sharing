package vn.edu.p2p.peer.update;

import vn.edu.p2p.peer.util.HashUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class UpdateInstaller {

    public static final String BOOTSTRAP_FLAG = "--update-bootstrap";
    public static final String RECOVER_FLAG = "--recover";
    public static final String READY_PREFIX = "P2P_HELPER_READY ";
    public static final String GO_PREFIX = "GO ";
    public static final String CHILD_READY_PREFIX = "P2P_BOOTSTRAP_READY ";
    public static final String COMMIT_PREFIX = "COMMIT ";

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: UpdateInstaller <installRoot> <transactionId> OR UpdateInstaller --recover <installRoot> [waitPid waitStartInstant]");
            System.exit(1);
        }

        try {
            if (RECOVER_FLAG.equals(args[0])) {
                if (args.length < 2) {
                    System.err.println("Usage: UpdateInstaller --recover <installRoot> [waitPid waitStartInstant]");
                    System.exit(1);
                }
                Path installRoot = Path.of(args[1]).toAbsolutePath().normalize();
                Long waitPid = args.length >= 4 ? Long.parseLong(args[2]) : null;
                Instant waitStart = args.length >= 4 ? Instant.parse(args[3]) : null;
                runRecovery(installRoot, waitPid, waitStart);
            } else {
                if (args.length < 2) {
                    System.err.println("Usage: UpdateInstaller <installRoot> <transactionId>");
                    System.exit(1);
                }
                Path installRoot = Path.of(args[0]).toAbsolutePath().normalize();
                String transactionId = args[1];
                runHelper(installRoot, transactionId);
            }
        } catch (Exception ex) {
            System.err.println("UpdateInstaller error: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public static void runHelper(Path installRoot, String transactionId) throws Exception {
        Path updateDir = installRoot.resolve(".p2p-update");
        if (!Files.exists(updateDir)) {
            throw new IOException("Update directory does not exist: " + updateDir);
        }

        try (UpdateLocks.FileLockHandle opLock = UpdateLocks.acquireOperationLock(updateDir, Duration.ofSeconds(10))) {
            UpdateJournal journal = UpdateJournal.read(updateDir);
            if (journal == null) {
                throw new IOException("No update journal found in: " + updateDir);
            }
            if (!transactionId.equals(journal.transactionId())) {
                throw new SecurityException("Transaction ID mismatch: expected " + journal.transactionId() + " but got " + transactionId);
            }
            if (journal.phase() != UpdateJournal.Phase.PREPARED) {
                throw new IllegalStateException("Journal is not in PREPARED state: " + journal.phase());
            }

            // Update helper process info in journal
            ProcessHandle self = ProcessHandle.current();
            journal = new UpdateJournal(
                    journal.schema(), journal.repository(), journal.installId(), journal.operation(),
                    journal.transactionId(), journal.phase(), journal.parentPid(), journal.parentStartInstant(),
                    self.pid(), self.info().startInstant().orElse(null),
                    journal.childPid(), journal.childStartInstant(),
                    journal.configPath(), journal.workingDirectory(), journal.javaPath(), journal.allowedJvmArgs(),
                    journal.currentVersion(), journal.targetVersion(), journal.currentSha256(), journal.candidateSha256(),
                    journal.lastError()
            );
            journal.writeAtomic(updateDir);

            // Signal READY on stdout
            System.out.println(READY_PREFIX + transactionId);
            System.out.flush();

            // Wait for GO on stdin (10-second deadline)
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            long readDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            String goLine = null;
            while (System.nanoTime() < readDeadline) {
                if (reader.ready()) {
                    goLine = reader.readLine();
                    break;
                }
                Thread.sleep(50);
            }

            if (goLine == null || !goLine.trim().equals(GO_PREFIX + transactionId)) {
                writeFailure(updateDir, journal, "Helper did not receive GO signal from parent within 10s");
                return;
            }

            // Capture parent ProcessHandle and wait for it to exit (up to 20s)
            Optional<ProcessHandle> parentOpt = ProcessHandle.of(journal.parentPid());
            if (parentOpt.isPresent()) {
                ProcessHandle parent = parentOpt.get();
                long parentDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (parent.isAlive() && System.nanoTime() < parentDeadline) {
                    Thread.sleep(100);
                }
                if (parent.isAlive()) {
                    writeFailure(updateDir, journal, "Parent process did not exit within 20s");
                    return;
                }
            }

            // Acquire exclusive runtime.lock (timeout 20s)
            UpdateLocks.FileLockHandle runtimeLock;
            try {
                runtimeLock = UpdateLocks.acquireExclusiveRuntimeLock(updateDir, Duration.ofSeconds(20));
            } catch (Exception ex) {
                writeFailure(updateDir, journal, "Cannot acquire exclusive runtime lock: other peers are running");
                // Relaunch parent once
                relaunchCanonical(installRoot, journal);
                return;
            }

            try {
                // Test atomic move on scratch file
                testAtomicMove(updateDir);

                Path peerAppJar = installRoot.resolve("peer-app.jar");
                Path candidateJar = updateDir.resolve("candidate.jar");
                Path previousJar = updateDir.resolve("previous.jar");

                if (journal.operation() == UpdateJournal.Operation.UPDATE) {
                    if (!Files.exists(candidateJar)) {
                        throw new IOException("Candidate JAR missing: " + candidateJar);
                    }
                    String candidateSha = HashUtil.sha256(candidateJar);
                    if (!candidateSha.equalsIgnoreCase(journal.candidateSha256())) {
                        throw new SecurityException("Candidate JAR hash mismatch before swap");
                    }

                    // Move to SWITCHING phase
                    journal = advancePhase(updateDir, journal, UpdateJournal.Phase.SWITCHING, null);

                    if (Files.exists(peerAppJar)) {
                        Files.move(peerAppJar, previousJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(candidateJar, peerAppJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }

                // Move to LAUNCHING phase
                journal = advancePhase(updateDir, journal, UpdateJournal.Phase.LAUNCHING, null);

                // Spawn child process with bootstrap flag
                Process child = spawnChild(installRoot, journal);
                journal = new UpdateJournal(
                        journal.schema(), journal.repository(), journal.installId(), journal.operation(),
                        journal.transactionId(), journal.phase(), journal.parentPid(), journal.parentStartInstant(),
                        journal.helperPid(), journal.helperStartInstant(),
                        child.pid(), child.info().startInstant().orElse(null),
                        journal.configPath(), journal.workingDirectory(), journal.javaPath(), journal.allowedJvmArgs(),
                        journal.currentVersion(), journal.targetVersion(), journal.currentSha256(), journal.candidateSha256(),
                        journal.lastError()
                );
                journal.writeAtomic(updateDir);

                // Wait for child readiness on stdout (20s)
                BufferedReader childOut = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                long childDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                boolean childReady = false;
                while (System.nanoTime() < childDeadline && child.isAlive()) {
                    if (childOut.ready()) {
                        String line = childOut.readLine();
                        if (line != null && line.startsWith(CHILD_READY_PREFIX + transactionId)) {
                            childReady = true;
                            break;
                        }
                    }
                    Thread.sleep(50);
                }

                if (!childReady) {
                    // Terminate child
                    terminateProcess(child);
                    if (journal.operation() == UpdateJournal.Operation.UPDATE) {
                        restorePrevious(installRoot, updateDir, peerAppJar, previousJar, journal.currentSha256());
                    }
                    journal = advancePhase(updateDir, journal, UpdateJournal.Phase.ROLLED_BACK, "Child failed bootstrap readiness check");
                    runtimeLock.close();
                    relaunchCanonical(installRoot, journal);
                    return;
                }

                // Acknowledged! Commit transaction
                journal = advancePhase(updateDir, journal, UpdateJournal.Phase.COMMITTED, null);

                // Release exclusive lock before sending COMMIT
                runtimeLock.close();

                // Send COMMIT to child stdin
                try {
                    OutputStream childIn = child.getOutputStream();
                    childIn.write((COMMIT_PREFIX + transactionId + "\n").getBytes(StandardCharsets.UTF_8));
                    childIn.flush();
                } catch (IOException ignored) {
                }

            } finally {
                if (runtimeLock != null) {
                    try {
                        runtimeLock.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    public static void runRecovery(Path installRoot, Long waitPid, Instant waitStart) throws Exception {
        Path updateDir = installRoot.resolve(".p2p-update");
        if (!Files.exists(updateDir)) {
            return;
        }

        if (waitPid != null) {
            Optional<ProcessHandle> ph = ProcessHandle.of(waitPid);
            if (ph.isPresent()) {
                ProcessHandle p = ph.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (p.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
            }
        }

        try (UpdateLocks.FileLockHandle opLock = UpdateLocks.acquireOperationLock(updateDir, Duration.ofSeconds(10))) {
            UpdateJournal journal = UpdateJournal.read(updateDir);
            if (journal == null) {
                return;
            }

            Path peerAppJar = installRoot.resolve("peer-app.jar");
            Path previousJar = updateDir.resolve("previous.jar");

            if (journal.phase() == UpdateJournal.Phase.COMMITTED) {
                // If committed and peer-app.jar exists, clean up staging
                if (Files.exists(peerAppJar)) {
                    Files.deleteIfExists(updateDir.resolve("candidate.jar.part"));
                }
                return;
            }

            if (journal.phase() == UpdateJournal.Phase.PREPARED) {
                // Check if parent is still alive
                Optional<ProcessHandle> parentOpt = ProcessHandle.of(journal.parentPid());
                if (parentOpt.isPresent() && parentOpt.get().isAlive()) {
                    return; // Do not touch live transaction
                }
                // Parent exited without finishing
                advancePhase(updateDir, journal, UpdateJournal.Phase.FAILED, "Parent abandoned prepared transaction");
                return;
            }

            // Exclusive runtime lock before restoring files
            try (UpdateLocks.FileLockHandle runtimeLock = UpdateLocks.acquireExclusiveRuntimeLock(updateDir, Duration.ofSeconds(20))) {
                if (!Files.exists(peerAppJar) && Files.exists(previousJar)) {
                    restorePrevious(installRoot, updateDir, peerAppJar, previousJar, journal.currentSha256());
                }
                advancePhase(updateDir, journal, UpdateJournal.Phase.ROLLED_BACK, "Recovered from interrupted transaction");
            }
        }
    }

    private static void restorePrevious(Path installRoot, Path updateDir, Path peerAppJar, Path previousJar, String expectedSha) throws IOException {
        if (!Files.exists(previousJar)) {
            throw new IOException("Cannot restore: previous.jar missing in " + updateDir);
        }
        if (expectedSha != null && !expectedSha.isBlank()) {
            String prevSha = HashUtil.sha256(previousJar);
            if (!prevSha.equalsIgnoreCase(expectedSha)) {
                throw new SecurityException("Cannot restore: previous.jar hash mismatch (" + prevSha + " vs expected " + expectedSha + ")");
            }
        }

        Path restoreTmp = updateDir.resolve("restore.tmp");
        Files.copy(previousJar, restoreTmp, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel fc = FileChannel.open(restoreTmp, StandardOpenOption.WRITE)) {
            fc.force(true);
        }
        Files.move(restoreTmp, peerAppJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void testAtomicMove(Path updateDir) throws IOException {
        Path scratch1 = Files.createTempFile(updateDir, "scratch1.", ".tmp");
        Path scratch2 = updateDir.resolve("scratch2.tmp");
        try {
            Files.writeString(scratch1, "test");
            Files.move(scratch1, scratch2, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(scratch1);
            Files.deleteIfExists(scratch2);
        }
    }

    private static Process spawnChild(Path installRoot, UpdateJournal journal) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(journal.javaPath());
        if (journal.allowedJvmArgs() != null && !journal.allowedJvmArgs().isBlank()) {
            command.addAll(Arrays.asList(journal.allowedJvmArgs().split(" ")));
        }
        command.add("-jar");
        command.add(installRoot.resolve("peer-app.jar").toAbsolutePath().toString());
        if (journal.configPath() != null && !journal.configPath().isBlank()) {
            command.add(journal.configPath());
        }
        command.add(BOOTSTRAP_FLAG);
        command.add(journal.transactionId());

        ProcessBuilder pb = new ProcessBuilder(command);
        if (journal.workingDirectory() != null && !journal.workingDirectory().isBlank()) {
            pb.directory(Path.of(journal.workingDirectory()).toFile());
        }
        return pb.start();
    }

    private static void relaunchCanonical(Path installRoot, UpdateJournal journal) {
        try {
            List<String> command = new ArrayList<>();
            command.add(journal.javaPath());
            if (journal.allowedJvmArgs() != null && !journal.allowedJvmArgs().isBlank()) {
                command.addAll(Arrays.asList(journal.allowedJvmArgs().split(" ")));
            }
            command.add("-jar");
            command.add(installRoot.resolve("peer-app.jar").toAbsolutePath().toString());
            if (journal.configPath() != null && !journal.configPath().isBlank()) {
                command.add(journal.configPath());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            if (journal.workingDirectory() != null && !journal.workingDirectory().isBlank()) {
                pb.directory(Path.of(journal.workingDirectory()).toFile());
            }
            pb.start();
        } catch (Exception ex) {
            System.err.println("Failed to relaunch application: " + ex.getMessage());
        }
    }

    private static void terminateProcess(Process child) {
        if (child == null || !child.isAlive()) {
            return;
        }
        child.destroy();
        try {
            if (!child.waitFor(5, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
        }
    }

    private static UpdateJournal advancePhase(Path updateDir, UpdateJournal journal, UpdateJournal.Phase phase, String error) throws IOException {
        UpdateJournal next = new UpdateJournal(
                journal.schema(), journal.repository(), journal.installId(), journal.operation(),
                journal.transactionId(), phase, journal.parentPid(), journal.parentStartInstant(),
                journal.helperPid(), journal.helperStartInstant(),
                journal.childPid(), journal.childStartInstant(),
                journal.configPath(), journal.workingDirectory(), journal.javaPath(), journal.allowedJvmArgs(),
                journal.currentVersion(), journal.targetVersion(), journal.currentSha256(), journal.candidateSha256(),
                error != null ? error : journal.lastError()
        );
        next.writeAtomic(updateDir);
        return next;
    }

    private static void writeFailure(Path updateDir, UpdateJournal journal, String reason) {
        try {
            advancePhase(updateDir, journal, UpdateJournal.Phase.FAILED, reason);
        } catch (IOException ignored) {
        }
    }
}
