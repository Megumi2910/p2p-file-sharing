package vn.edu.p2p.common.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameIOBoundaryTest {

    @Test
    void testDuplicateHeaderKeyRejected() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(out);
        dataOut.writeInt(0x50325031); // Magic
        dataOut.writeShort(1);        // Version
        dataOut.writeInt(MessageType.FILE_OFFER.code());
        dataOut.writeInt(2);          // 2 headers
        dataOut.writeUTF("key1");
        dataOut.writeUTF("val1");
        dataOut.writeUTF("key1");     // Duplicate key
        dataOut.writeUTF("val2");
        dataOut.writeInt(0);          // Payload length

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        IOException ex = assertThrows(IOException.class, () -> FrameIO.read(in));
        assertTrue(ex.getMessage().contains("Duplicate frame header key: key1"));
    }

    @Test
    void testPayloadExceedingCallerLimitRejectedWithoutOversizedAllocation() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(out);
        dataOut.writeInt(0x50325031); // Magic
        dataOut.writeShort(1);        // Version
        dataOut.writeInt(MessageType.CHUNK_DATA.code());
        dataOut.writeInt(0);          // 0 headers
        dataOut.writeInt(1024 * 1024); // Declares 1 MiB payload length
        // We write only 10 dummy bytes so it would fail if it tried to read 1 MiB
        dataOut.write(new byte[10]);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        // Caller specifies cap of 512 bytes: must reject immediately on declared length without reading payload
        IOException ex = assertThrows(IOException.class, () -> FrameIO.read(in, 512));
        assertTrue(ex.getMessage().contains("allowed max: 512"));
    }

    @Test
    void testZeroPayloadCapEnforced() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(out);
        dataOut.writeInt(0x50325031); // Magic
        dataOut.writeShort(1);        // Version
        dataOut.writeInt(MessageType.TRACKER_REGISTER_OK.code());
        dataOut.writeInt(0);
        dataOut.writeInt(1);          // Declares 1 byte payload
        dataOut.writeByte(0xFF);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        IOException ex = assertThrows(IOException.class, () -> FrameIO.read(in, 0));
        assertTrue(ex.getMessage().contains("allowed max: 0"));
    }

    @Test
    void testNegativeOrExcessiveCallerCapRejected() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> FrameIO.read(in, -1));
        assertThrows(IllegalArgumentException.class, () -> FrameIO.read(in, FrameIO.MAX_PAYLOAD_BYTES + 1));
    }

    @Test
    void testWriteRejectsMoreThan128HeadersBeforeEmitting() {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < 129; i++) {
            headers.put("key" + i, "val" + i);
        }
        Frame frame = new Frame(MessageType.FILE_OFFER, headers);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> FrameIO.write(out, frame));
        assertEquals(0, out.size(), "No bytes should be emitted when preflight checks fail");
    }
}
