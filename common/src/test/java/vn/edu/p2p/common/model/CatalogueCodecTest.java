package vn.edu.p2p.common.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogueCodecTest {

    private static final String SHA_A = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SHA_B = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";

    @Test
    void testEncodeDecodeFilesRoundtrip() throws IOException {
        List<FileRecord> files = List.of(
                new FileRecord(SHA_A, "empty.txt", 0, 1024, 0),
                new FileRecord(SHA_B, "hello.txt", 11, 1024, 1)
        );

        byte[] payload = CatalogueCodec.encodeFiles(files);
        List<FileRecord> decoded = CatalogueCodec.decodeFiles(payload);

        assertEquals(2, decoded.size());
        assertEquals("empty.txt", decoded.get(0).fileName());
        assertEquals(SHA_A, decoded.get(0).fileId());
        assertEquals("hello.txt", decoded.get(1).fileName());
        assertEquals(SHA_B, decoded.get(1).fileId());
    }

    @Test
    void testEncodeDecodeSearchResultsRoundtrip() throws IOException {
        FileRecord file = new FileRecord(SHA_A, "distributed.iso", 2048, 1024, 2);
        List<PeerInfo> providers = List.of(
                new PeerInfo("p1", "Alice", "10.0.0.1", 6001),
                new PeerInfo("p2", "Bob", "10.0.0.2", 6002)
        );
        SearchResult result = new SearchResult(file, providers);

        byte[] payload = CatalogueCodec.encodeSearchResults(List.of(result));
        List<SearchResult> decoded = CatalogueCodec.decodeSearchResults(payload);

        assertEquals(1, decoded.size());
        assertEquals("distributed.iso", decoded.get(0).file().fileName());
        assertEquals(2, decoded.get(0).providers().size());
        assertEquals("Alice", decoded.get(0).providers().get(0).displayName());
        assertEquals("Bob", decoded.get(0).providers().get(1).displayName());
    }
}
