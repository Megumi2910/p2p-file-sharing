package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.PeerListCodec;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.peer.config.AppConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

public final class TrackerClient implements AutoCloseable {
    private final AppConfig config;
    private Socket socket;

    public TrackerClient(AppConfig config) {
        this.config = config;
    }

    public synchronized String connectAndRegister() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(config.trackerHost(), config.trackerPort()), 5_000);

        FrameIO.write(socket.getOutputStream(), new Frame(
                MessageType.TRACKER_REGISTER,
                Map.of(
                        "peerId", config.peerId(),
                        "displayName", config.displayName(),
                        "peerPort", Integer.toString(config.peerPort())
                )
        ));

        Frame response = FrameIO.read(socket.getInputStream());
        if (response.type() != MessageType.TRACKER_REGISTER_OK) {
            throw new IOException("Tracker registration failed: " + response.type());
        }
        return response.requireHeader("host");
    }

    public synchronized List<PeerInfo> listPeers() throws IOException {
        ensureConnected();
        FrameIO.write(socket.getOutputStream(), new Frame(MessageType.TRACKER_LIST_PEERS));
        Frame response = FrameIO.read(socket.getInputStream());
        if (response.type() == MessageType.ERROR) {
            throw new IOException(response.requireHeader("message"));
        }
        if (response.type() != MessageType.TRACKER_PEER_LIST) {
            throw new IOException("Unexpected tracker response: " + response.type());
        }
        return PeerListCodec.decode(response.payload());
    }

    private void ensureConnected() throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("Not connected to tracker");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (socket == null) {
            return;
        }
        if (!socket.isClosed()) {
            try {
                FrameIO.write(socket.getOutputStream(), new Frame(MessageType.TRACKER_DISCONNECT));
            } catch (IOException ignored) {
            }
            socket.close();
        }
    }
}
