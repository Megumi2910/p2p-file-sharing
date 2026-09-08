package vn.edu.p2p.peer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.peer.PeerRuntime;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {

    @Test
    void testRoundTripWithUnicodeWindowsPathsAndAdvancedSettings(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("my-custom-node.properties");
        String initialContent = """
                peer.id=peer-alpha-999
                peer.name=Nguy\\u1ec5n V\\u0103n A
                peer.port=6001
                tracker.host=127.0.0.1
                tracker.port=5000
                download.dir=C:\\\\My Downloads\\\\P2P Path
                shared.dir=C:\\\\My Shared Files\\\\P2P Share
                custom.keep=unchanged
                chunk.size.bytes=1048576
                transfer.autoAccept=false
                updates.checkOnStartup=true
                tracker.read.timeout.ms=15000
                transfer.read.timeout.ms=15000
                transfer.prompt.timeout.ms=120000
                transfer.offer.response.timeout.ms=135000
                transfer.verify.timeout.ms=300000
                transfer.max.concurrent=4
                """;
        Files.writeString(configFile, initialContent);

        AppConfig initialConfig = AppConfig.load(configFile);
        PeerRuntime runtime = new PeerRuntime(initialConfig);

        ConfigStore store = new ConfigStore(configFile, tempDir);
        ConfigSnapshot snapshot = store.read();
        assertTrue(snapshot.exists());
        assertEquals("peer-alpha-999", snapshot.getProperty("peer.id"));
        assertEquals("Nguyễn Văn A", snapshot.getProperty("peer.name"));
        assertEquals("unchanged", snapshot.getProperty("custom.keep"));
        assertEquals("true", snapshot.getProperty("updates.checkOnStartup"));

        Map<String, String> edits = Map.of(
                "peer.name", "Nguyễn Văn B",
                "peer.port", "6002",
                "transfer.autoAccept", "true",
                "updates.checkOnStartup", "false",
                "download.dir", "D:\\New Download\\Sub"
        );

        ConfigSnapshot saved = store.save(snapshot, edits);
        assertTrue(saved.exists());
        assertNotNull(saved.sha256());

        // Assert running PeerRuntime is unaffected before restart
        assertEquals("Nguyễn Văn A", runtime.config().displayName());
        assertEquals(6001, runtime.config().peerPort());
        assertFalse(runtime.config().autoAccept());

        // Assert saved file matches edited values on disk
        AppConfig reloaded = AppConfig.load(configFile);
        assertEquals("peer-alpha-999", reloaded.peerId());
        assertEquals("Nguyễn Văn B", reloaded.displayName());
        assertEquals(6002, reloaded.peerPort());
        assertTrue(reloaded.autoAccept());
        assertEquals(Path.of("D:\\New Download\\Sub"), reloaded.downloadDir());

        ConfigSnapshot secondRead = store.read();
        assertEquals("unchanged", secondRead.getProperty("custom.keep"));
        assertEquals("false", secondRead.getProperty("updates.checkOnStartup"));
    }

    @Test
    void testValidationRejectionDoesNotModifyFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("node.properties");
        String initialContent = """
                peer.id=peer-1
                peer.name=Alice
                peer.port=6001
                tracker.host=127.0.0.1
                tracker.port=5000
                """;
        Files.writeString(configFile, initialContent);
        byte[] originalBytes = Files.readAllBytes(configFile);

        ConfigStore store = new ConfigStore(configFile, tempDir);
        ConfigSnapshot snapshot = store.read();

        // Invalid port
        assertThrows(IllegalArgumentException.class, () ->
                store.save(snapshot, Map.of("peer.port", "70000")));

        // Prompt timeout >= offer response timeout
        assertThrows(IllegalArgumentException.class, () ->
                store.save(snapshot, Map.of(
                        "transfer.prompt.timeout.ms", "150000",
                        "transfer.offer.response.timeout.ms", "100000"
                )));

        // Invalid boolean for updates.checkOnStartup
        assertThrows(IllegalArgumentException.class, () ->
                store.save(snapshot, Map.of("updates.checkOnStartup", "maybe")));

        byte[] currentBytes = Files.readAllBytes(configFile);
        assertEquals(new String(originalBytes), new String(currentBytes));
    }

    @Test
    void testExternalEditAfterSnapshotCausesConflict(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("node.properties");
        Files.writeString(configFile, "peer.id=p1\npeer.name=Alice\n");

        ConfigStore store = new ConfigStore(configFile, tempDir);
        ConfigSnapshot snapshot = store.read();

        // External modification
        Files.writeString(configFile, "peer.id=p1\npeer.name=ExternalEdit\n");
        byte[] externalBytes = Files.readAllBytes(configFile);

        assertThrows(ConfigConflictException.class, () ->
                store.save(snapshot, Map.of("peer.name", "MyEdit")));

        assertEquals(new String(externalBytes), Files.readString(configFile));
    }

    @Test
    void testFileCreationRaceCausesConflict(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("absent.properties");
        ConfigStore store = new ConfigStore(configFile, tempDir);
        ConfigSnapshot snapshot = store.read();
        assertFalse(snapshot.exists());

        // File created externally before save
        Files.writeString(configFile, "peer.id=p1\npeer.name=Racer\n");

        assertThrows(ConfigConflictException.class, () ->
                store.save(snapshot, Map.of("peer.id", "p2", "peer.name", "Me")));
    }

    @Test
    void testFileDeletionRaceCausesConflict(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("to-delete.properties");
        Files.writeString(configFile, "peer.id=p1\npeer.name=Alice\n");

        ConfigStore store = new ConfigStore(configFile, tempDir);
        ConfigSnapshot snapshot = store.read();

        // Deleted externally
        Files.delete(configFile);

        assertThrows(ConfigConflictException.class, () ->
                store.save(snapshot, Map.of("peer.name", "Bob")));
    }

    @Test
    void testChangedSymlinkTargetCausesConflict(@TempDir Path tempDir) throws IOException {
        Path targetA = tempDir.resolve("targetA.properties");
        Path targetB = tempDir.resolve("targetB.properties");
        Files.writeString(targetA, "peer.id=p1\npeer.name=Alice\n");
        Files.writeString(targetB, "peer.id=p1\npeer.name=Alice\n");

        Path link = tempDir.resolve("link.properties");
        try {
            Files.createSymbolicLink(link, targetA);
        } catch (FileSystemException | UnsupportedOperationException ex) {
            // Symlinks might not be supported without admin/Developer mode on Windows
            return;
        }

        ConfigStore store = new ConfigStore(link, tempDir);
        ConfigSnapshot snapshot = store.read();

        // Change symlink to point to targetB
        Files.delete(link);
        Files.createSymbolicLink(link, targetB);

        assertThrows(ConfigConflictException.class, () ->
                store.save(snapshot, Map.of("peer.name", "Bob")));
    }

    @Test
    void testTwoSerializedWritersConflict(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("shared.properties");
        Files.writeString(configFile, "peer.id=p1\npeer.name=Initial\n");

        ConfigStore store1 = new ConfigStore(configFile, tempDir);
        ConfigStore store2 = new ConfigStore(configFile, tempDir);

        ConfigSnapshot snap1 = store1.read();
        ConfigSnapshot snap2 = store2.read();

        // Writer 1 succeeds
        ConfigSnapshot saved1 = store1.save(snap1, Map.of("peer.name", "Writer1Win"));
        assertEquals("Writer1Win", saved1.getProperty("peer.name"));

        // Writer 2 fails with conflict because snapshot is stale
        assertThrows(ConfigConflictException.class, () ->
                store2.save(snap2, Map.of("peer.name", "Writer2Loss")));

        // Winning file untouched by writer 2
        assertEquals("Writer1Win", store1.read().getProperty("peer.name"));
    }

    @Test
    void testFailureInjectionDuringSavePreservesOriginalFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("safe.properties");
        Files.writeString(configFile, "peer.id=p1\npeer.name=Initial\n");
        byte[] original = Files.readAllBytes(configFile);

        ConfigStore store = new ConfigStore(configFile, tempDir);
        ConfigSnapshot snapshot = store.read();

        store.fileSystemSeam = (tempFile, target) -> {
            throw new IOException("Simulated filesystem I/O error");
        };

        IOException ex = assertThrows(IOException.class, () ->
                store.save(snapshot, Map.of("peer.name", "ShouldFail")));
        assertTrue(ex.getMessage().contains("Simulated filesystem I/O error"));

        // Original file must be preserved
        assertEquals(new String(original), Files.readString(configFile));
    }

    @Test
    void testMissingParentDirectoryThrowsIOException(@TempDir Path tempDir) {
        Path nonExistentDir = tempDir.resolve("missing_folder");
        Path configFile = nonExistentDir.resolve("node.properties");

        ConfigStore store = new ConfigStore(configFile, tempDir);
        ConfigSnapshot snapshot = new ConfigSnapshot(configFile, configFile, false, null, Map.of());

        assertThrows(IOException.class, () ->
                store.save(snapshot, Map.of("peer.id", "p1", "peer.name", "Alice")));

        assertFalse(Files.exists(nonExistentDir));
    }
}
