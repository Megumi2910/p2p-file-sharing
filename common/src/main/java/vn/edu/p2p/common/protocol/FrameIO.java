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
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

    private FrameIO() {
    }

    public static void write(OutputStream output, Frame frame) throws IOException {
        if (frame.headers().size() > MAX_HEADERS) {
            throw new IllegalArgumentException("Header count exceeds maximum: " + frame.headers().size());
        }
        for (Map.Entry<String, String> entry : frame.headers().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("Header key or value cannot be null");
            }
            if (utflen(entry.getKey()) > 65535 || utflen(entry.getValue()) > 65535) {
                throw new IllegalArgumentException("Header string length exceeds UTF-8 limit (65535 bytes)");
            }
        }
        if (frame.payload().length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload length exceeds maximum: " + frame.payload().length);
        }

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
        return read(input, MAX_PAYLOAD_BYTES);
    }

    public static Frame read(InputStream input, int maxPayloadBytes) throws IOException {
        if (maxPayloadBytes < 0 || maxPayloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid maxPayloadBytes: " + maxPayloadBytes);
        }

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
            String key = in.readUTF();
            String value = in.readUTF();
            if (headers.put(key, value) != null) {
                throw new IOException("Duplicate frame header key: " + key);
            }
        }

        int payloadLength = in.readInt();
        int effectiveMax = Math.min(maxPayloadBytes, MAX_PAYLOAD_BYTES);
        if (payloadLength < 0 || payloadLength > effectiveMax) {
            throw new IOException("Invalid payload length: " + payloadLength + " (allowed max: " + effectiveMax + ")");
        }

        byte[] payload = new byte[payloadLength];
        in.readFully(payload);
        return new Frame(type, headers, payload);
    }

    private static int utflen(String s) {
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x0001) && (c <= 0x007F)) {
                len++;
            } else if (c > 0x07FF) {
                len += 3;
            } else {
                len += 2;
            }
        }
        return len;
    }
}
