package vn.edu.p2p.common.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerListCodecTest {

    @Test
    void testGenericCodecAllows65RecordsUnder10000Ceiling() throws IOException {
        List<PeerInfo> peers = new ArrayList<>(65);
        for (int i = 0; i < 65; i++) {
            peers.add(new PeerInfo("peer-" + i, "Peer " + i, "10.0.0." + (i + 1), 6000 + i));
        }

        byte[] encoded = PeerListCodec.encode(peers);
        List<PeerInfo> decoded = PeerListCodec.decode(encoded);
        assertEquals(65, decoded.size());
    }

    @Test
    void testDuplicatePeerIdInPayloadRejected() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(out);
        dataOut.writeInt(2); // 2 peers
        // Peer 1
        dataOut.writeUTF("duplicate-id");
        dataOut.writeUTF("Alice");
        dataOut.writeUTF("127.0.0.1");
        dataOut.writeInt(6001);
        // Peer 2 with same ID
        dataOut.writeUTF("duplicate-id");
        dataOut.writeUTF("Bob");
        dataOut.writeUTF("127.0.0.1");
        dataOut.writeInt(6002);

        byte[] payload = out.toByteArray();
        IOException ex = assertThrows(IOException.class, () -> PeerListCodec.decode(payload));
        assertTrue(ex.getMessage().contains("Duplicate peerId in peer list: duplicate-id"));
    }

    @Test
    void testTrailingBytesInPayloadRejected() throws IOException {
        List<PeerInfo> peers = List.of(new PeerInfo("p1", "Alice", "127.0.0.1", 6001));
        byte[] encoded = PeerListCodec.encode(peers);

        byte[] withTrailing = new byte[encoded.length + 4];
        System.arraycopy(encoded, 0, withTrailing, 0, encoded.length);
        withTrailing[encoded.length] = 0x42; // Extra byte

        IOException ex = assertThrows(IOException.class, () -> PeerListCodec.decode(withTrailing));
        assertTrue(ex.getMessage().contains("Trailing bytes"));
    }
}
