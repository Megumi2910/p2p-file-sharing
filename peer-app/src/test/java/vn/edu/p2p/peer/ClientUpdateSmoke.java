package vn.edu.p2p.peer;

import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.config.ConfigSnapshot;
import vn.edu.p2p.peer.config.ConfigStore;
import vn.edu.p2p.peer.update.ClientVersion;
import vn.edu.p2p.peer.update.ReleaseClient;
import vn.edu.p2p.peer.update.ReleaseManifest;
import vn.edu.p2p.peer.update.RestartCoordinator;
import vn.edu.p2p.peer.update.UpdateInstaller;
import vn.edu.p2p.peer.update.UpdateJournal;
import vn.edu.p2p.peer.update.UpdateLocks;
import vn.edu.p2p.peer.util.HashUtil;
import vn.edu.p2p.tracker.TrackerServer;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ClientUpdateSmoke {

    public static void main(String[] args) {
        System.out.println("=== Starting ClientUpdateSmoke ===");

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Error: ClientUpdateSmoke requires a non-headless display (-Djava.awt.headless=false)");
            System.exit(1);
        }

        try {
            runSmoke();
            System.out.println("\n=== ClientUpdateSmoke verification PASSED on " + System.getProperty("os.name") + " ===");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("\n=== ClientUpdateSmoke FAILED ===");
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runSmoke() throws Exception {
        Path tempDir = Files.createTempDirectory("p2p-update-smoke-");
        System.out.println("Working in external temp directory: " + tempDir.toAbsolutePath());

        // 1. Generate ephemeral Ed25519 keypair
        System.out.println("Step 1: Generating ephemeral Ed25519 keypair...");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = kpg.generateKeyPair();
        String pubKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        // 2. Locate base shaded JAR
        Path baseJar = Path.of("peer-app/target/peer-app.jar");
        if (!Files.exists(baseJar)) {
            baseJar = Path.of("target/peer-app.jar");
        }
        if (!Files.exists(baseJar)) {
            throw new IOException("Base shaded JAR not found: " + baseJar.toAbsolutePath());
        }

        // 3. Create versioned fixture JAR copies (v1.0.0 and v1.0.1)
        System.out.println("Step 2: Building signed fixture JARs (1.0.0 and 1.0.1)...");
        Path jar100 = tempDir.resolve("peer-app-1.0.0.jar");
        Path jar101 = tempDir.resolve("peer-app-1.0.1.jar");
        createFixtureJar(baseJar, jar100, "1.0.0", pubKeyBase64);
        createFixtureJar(baseJar, jar101, "1.0.1", pubKeyBase64);

        // Verify bytecode unmodified by checking class entry SHA-256
        assertClassHashesEqual(baseJar, jar100);
        assertClassHashesEqual(baseJar, jar101);

        // 4. Create signed update manifest for 1.0.1
        System.out.println("Step 3: Creating signed release manifest for 1.0.1...");
        long jar101Size = Files.size(jar101);
        String jar101Sha = HashUtil.sha256(jar101);

        Path zip101 = tempDir.resolve("p2p-client-1.0.1.zip");
        createMinimalZip(jar101, zip101, "1.0.1");
        long zip101Size = Files.size(zip101);
        String zip101Sha = HashUtil.sha256(zip101);

        ReleaseManifest manifest101 = new ReleaseManifest(
                1,
                ReleaseClient.EXPECTED_REPOSITORY,
                ClientVersion.parse("1.0.1"),
                "peer-app.jar",
                jar101Size,
                jar101Sha,
                21,
                1,
                new ReleaseManifest.BundleInfo("p2p-client-1.0.1.zip", zip101Size, zip101Sha)
        );

        byte[] manifestBytes = manifest101.toJsonUtf8();
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(keyPair.getPrivate());
        sig.update(manifestBytes);
        byte[] sigBytes = sig.sign();

        ReleaseManifest.parseAndVerify(manifestBytes, sigBytes, keyPair.getPublic(), ReleaseClient.EXPECTED_REPOSITORY);

        // 5. Start TrackerServer on dynamic port
        System.out.println("Step 4: Starting TrackerServer...");
        TrackerServer tracker = new TrackerServer(0);
        Thread trackerThread = new Thread(() -> {
            try {
                tracker.start();
            } catch (Exception ex) {
                System.err.println("Tracker thread exited: " + ex.getMessage());
            }
        }, "smoke-tracker");
        trackerThread.setDaemon(true);
        trackerThread.start();

        int trackerPort = 0;
        long trackerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < trackerDeadline) {
            try {
                trackerPort = tracker.localPort();
                if (trackerPort > 0) break;
            } catch (Exception e) {
                Thread.sleep(20);
            }
        }

        // 6. Setup install directory and peers with paths containing spaces and Unicode
        System.out.println("Step 5: Configuring peers with Unicode and paths with spaces...");
        Path installRoot = tempDir.resolve("P2P Client Installation");
        Files.createDirectories(installRoot);

        Path canonicalJar = installRoot.resolve("peer-app.jar");
        Files.copy(jar100, canonicalJar, StandardCopyOption.REPLACE_EXISTING);

        Path peer1Dir = tempDir.resolve("Peer Một");
        Path peer1Downloads = peer1Dir.resolve("Tải Về");
        Path peer1Shared = peer1Dir.resolve("Chia Sẻ");
        Files.createDirectories(peer1Downloads);
        Files.createDirectories(peer1Shared);

        Path testShareFile = peer1Shared.resolve("sample document.txt");
        Files.writeString(testShareFile, "Hello P2P File Sharing Smoke Test Content! 1234567890");
        String shareSha256 = HashUtil.sha256(testShareFile);

        int peer1Port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { peer1Port = s.getLocalPort(); }
        int peer2Port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { peer2Port = s.getLocalPort(); }

        Path peer1ConfigFile = peer1Dir.resolve("peer1.properties");
        java.util.Properties p1 = new java.util.Properties();
        p1.setProperty("peer.id", "peer-smoke-1");
        p1.setProperty("peer.name", "Nguyễn Văn Một");
        p1.setProperty("peer.port", String.valueOf(peer1Port));
        p1.setProperty("tracker.host", "127.0.0.1");
        p1.setProperty("tracker.port", String.valueOf(trackerPort));
        p1.setProperty("download.dir", peer1Downloads.toString());
        p1.setProperty("shared.dir", peer1Shared.toString());
        p1.setProperty("transfer.autoAccept", "false");
        p1.setProperty("updates.checkOnStartup", "false");
        try (java.io.OutputStream out = Files.newOutputStream(peer1ConfigFile)) {
            p1.store(out, null);
        }

        Path peer2Dir = tempDir.resolve("Peer Hai");
        Path peer2Downloads = peer2Dir.resolve("Tải Về 2");
        Path peer2Shared = peer2Dir.resolve("Chia Sẻ 2");
        Files.createDirectories(peer2Downloads);
        Files.createDirectories(peer2Shared);

        Path peer2ConfigFile = peer2Dir.resolve("peer2.properties");
        java.util.Properties p2 = new java.util.Properties();
        p2.setProperty("peer.id", "peer-smoke-2");
        p2.setProperty("peer.name", "Trần Thị Hai");
        p2.setProperty("peer.port", String.valueOf(peer2Port));
        p2.setProperty("tracker.host", "127.0.0.1");
        p2.setProperty("tracker.port", String.valueOf(trackerPort));
        p2.setProperty("download.dir", peer2Downloads.toString());
        p2.setProperty("shared.dir", peer2Shared.toString());
        p2.setProperty("updates.checkOnStartup", "false");
        try (java.io.OutputStream out = Files.newOutputStream(peer2ConfigFile)) {
            p2.store(out, null);
        }

        AppConfig config1 = AppConfig.load(peer1ConfigFile);
        AppConfig config2 = AppConfig.load(peer2ConfigFile);

        PeerRuntime runtime1 = new PeerRuntime(config1);
        PeerRuntime runtime2 = new PeerRuntime(config2);

        // Setup prompt latch on peer1 to exercise prompt-pending state
        CountDownLatch promptEntered = new CountDownLatch(1);
        CountDownLatch promptAllow = new CountDownLatch(1);

        runtime1.transferManager().setIncomingFilePrompt((metadata, sender, timeout) -> {
            promptEntered.countDown();
            try {
                promptAllow.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return true;
        });

        runtime1.start();
        runtime2.start();

        runtime1.refreshSharedFiles();
        runtime2.refreshSharedFiles();

        System.out.println("Peer 1 started on port: " + runtime1.config().peerPort());
        System.out.println("Peer 2 started on port: " + runtime2.config().peerPort());

        // 7. Verify settings save and restart refusal during pending prompt
        System.out.println("Step 6: Initiating file transfer and testing restart refusal during pending prompt...");
        ConfigStore store1 = new ConfigStore(peer1ConfigFile, peer1Dir);
        ConfigSnapshot snap1 = store1.read();

        // Trigger send from peer 2 to peer 1
        PeerInfo target1 = new PeerInfo(runtime1.config().peerId(), runtime1.config().displayName(), "127.0.0.1", runtime1.config().peerPort());
        Path peer2File = peer2Shared.resolve("file_from_peer2.txt");
        Files.writeString(peer2File, "Data from peer 2 to peer 1 for prompt test");
        runtime2.sendFile(target1, peer2File);

        assertTrue("Prompt should be entered on peer 1", promptEntered.await(5, TimeUnit.SECONDS));

        // While prompt is open, tryReserveRestart on peer 1 must return false
        boolean reserveAttempt = runtime1.transferManager().tryReserveRestart();
        assertFalse("Restart reservation MUST be refused while prompt is active", reserveAttempt);

        // Edit and save settings on peer 1 during active prompt
        ConfigSnapshot saved1 = store1.save(snap1, Map.of("peer.name", "Nguyễn Văn Một Updated"));
        assertTrue("Saved snapshot exists", saved1.exists());
        assertEquals("Running peer display name unchanged before restart", "Nguyễn Văn Một", runtime1.config().displayName());

        // Allow prompt to complete
        promptAllow.countDown();
        Thread.sleep(1000);

        // After transfer finishes, reservation must succeed
        long idleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runtime1.transferManager().hasActiveSessions() && System.nanoTime() < idleDeadline) {
            Thread.sleep(50);
        }
        assertTrue("Restart reservation MUST succeed when idle", runtime1.transferManager().tryReserveRestart());
        runtime1.transferManager().cancelRestartReservation();

        // 8. Staged update simulation and execution
        System.out.println("Step 7: Testing packaged update to version 1.0.1 and rollback recovery...");
        Path updateDir = installRoot.resolve(".p2p-update");
        Files.createDirectories(updateDir);

        Path stagedCandidate = updateDir.resolve("candidate.jar");
        Files.copy(jar101, stagedCandidate, StandardCopyOption.REPLACE_EXISTING);
        Files.write(updateDir.resolve("update-manifest.json"), manifestBytes);
        Files.write(updateDir.resolve("update-manifest.sig"), sigBytes);

        String txId = "smoke-tx-" + UUID.randomUUID();
        String installId = UpdateJournal.getOrCreateInstallId(updateDir, ReleaseClient.EXPECTED_REPOSITORY, installRoot);

        UpdateJournal journal = new UpdateJournal(
                1, ReleaseClient.EXPECTED_REPOSITORY, installId,
                UpdateJournal.Operation.UPDATE, txId,
                UpdateJournal.Phase.SWITCHING,
                ProcessHandle.current().pid(), Instant.now(),
                null, null, null, null,
                peer1ConfigFile.toString(), peer1Dir.toString(),
                ProcessHandle.current().info().command().orElse("java"), "",
                "1.0.0", "1.0.1", HashUtil.sha256(canonicalJar), jar101Sha, ""
        );
        journal.writeAtomic(updateDir);

        // Simulate switching: canonicalJar moved to previous.jar, candidate moves to canonical
        Path previousJar = updateDir.resolve("previous.jar");
        Files.move(canonicalJar, previousJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Files.move(stagedCandidate, canonicalJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        assertEquals("Canonical JAR now has 1.0.1 hash", jar101Sha, HashUtil.sha256(canonicalJar));

        // 9. Exercise recovery from broken fixture (rollback check)
        System.out.println("Step 8: Testing automatic rollback when child fails bootstrap...");
        // Corrupt canonical jar to simulate failed launch
        Files.deleteIfExists(canonicalJar);

        UpdateInstaller.runRecovery(installRoot, null, null);

        // Previous jar must have been restored
        assertTrue("Previous JAR restored to canonical path", Files.exists(canonicalJar));
        UpdateJournal recoveredJournal = UpdateJournal.read(updateDir);
        assertEquals("Journal rolled back", UpdateJournal.Phase.ROLLED_BACK, recoveredJournal.phase());

        // 10. Clean shutdown of runtimes and tracker
        System.out.println("Step 9: Cleaning up test runtimes...");
        runtime1.close();
        runtime2.close();
        tracker.close();

        System.out.println("Step 10: Verifying data preservation...");
        assertTrue("Original shared file intact", Files.exists(testShareFile));
        assertEquals("Original shared file hash intact", shareSha256, HashUtil.sha256(testShareFile));
        assertTrue("Peer 1 config intact", Files.exists(peer1ConfigFile));
        assertTrue("Peer 2 config intact", Files.exists(peer2ConfigFile));
    }

    private static void createFixtureJar(Path baseJar, Path outJar, String version, String pubKey) throws IOException {
        try (JarFile in = new JarFile(baseJar.toFile());
             JarOutputStream out = new JarOutputStream(new FileOutputStream(outJar.toFile()))) {

            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if ("vn/edu/p2p/peer/update/build.properties".equals(entry.getName())) {
                    continue;
                }
                out.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream is = in.getInputStream(entry)) {
                    is.transferTo(out);
                }
                out.closeEntry();
            }

            // Write modified build.properties
            JarEntry bpEntry = new JarEntry("vn/edu/p2p/peer/update/build.properties");
            out.putNextEntry(bpEntry);
            String bpContent = "version=" + version + "\nrepository=" + ReleaseClient.EXPECTED_REPOSITORY + "\npublicKey=" + pubKey + "\ninstallerProtocol=1\n";
            out.write(bpContent.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void createMinimalZip(Path jar, Path outZip, String version) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outZip.toFile()))) {
            out.putNextEntry(new ZipEntry("p2p-client/"));
            out.closeEntry();

            out.putNextEntry(new ZipEntry("p2p-client/peer-app.jar"));
            Files.copy(jar, out);
            out.closeEntry();

            out.putNextEntry(new ZipEntry("p2p-client/run-peer-linux.sh"));
            out.write("#!/usr/bin/env bash\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            out.putNextEntry(new ZipEntry("p2p-client/run-peer-windows.bat"));
            out.write("@echo off\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            out.putNextEntry(new ZipEntry("p2p-client/peer.properties.example"));
            out.write("peer.id=example\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void assertClassHashesEqual(Path jar1, Path jar2) throws Exception {
        Map<String, String> classes1 = extractClassHashes(jar1);
        Map<String, String> classes2 = extractClassHashes(jar2);
        if (!classes1.equals(classes2)) {
            throw new AssertionError("Bytecode class entry hashes changed between fixture copies!");
        }
    }

    private static Map<String, String> extractClassHashes(Path jarPath) throws Exception {
        Map<String, String> map = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        byte[] bytes = in.readAllBytes();
                        map.put(entry.getName(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    }
                }
            }
        }
        return map;
    }

    private static void assertTrue(String msg, boolean condition) {
        if (!condition) {
            throw new AssertionError(msg);
        }
    }

    private static void assertFalse(String msg, boolean condition) {
        if (condition) {
            throw new AssertionError(msg);
        }
    }

    private static void assertEquals(String msg, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(msg + " - expected: " + expected + " but got: " + actual);
        }
    }
}
