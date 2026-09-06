package vn.edu.p2p.peer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashUtilTest {

    @Test
    void testEmptyStringSha256() {
        String hash = HashUtil.sha256(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }

    @Test
    void testFileSha256(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        String hash = HashUtil.sha256(file);
        // SHA-256 of "hello world"
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash);
    }
}
