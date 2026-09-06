package vn.edu.p2p.common.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameIOTest {

    @Test
    void testValidRoundtripWithHeadersAndPayload() throws IOException {
        byte[] payload = "Hello P2P World".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of(
                "transferId", "test-123",
                "fileName", "sample.txt"
        );
        Frame original = new Frame(MessageType.FILE_OFFER, headers, payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameIO.write(out, original);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Frame read = FrameIO.read(in);

        assertEquals(MessageType.FILE_OFFER, read.type());
        assertEquals("test-123", read.requireHeader("transferId"));
        assertEquals("sample.txt", read.requireHeader("fileName"));
        assertArrayEquals(payload, read.payload());
    }

    @Test
    void testBadMagic() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(out);
        try {
            dataOut.writeInt(0x12345678); // Bad magic
            dataOut.writeShort(1);
            dataOut.writeInt(10);
            dataOut.writeInt(0);
            dataOut.writeInt(0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        IOException ex = assertThrows(IOException.class, () -> FrameIO.read(in));
        assertTrue(ex.getMessage().contains("Invalid protocol magic"));
    }

    @Test
    void testBadVersion() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(out);
        dataOut.writeInt(0x50325031); // Valid magic
        dataOut.writeShort(99);       // Bad version
        dataOut.writeInt(10);
        dataOut.writeInt(0);
        dataOut.writeInt(0);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        IOException ex = assertThrows(IOException.class, () -> FrameIO.read(in));
        assertTrue(ex.getMessage().contains("Unsupported protocol version"));
    }

    @Test
    void testUnknownMessageType() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(out);
        dataOut.writeInt(0x50325031); // Valid magic
        dataOut.writeShort(1);        // Valid version
        dataOut.writeInt(9999);       // Unknown message code
        dataOut.writeInt(0);
        dataOut.writeInt(0);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertThrows(IOException.class, () -> FrameIO.read(in));
    }

    @Test
    void testTruncatedStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Frame frame = new Frame(MessageType.FILE_OFFER, Map.of("k", "v"), new byte[]{1, 2, 3, 4});
        FrameIO.write(out, frame);

        byte[] allBytes = out.toByteArray();
        // Truncate halfway through
        byte[] truncated = new byte[allBytes.length / 2];
        System.arraycopy(allBytes, 0, truncated, 0, truncated.length);

        ByteArrayInputStream in = new ByteArrayInputStream(truncated);
        assertThrows(IOException.class, () -> FrameIO.read(in));
    }
}
