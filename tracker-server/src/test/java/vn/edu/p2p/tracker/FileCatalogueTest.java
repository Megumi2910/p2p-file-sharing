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
}
