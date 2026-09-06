package vn.edu.p2p.common.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FrameIO {
    private static final int MAGIC = 0x50325031; // ASCII-ish: P2P1
    private static final short VERSION = 1;
    private static final int MAX_HEADERS = 128;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

    private FrameIO() {
    }

    public static void write(OutputStream output, Frame frame) throws IOException {
        DataOutputStream out = output instanceof DataOutputStream dataOut
                ? dataOut
                : new DataOutputStream(output);

        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeInt(frame.type().code());
        out.writeInt(frame.headers().size());

        for (Map.Entry<String, String> entry : frame.headers().entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeUTF(entry.getValue());
        }

        out.writeInt(frame.payload().length);
        out.write(frame.payload());
        out.flush();
    }

    public static Frame read(InputStream input) throws IOException {
        DataInputStream in = input instanceof DataInputStream dataIn
                ? dataIn
                : new DataInputStream(input);

        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid protocol magic");
        }

        short version = in.readShort();
        if (version != VERSION) {
            throw new IOException("Unsupported protocol version: " + version);
        }

        MessageType type;
        try {
            type = MessageType.fromCode(in.readInt());
        } catch (IllegalArgumentException ex) {
            throw new IOException(ex.getMessage(), ex);
        }

        int headerCount = in.readInt();
        if (headerCount < 0 || headerCount > MAX_HEADERS) {
            throw new IOException("Invalid header count: " + headerCount);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < headerCount; i++) {
            headers.put(in.readUTF(), in.readUTF());
        }

        int payloadLength = in.readInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid payload length: " + payloadLength);
        }

        byte[] payload = new byte[payloadLength];
        in.readFully(payload);
        return new Frame(type, headers, payload);
    }
}
