package vn.edu.p2p.common.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PeerListCodec {
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
            if (count < 0 || count > 10_000) {
                throw new IOException("Invalid peer count: " + count);
            }

            List<PeerInfo> peers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                peers.add(new PeerInfo(in.readUTF(), in.readUTF(), in.readUTF(), in.readInt()));
            }
            return peers;
        }
    }
}
