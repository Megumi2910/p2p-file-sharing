package vn.edu.p2p.tracker;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.model.PeerInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerRegistryTest {

    @Test
    void testDuplicateRegistrationFailsAndDoesNotOverwrite() {
        PeerRegistry registry = new PeerRegistry();
        PeerInfo alice = new PeerInfo("peer-1", "Alice", "10.0.0.1", 6001);
        PeerInfo impostor = new PeerInfo("peer-1", "Impostor", "10.0.0.2", 6002);

        assertTrue(registry.register(alice));
        assertFalse(registry.register(impostor));

        List<PeerInfo> list = registry.listExcept("other-id");
        assertEquals(1, list.size());
        assertEquals("Alice", list.get(0).displayName());
    }

    @Test
    void testConditionalUnregister() {
        PeerRegistry registry = new PeerRegistry();
        PeerInfo alice = new PeerInfo("peer-1", "Alice", "10.0.0.1", 6001);
        PeerInfo impostor = new PeerInfo("peer-1", "Impostor", "10.0.0.2", 6002);

        registry.register(alice);

        // Impostor tries to unregister alice's ID
        registry.unregister(impostor);
        assertEquals(1, registry.size(), "Impostor must not be able to unregister alice");

        // Alice unregisters herself
        registry.unregister(alice);
        assertEquals(0, registry.size());
    }

    @Test
    void testListExcludesRequesterAndSortsByDisplayName() {
        PeerRegistry registry = new PeerRegistry();
        registry.register(new PeerInfo("p1", "Charlie", "10.0.0.3", 6003));
        registry.register(new PeerInfo("p2", "Alice", "10.0.0.1", 6001));
        registry.register(new PeerInfo("p3", "Bob", "10.0.0.2", 6002));

        List<PeerInfo> list = registry.listExcept("p3"); // Exclude Bob
        assertEquals(2, list.size());
        assertEquals("Alice", list.get(0).displayName());
        assertEquals("Charlie", list.get(1).displayName());
    }
}
