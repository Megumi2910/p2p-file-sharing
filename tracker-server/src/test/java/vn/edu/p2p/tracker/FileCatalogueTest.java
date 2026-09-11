package vn.edu.p2p.tracker;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.SearchResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCatalogueTest {

    private static final String SHA_1 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SHA_2 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";

    @Test
    void testPublishSearchAndPruning() {
        FileCatalogue catalogue = new FileCatalogue();
        PeerRegistry registry = new PeerRegistry();

        PeerInfo alice = new PeerInfo("alice-id", "Alice", "10.0.0.1", 6001);
        PeerInfo bob = new PeerInfo("bob-id", "Bob", "10.0.0.2", 6002);
        registry.register(alice);
        registry.register(bob);

        FileRecord file1 = new FileRecord(SHA_1, "Algorithms_Book.pdf", 5000, 1024, 5);
        FileRecord file2 = new FileRecord(SHA_2, "Notes.txt", 100, 1024, 1);

        // Alice shares file1 and file2
        catalogue.publishFiles("alice-id", List.of(file1, file2));
        // Bob also shares file1
        catalogue.publishFiles("bob-id", List.of(file1));

        // Search for "algorithms"
        List<SearchResult> results = catalogue.search("algorithms", registry);
        assertEquals(1, results.size());
        assertEquals("Algorithms_Book.pdf", results.get(0).file().fileName());
        assertEquals(2, results.get(0).providers().size(), "Both Alice and Bob should provide file1");

        // Search by SHA
        List<SearchResult> shaResults = catalogue.search(SHA_2.substring(0, 10), registry);
        assertEquals(1, shaResults.size());
        assertEquals("Notes.txt", shaResults.get(0).file().fileName());
        assertEquals(1, shaResults.get(0).providers().size());

        // Alice disconnects
        catalogue.removePeer("alice-id");
        registry.unregister(alice);

        // Search for Notes.txt: Alice was only provider, so it should be pruned!
        List<SearchResult> notesResults = catalogue.search("Notes", registry);
        assertTrue(notesResults.isEmpty(), "Notes.txt should be pruned after sole provider disconnects");

        // Search for file1: Bob is still alive and sharing!
        List<SearchResult> remaining = catalogue.search("Algorithms", registry);
        assertEquals(1, remaining.size());
        assertEquals(1, remaining.get(0).providers().size());
        assertEquals("Bob", remaining.get(0).providers().get(0).displayName());
    }
    @Test
    void testRepublishReplacesSnapshot() {
        FileCatalogue catalogue = new FileCatalogue();
        PeerRegistry registry = new PeerRegistry();
        PeerInfo alice = new PeerInfo("alice-id", "Alice", "10.0.0.1", 6001);
        registry.register(alice);

        FileRecord file1 = new FileRecord(SHA_1, "file1.txt", 100, 1024, 1);
        FileRecord file2 = new FileRecord(SHA_2, "file2.txt", 200, 1024, 1);

        // First publish: file1 and file2
        catalogue.publishFiles("alice-id", List.of(file1, file2));
        assertEquals(2, catalogue.search("", registry).size());

        // Alice unshares file2 and republishes only file1
        catalogue.publishFiles("alice-id", List.of(file1));
        List<SearchResult> results = catalogue.search("", registry);
        assertEquals(1, results.size(), "Republishing should replace previous snapshot");
        assertEquals("file1.txt", results.get(0).file().fileName());

        // Search for file2 specifically returns empty
        assertTrue(catalogue.search("file2", registry).isEmpty());
    }

    @Test
    void testRenameSafeCatalogueAndAliases() {
        FileCatalogue catalogue = new FileCatalogue();
        PeerRegistry registry = new PeerRegistry();
        PeerInfo alice = new PeerInfo("alice-id", "Alice", "10.0.0.1", 6001);
        PeerInfo bob = new PeerInfo("bob-id", "Bob", "10.0.0.2", 6002);
        registry.register(alice);
        registry.register(bob);

        FileRecord oldAlice = new FileRecord(SHA_1, "old.txt", 100, 1024, 1);
        FileRecord newAlice = new FileRecord(SHA_1, "new.txt", 100, 1024, 1);

        // Alice publishes old.txt
        catalogue.publishFiles("alice-id", List.of(oldAlice));
        assertEquals(1, catalogue.search("old.txt", registry).size());

        // Alice renames to new.txt and republishes
        catalogue.publishFiles("alice-id", List.of(newAlice));
        assertTrue(catalogue.search("old.txt", registry).isEmpty(), "old.txt should not be found once Alice renames it");
        assertEquals(1, catalogue.search("new.txt", registry).size());
        assertEquals("new.txt", catalogue.search("new.txt", registry).get(0).file().fileName());

        // Now Bob publishes old.txt with the same hash
        FileRecord oldBob = new FileRecord(SHA_1, "old.txt", 100, 1024, 1);
        catalogue.publishFiles("bob-id", List.of(oldBob));

        // Searching for "old" matches Bob's alias; result has both Alice and Bob as providers
        List<SearchResult> oldResults = catalogue.search("old", registry);
        assertEquals(1, oldResults.size());
        assertEquals("old.txt", oldResults.get(0).file().fileName());
        assertEquals(2, oldResults.get(0).providers().size(), "Both Alice and Bob provide the hash");

        // Searching for "new" matches Alice's alias; result also has both Alice and Bob as providers
        List<SearchResult> newResults = catalogue.search("new", registry);
        assertEquals(1, newResults.size());
        assertEquals("new.txt", newResults.get(0).file().fileName());
        assertEquals(2, newResults.get(0).providers().size());

        // Empty query matches all: displayed name is "new.txt" ('n' < 'o')
        List<SearchResult> allResults = catalogue.search("", registry);
        assertEquals(1, allResults.size());
        assertEquals("new.txt", allResults.get(0).file().fileName());

        // Bob disconnects/unpublishes
        catalogue.removePeer("bob-id");
        registry.unregister(bob);

        assertTrue(catalogue.search("old", registry).isEmpty(), "old alias disappears when Bob is removed");
        assertEquals(1, catalogue.search("new", registry).size());
        assertEquals(1, catalogue.search("new", registry).get(0).providers().size());
    }

    @Test
    void testDuplicateContentAliasesWithinSinglePeer() {
        FileCatalogue catalogue = new FileCatalogue();
        PeerRegistry registry = new PeerRegistry();
        PeerInfo alice = new PeerInfo("alice-id", "Alice", "10.0.0.1", 6001);
        registry.register(alice);

        FileRecord alias1 = new FileRecord(SHA_1, "alias1.txt", 100, 1024, 1);
        FileRecord alias2 = new FileRecord(SHA_1, "alias2.txt", 100, 1024, 1);

        // Alice publishes two aliases for the exact same hash
        catalogue.publishFiles("alice-id", List.of(alias1, alias2));
        assertEquals(1, catalogue.size(), "size() must count distinct content hashes");

        // Search for alias1 returns 1 result with Alice once as provider
        List<SearchResult> res1 = catalogue.search("alias1", registry);
        assertEquals(1, res1.size());
        assertEquals("alias1.txt", res1.get(0).file().fileName());
        assertEquals(1, res1.get(0).providers().size());

        // Search for alias2 returns 1 result with Alice once as provider
        List<SearchResult> res2 = catalogue.search("alias2", registry);
        assertEquals(1, res2.size());
        assertEquals("alias2.txt", res2.get(0).file().fileName());
        assertEquals(1, res2.get(0).providers().size());
    }

    @Test
    void testNullHandlingAndEmptyInputs() {
        FileCatalogue catalogue = new FileCatalogue();
        PeerRegistry registry = new PeerRegistry();
        catalogue.publishFiles(null, null);
        catalogue.removePeer(null);
        assertEquals(0, catalogue.size());
        assertTrue(catalogue.search("query", null).isEmpty());
        assertTrue(catalogue.search("query", registry).isEmpty());
    }
}
