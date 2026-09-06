package vn.edu.p2p.common.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PeerListCodec {
    private static final int MAX_PARSER_PEERS = 10_000;

    private PeerListCodec() {
    }

    public static byte[] encode(List<PeerInfo> peers) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(peers.size());
            for (PeerInfo peer : peers) {
                out.writeUTF(peer.peerId());
                out.writeUTF(peer.displayName());
                out.writeUTF(peer.host());
                out.writeInt(peer.port());
            }
        }
        return bytes.toByteArray();
    }

    public static List<PeerInfo> decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = in.readInt();
            if (count < 0 || count > MAX_PARSER_PEERS) {
                throw new IOException("Invalid peer count: " + count);
            }

            List<PeerInfo> peers = new ArrayList<>(count);
            Set<String> seenIds = new HashSet<>(count);
            for (int i = 0; i < count; i++) {
                PeerInfo peer;
                try {
                    peer = new PeerInfo(in.readUTF(), in.readUTF(), in.readUTF(), in.readInt());
                } catch (IllegalArgumentException ex) {
                    throw new IOException("Invalid peer record at index " + i + ": " + ex.getMessage(), ex);
                }
                if (!seenIds.add(peer.peerId())) {
                    throw new IOException("Duplicate peerId in peer list: " + peer.peerId());
                }
                peers.add(peer);
            }

            if (in.available() > 0) {
                throw new IOException("Trailing bytes in peer list payload");
            }

            return peers;
        }
    }
}
