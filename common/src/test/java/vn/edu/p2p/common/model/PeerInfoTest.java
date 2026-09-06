package vn.edu.p2p.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeerInfoTest {

    @Test
    void testValidUnicodePreserved() {
        PeerInfo p1 = assertDoesNotThrow(() -> new PeerInfo("peer-bob", "ボブ", "192.168.1.10", 6001));
        assertEquals("ボブ", p1.displayName());

        PeerInfo p2 = assertDoesNotThrow(() -> new PeerInfo("peer-nguyen", "Nguyễn Văn A", "127.0.0.1", 6002));
        assertEquals("Nguyễn Văn A", p2.displayName());
    }

    @Test
    void testRejectIsoControlCharacters() {
        // Embedded newline
        assertThrows(IllegalArgumentException.class, () -> new PeerInfo("peer\nid", "Bob", "127.0.0.1", 6001));
        // Embedded tab in display name
        assertThrows(IllegalArgumentException.class, () -> new PeerInfo("peer-id", "Bob\tSmith", "127.0.0.1", 6001));
        // NUL character
        assertThrows(IllegalArgumentException.class, () -> new PeerInfo("peer-id\0", "Bob", "127.0.0.1", 6001));
        // C1 control (\u0085 Next Line)
        assertThrows(IllegalArgumentException.class, () -> new PeerInfo("peer-id", "Bob\u0085", "127.0.0.1", 6001));
    }

    @Test
    void testPortBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> new PeerInfo("peer-id", "Bob", "127.0.0.1", 0));
        assertThrows(IllegalArgumentException.class, () -> new PeerInfo("peer-id", "Bob", "127.0.0.1", 65536));
        assertDoesNotThrow(() -> new PeerInfo("peer-id", "Bob", "127.0.0.1", 1));
        assertDoesNotThrow(() -> new PeerInfo("peer-id", "Bob", "127.0.0.1", 65535));
    }

    @Test
    void testLengthBoundaries() {
        String str255 = "a".repeat(255);
        String str256 = "a".repeat(256);
        assertDoesNotThrow(() -> new PeerInfo(str255, str255, str255, 6001));
        assertThrows(IllegalArgumentException.class, () -> new PeerInfo(str256, "Bob", "127.0.0.1", 6001));
        assertThrows(IllegalArgumentException.class, () -> new PeerInfo("peer-id", str256, "127.0.0.1", 6001));
        assertThrows(IllegalArgumentException.class, () -> new PeerInfo("peer-id", "Bob", str256, 6001));
    }
}
