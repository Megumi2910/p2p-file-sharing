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
import java.security.PublicKey;
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
                if (args.length != 2 && args.length != 4) {
                    System.err.println("Usage: UpdateInstaller --recover <installRoot> OR UpdateInstaller --recover <installRoot> <waitPid> <waitStartInstant>");
                    System.exit(1);
                }
                Path installRoot = Path.of(args[1]).toAbsolutePath().normalize();
                Long waitPid = null;
                Instant waitStart = null;
                if (args.length == 4) {
                    waitPid = Long.parseLong(args[2]);
                    waitStart = Instant.parse(args[3]);
                }
                runRecovery(installRoot, waitPid, waitStart);
            } else {
                if (args.length != 2) {
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
        Objects.requireNonNull(installRoot, "installRoot cannot be null");
        Objects.requireNonNull(transactionId, "transactionId cannot be null");

        Path updateDir = installRoot.resolve(".p2p-update");
        if (!Files.isDirectory(updateDir)) {
            throw new IOException("Update directory does not exist or is not a directory: " + updateDir);
        }

        UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);

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

            // Capture actual parent ProcessHandle and startInstant
            Optional<ProcessHandle> parentOpt = ProcessHandle.of(journal.parentPid());
            if (parentOpt.isEmpty() || !parentOpt.get().isAlive()) {
                writeFailure(updateDir, journal, "Parent process is not alive");
                return;
            }
            ProcessHandle parent = parentOpt.get();
            Instant parentStart = parent.info().startInstant().orElse(null);
            if (parentStart == null || !parentStart.equals(journal.parentStartInstant())) {
                throw new SecurityException("Parent start instant mismatch: expected "
                        + journal.parentStartInstant() + " but observed " + parentStart);
            }

            // Update helper process info in journal
            ProcessHandle self = ProcessHandle.current();
            Instant helperStart = self.info().startInstant().orElse(null);
            if (helperStart == null) {
                throw new IllegalStateException("Helper start instant cannot be determined");
            }

            journal = new UpdateJournal(
                    journal.schema(), journal.repository(), journal.installId(), journal.operation(),
                    journal.transactionId(), journal.phase(), journal.parentPid(), journal.parentStartInstant(),
                    self.pid(), helperStart,
                    journal.childPid(), journal.childStartInstant(),
                    journal.configPath(), journal.workingDirectory(), journal.javaPath(), journal.allowedJvmArgs(),
                    journal.currentVersion(), journal.targetVersion(), journal.currentSha256(), journal.candidateSha256(),
                    journal.lastError()
            );
            journal.writeAtomic(updateDir);

            // Signal READY on stdout
            System.out.println(READY_PREFIX + transactionId);
            System.out.flush();

            // Wait for exact GO on stdin (10-second deadline)
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

            String expectedGo = GO_PREFIX + transactionId;
            if (goLine == null || !expectedGo.equals(goLine.trim())) {
                writeFailure(updateDir, journal, "Helper did not receive exact GO signal from parent within 10s");
                return;
            }

            // Await parent's actual exit (up to 20s)
            long parentDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (parent.isAlive() && System.nanoTime() < parentDeadline) {
                Thread.sleep(100);
            }
            if (parent.isAlive()) {
                writeFailure(updateDir, journal, "Parent process did not exit within 20s");
                return;
            }

            // Acquire exclusive runtime.lock within 20s after parent exit
            UpdateLocks.FileLockHandle runtimeLock;
            try {
                runtimeLock = UpdateLocks.acquireExclusiveRuntimeLock(updateDir, Duration.ofSeconds(20));
            } catch (Exception ex) {
                writeFailure(updateDir, journal, "Cannot acquire exclusive runtime lock: other peers are running");
                relaunchCanonical(installRoot, journal);
                return;
            }

            Process child = null;
            try {
                testAtomicMove(updateDir);

                Path peerAppJar = installRoot.resolve("peer-app.jar");
                Path candidateJar = updateDir.resolve("candidate.jar");
                Path previousJar = updateDir.resolve("previous.jar");

                PublicKey helperKey = BuildInfo.load().publicKey();

                if (journal.operation() == UpdateJournal.Operation.UPDATE) {
                    if (!Files.exists(candidateJar)) {
                        throw new IOException("Candidate JAR missing: " + candidateJar);
                    }

                    // Re-read manifest and signature and verify using helper embedded key
                    Path manifestPath = updateDir.resolve("update-manifest.json");
                    Path sigPath = updateDir.resolve("update-manifest.sig");
                    if (!Files.exists(manifestPath) || !Files.exists(sigPath)) {
                        throw new IOException("Staged manifest or signature missing");
                    }
                    byte[] manifestBytes = Files.readAllBytes(manifestPath);
                    byte[] sigBytes = Files.readAllBytes(sigPath);
                    ReleaseManifest manifest = ReleaseManifest.parseAndVerify(manifestBytes, sigBytes, helperKey, BuildInfo.DEFAULT_REPOSITORY);

                    ReleaseManifest.verifyJar(candidateJar, manifest, helperKey);

                    // Check canonical hash
                    if (Files.exists(peerAppJar)) {
                        String currentSha = HashUtil.sha256(peerAppJar);
                        if (!currentSha.equalsIgnoreCase(journal.currentSha256())) {
                            throw new SecurityException("Current canonical JAR hash mismatch before swap");
                        }
                    }

                    // Move to SWITCHING phase
                    journal = advancePhase(updateDir, journal, UpdateJournal.Phase.SWITCHING, null);

                    if (Files.exists(peerAppJar)) {
                        Files.move(peerAppJar, previousJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(candidateJar, peerAppJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // RESTART: verify current JAR hash, no swap
                    if (Files.exists(peerAppJar)) {
                        String currentSha = HashUtil.sha256(peerAppJar);
                        if (!currentSha.equalsIgnoreCase(journal.currentSha256())) {
                            throw new SecurityException("Current canonical JAR hash mismatch during restart");
                        }
                    }
                }

                // Move to LAUNCHING phase
                journal = advancePhase(updateDir, journal, UpdateJournal.Phase.LAUNCHING, null);

                // Spawn child process with bootstrap flag
                child = spawnChild(installRoot, journal);
                Instant childStart = child.info().startInstant().orElse(null);
                if (childStart == null) {
                    throw new IOException("Child process start instant cannot be determined");
                }

                journal = new UpdateJournal(
                        journal.schema(), journal.repository(), journal.installId(), journal.operation(),
                        journal.transactionId(), journal.phase(), journal.parentPid(), journal.parentStartInstant(),
                        journal.helperPid(), journal.helperStartInstant(),
                        child.pid(), childStart,
                        journal.configPath(), journal.workingDirectory(), journal.javaPath(), journal.allowedJvmArgs(),
                        journal.currentVersion(), journal.targetVersion(), journal.currentSha256(), journal.candidateSha256(),
                        journal.lastError()
                );
                journal.writeAtomic(updateDir);

                // Bounded wait for exact child readiness on stdout (20s)
                RestartCoordinator.ProcessLineReader childLineReader = new RestartCoordinator.ProcessLineReader(child);
                String childReadyLine = null;
                try {
                    childReadyLine = childLineReader.readLineWithDeadline(Duration.ofSeconds(20));
                } catch (Exception ex) {
                    childReadyLine = null;
                }

                String expectedChildReady = CHILD_READY_PREFIX + transactionId + " " + journal.targetVersion();
                if (childReadyLine == null || !expectedChildReady.equals(childReadyLine)) {
                    // Child readiness failed
                    terminateProcess(child);
                    if (journal.operation() == UpdateJournal.Operation.UPDATE) {
                        restorePrevious(installRoot, updateDir, peerAppJar, previousJar, journal.currentSha256());
                    }
                    journal = advancePhase(updateDir, journal, UpdateJournal.Phase.ROLLED_BACK, "Child failed bootstrap readiness check");
                    runtimeLock.close();
                    relaunchCanonical(installRoot, journal);
                    return;
                }

                // Child successfully signaled READY! Commit transaction
                journal = advancePhase(updateDir, journal, UpdateJournal.Phase.COMMITTED, null);

                // Release exclusive runtime lock before sending COMMIT
                runtimeLock.close();

                // Send exact COMMIT to child stdin
                try {
                    OutputStream childIn = child.getOutputStream();
                    childIn.write((COMMIT_PREFIX + transactionId + "\n").getBytes(StandardCharsets.UTF_8));
                    childIn.flush();
                } catch (IOException ex) {
                    System.err.println("Notice: Helper failed to deliver COMMIT to child: " + ex.getMessage());
                }
            } catch (Exception ex) {
                if (child != null && child.isAlive()) {
                    terminateProcess(child);
                }
                Path peerAppJar = installRoot.resolve("peer-app.jar");
                Path previousJar = updateDir.resolve("previous.jar");
                if (journal.operation() == UpdateJournal.Operation.UPDATE && journal.phase() == UpdateJournal.Phase.SWITCHING) {
                    restorePrevious(installRoot, updateDir, peerAppJar, previousJar, journal.currentSha256());
                    advancePhase(updateDir, journal, UpdateJournal.Phase.ROLLED_BACK, "Rolled back after error: " + ex.getMessage());
                } else {
                    advancePhase(updateDir, journal, UpdateJournal.Phase.FAILED, "Helper failed: " + ex.getMessage());
                }
                throw ex;
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
        Objects.requireNonNull(installRoot, "installRoot cannot be null");
        Path updateDir = installRoot.resolve(".p2p-update");
        if (!Files.isDirectory(updateDir)) {
            return;
        }

        UpdateJournal.getOrCreateInstallId(updateDir, BuildInfo.DEFAULT_REPOSITORY, installRoot);

        if (waitPid != null && waitStart != null) {
            Optional<ProcessHandle> ph = ProcessHandle.of(waitPid);
            if (ph.isPresent() && ph.get().isAlive()) {
                Instant start = ph.get().info().startInstant().orElse(null);
                if (start != null && start.equals(waitStart)) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (ph.get().isAlive() && System.nanoTime() < deadline) {
                        Thread.sleep(100);
                    }
                }
            }
        }

        try (UpdateLocks.FileLockHandle opLock = UpdateLocks.acquireOperationLock(updateDir, Duration.ofSeconds(10))) {
            UpdateJournal journal = UpdateJournal.read(updateDir);
            if (journal == null) {
                return;
            }

            // Exclude live parent / helper / child
            Optional<ProcessHandle> parentOpt = ProcessHandle.of(journal.parentPid());
            if (parentOpt.isPresent() && parentOpt.get().isAlive()) {
                Instant s = parentOpt.get().info().startInstant().orElse(null);
                if (s != null && s.equals(journal.parentStartInstant())) {
                    System.err.println("Active transaction parent is still alive; skipping recovery.");
                    return;
                }
            }
            if (journal.helperPid() != null) {
                Optional<ProcessHandle> helperOpt = ProcessHandle.of(journal.helperPid());
                if (helperOpt.isPresent() && helperOpt.get().isAlive()) {
                    Instant s = helperOpt.get().info().startInstant().orElse(null);
                    if (s != null && s.equals(journal.helperStartInstant())) {
                        System.err.println("Active transaction helper is still alive; skipping recovery.");
                        return;
                    }
                }
            }
            if (journal.childPid() != null) {
                Optional<ProcessHandle> childOpt = ProcessHandle.of(journal.childPid());
                if (childOpt.isPresent() && childOpt.get().isAlive()) {
                    Instant s = childOpt.get().info().startInstant().orElse(null);
                    if (s != null && s.equals(journal.childStartInstant())) {
                        System.err.println("Active transaction child is still alive; skipping recovery.");
                        return;
                    }
                }
            }

            Path peerAppJar = installRoot.resolve("peer-app.jar");
            Path previousJar = updateDir.resolve("previous.jar");

            String canonicalHash = Files.exists(peerAppJar) ? HashUtil.sha256(peerAppJar) : null;
            String previousHash = Files.exists(previousJar) ? HashUtil.sha256(previousJar) : null;

            try (UpdateLocks.FileLockHandle runtimeLock = UpdateLocks.acquireExclusiveRuntimeLock(updateDir, Duration.ofSeconds(20))) {
                // 1. COMMITTED phase
                if (journal.phase() == UpdateJournal.Phase.COMMITTED) {
                    String targetHash = journal.operation() == UpdateJournal.Operation.UPDATE
                            ? journal.candidateSha256()
                            : journal.currentSha256();
                    if (canonicalHash != null && canonicalHash.equalsIgnoreCase(targetHash)) {
                        Files.deleteIfExists(updateDir.resolve("candidate.jar.part"));
                        Files.deleteIfExists(updateDir.resolve("candidate.jar"));
                        return;
                    }
                    System.err.println("Manual recovery required: COMMITTED canonical hash mismatch. Canonical: "
                            + peerAppJar + ", Expected: " + targetHash + ", Found: " + canonicalHash);
                    return;
                }

                // 2. Before COMMITTED and canonical hash equals currentSha256: retain it
                if (canonicalHash != null && canonicalHash.equalsIgnoreCase(journal.currentSha256())) {
                    if (journal.phase() == UpdateJournal.Phase.PREPARED) {
                        advancePhase(updateDir, journal, UpdateJournal.Phase.FAILED, "Parent abandoned prepared transaction");
                    } else {
                        advancePhase(updateDir, journal, UpdateJournal.Phase.ROLLED_BACK, "Interrupted switching rolled back");
                    }
                    Files.deleteIfExists(updateDir.resolve("candidate.jar.part"));
                    return;
                }

                // 3. UPDATE before COMMITTED with either canonical missing or canonical hash=candidateSha256, and previous hash=currentSha256
                if (journal.operation() == UpdateJournal.Operation.UPDATE) {
                    boolean canonicalMatchesCandidate = canonicalHash != null && canonicalHash.equalsIgnoreCase(journal.candidateSha256());
                    boolean canonicalMissing = (canonicalHash == null);
                    boolean previousMatchesCurrent = previousHash != null && previousHash.equalsIgnoreCase(journal.currentSha256());

                    if ((canonicalMissing || canonicalMatchesCandidate) && previousMatchesCurrent) {
                        restorePrevious(installRoot, updateDir, peerAppJar, previousJar, journal.currentSha256());
                        advancePhase(updateDir, journal, UpdateJournal.Phase.ROLLED_BACK, "Restored previous JAR from interrupted update");
                        Files.deleteIfExists(updateDir.resolve("candidate.jar.part"));
                        return;
                    }
                }

                // 4. RESTART with missing/wrong canonical refuses automatic recovery
                if (journal.operation() == UpdateJournal.Operation.RESTART) {
                    System.err.println("Manual recovery required: RESTART transaction canonical JAR missing or corrupted. Canonical: " + peerAppJar);
                    return;
                }

                // 5. Unknown hashes or uncertain state
                System.err.println("Manual recovery required. Uncertainty in state or hashes. Canonical: "
                        + peerAppJar + ", Previous: " + previousJar + ", Journal: " + updateDir.resolve(UpdateJournal.TRANSACTION_FILE));
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
            System.err.println("Failed to relaunch canonical JAR: " + ex.getMessage());
        }
    }

    private static void terminateProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static UpdateJournal advancePhase(Path updateDir, UpdateJournal current, UpdateJournal.Phase newPhase, String error) throws IOException {
        UpdateJournal next = new UpdateJournal(
                current.schema(), current.repository(), current.installId(), current.operation(),
                current.transactionId(), newPhase, current.parentPid(), current.parentStartInstant(),
                current.helperPid(), current.helperStartInstant(),
                current.childPid(), current.childStartInstant(),
                current.configPath(), current.workingDirectory(), current.javaPath(), current.allowedJvmArgs(),
                current.currentVersion(), current.targetVersion(), current.currentSha256(), current.candidateSha256(),
                error != null ? error : current.lastError()
        );
        next.writeAtomic(updateDir);
        return next;
    }

    private static void writeFailure(Path updateDir, UpdateJournal current, String message) {
        try {
            advancePhase(updateDir, current, UpdateJournal.Phase.FAILED, message);
        } catch (IOException ignored) {
        }
    }
}
