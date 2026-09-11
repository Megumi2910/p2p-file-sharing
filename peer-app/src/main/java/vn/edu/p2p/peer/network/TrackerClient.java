package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.PeerListCodec;
import vn.edu.p2p.common.protocol.Frame;
import vn.edu.p2p.common.protocol.FrameIO;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.TrackerProtocol;
import vn.edu.p2p.peer.config.AppConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

public final class TrackerClient implements AutoCloseable {
    private final AppConfig config;
    private final Object requestLock = new Object();
    private final Object lifecycleLock = new Object();
    private volatile Socket socket;
    private volatile boolean closed = false;

    public TrackerClient(AppConfig config) {
        this.config = config;
    }

    public String connectAndRegister() throws IOException {
        synchronized (requestLock) {
            Socket oldSocket;
            synchronized (lifecycleLock) {
                if (closed) {
                    throw new IOException("TrackerClient is closed");
                }
                oldSocket = this.socket;
                this.socket = null;
            }
            if (oldSocket != null) {
                try {
                    oldSocket.close();
                } catch (IOException ignored) {
                }
            }

            Socket newSocket = new Socket();
            synchronized (lifecycleLock) {
                if (closed) {
                    try {
                        newSocket.close();
                    } catch (IOException ignored) {
                    }
                    throw new IOException("TrackerClient is closed");
                }
                this.socket = newSocket;
            }

            try {
                newSocket.connect(new InetSocketAddress(config.trackerHost(), config.trackerPort()), 5_000);
                synchronized (lifecycleLock) {
                    if (closed) {
                        try {
                            newSocket.close();
                        } catch (IOException ignored) {
                        }
                        if (this.socket == newSocket) {
                            this.socket = null;
                        }
                        throw new IOException("TrackerClient was closed during connect");
                    }
                }
                newSocket.setSoTimeout(config.trackerReadTimeoutMillis());

                FrameIO.write(newSocket.getOutputStream(), new Frame(
                        MessageType.TRACKER_REGISTER,
                        Map.of(
                                "peerId", config.peerId(),
                                "displayName", config.displayName(),
                                "peerPort", Integer.toString(config.peerPort())
                        )
                ));

                Frame response = FrameIO.read(newSocket.getInputStream(), 0);
                if (response.type() == MessageType.ERROR) {
                    String msg = response.headers().getOrDefault("message", "Registration rejected by tracker");
                    throw new IOException("Tracker registration failed: " + msg);
                }
                if (response.type() != MessageType.TRACKER_REGISTER_OK) {
                    throw new IOException("Tracker registration failed with unexpected message: " + response.type());
                }
                return response.requireHeader("host");
            } catch (Exception ex) {
                synchronized (lifecycleLock) {
                    try {
                        newSocket.close();
                    } catch (IOException ignored) {
                    }
                    if (this.socket == newSocket) {
                        this.socket = null;
                    }
                }
                if (ex instanceof IOException ioEx) {
                    throw ioEx;
                }
                throw new IOException("Tracker registration failed: " + ex.getMessage(), ex);
            }
        }
    }

    public List<PeerInfo> listPeers() throws IOException {
        synchronized (requestLock) {
            Socket currentSocket = this.socket;
            if (closed || currentSocket == null || currentSocket.isClosed()) {
                throw new IOException("Not connected to tracker");
            }

            try {
                currentSocket.setSoTimeout(config.trackerReadTimeoutMillis());
                FrameIO.write(currentSocket.getOutputStream(), new Frame(MessageType.TRACKER_LIST_PEERS));
                Frame response = FrameIO.read(currentSocket.getInputStream(), TrackerProtocol.MAX_PEER_LIST_PAYLOAD_BYTES);
                if (response.type() == MessageType.ERROR) {
                    throw new IOException(response.requireHeader("message"));
                }
                if (response.type() != MessageType.TRACKER_PEER_LIST) {
                    throw new IOException("Unexpected tracker response: " + response.type());
                }
                int declaredCount = Integer.parseInt(response.requireHeader("count"));
                if (declaredCount < 0 || declaredCount > TrackerProtocol.MAX_ACTIVE_PEERS) {
                    throw new IOException("Tracker declared count outside protocol bounds: " + declaredCount);
                }
                List<PeerInfo> peers = PeerListCodec.decode(response.payload());
                if (peers.size() != declaredCount) {
                    throw new IOException("Mismatched peer count: header=" + declaredCount + ", decoded=" + peers.size());
                }
                return peers;
            } catch (Exception ex) {
                synchronized (lifecycleLock) {
                    try {
                        currentSocket.close();
                    } catch (IOException ignored) {
                    }
                    if (this.socket == currentSocket) {
                        this.socket = null;
                    }
                }
                if (ex instanceof IOException ioEx) {
                    throw ioEx;
                }
                throw new IOException("Failed to list peers: " + ex.getMessage(), ex);
            }
        }
    }
    public void publishSharedFiles(List<vn.edu.p2p.common.model.FileRecord> files) throws IOException {
        synchronized (requestLock) {
            Socket currentSocket = this.socket;
            if (closed || currentSocket == null || currentSocket.isClosed()) {
                throw new IOException("Not connected to tracker");
            }
            try {
                currentSocket.setSoTimeout(config.trackerReadTimeoutMillis());
                byte[] payload = vn.edu.p2p.common.model.CatalogueCodec.encodeFiles(files);
                FrameIO.write(currentSocket.getOutputStream(), new Frame(
                        MessageType.TRACKER_PUBLISH_FILES,
                        Map.of("count", Integer.toString(files.size())),
                        payload
                ));
                Frame response = FrameIO.read(currentSocket.getInputStream(), 0);
                if (response.type() == MessageType.ERROR) {
                    throw new IOException(response.requireHeader("message"));
                }
                if (response.type() != MessageType.TRACKER_PUBLISH_OK) {
                    throw new IOException("Unexpected tracker response: " + response.type());
                }
            } catch (Exception ex) {
                synchronized (lifecycleLock) {
                    try {
                        currentSocket.close();
                    } catch (IOException ignored) {
                    }
                    if (this.socket == currentSocket) {
                        this.socket = null;
                    }
                }
                if (ex instanceof IOException ioEx) {
                    throw ioEx;
                }
                throw new IOException("Failed to publish shared files: " + ex.getMessage(), ex);
            }
        }
    }

    public List<vn.edu.p2p.common.model.SearchResult> searchFiles(String query) throws IOException {
        synchronized (requestLock) {
            Socket currentSocket = this.socket;
            if (closed || currentSocket == null || currentSocket.isClosed()) {
                throw new IOException("Not connected to tracker");
            }
            try {
                currentSocket.setSoTimeout(config.trackerReadTimeoutMillis());
                FrameIO.write(currentSocket.getOutputStream(), new Frame(
                        MessageType.TRACKER_SEARCH,
                        Map.of("query", query == null ? "" : query)
                ));
                Frame response = FrameIO.read(currentSocket.getInputStream(), TrackerProtocol.MAX_PEER_LIST_PAYLOAD_BYTES);
                if (response.type() == MessageType.ERROR) {
                    throw new IOException(response.requireHeader("message"));
                }
                if (response.type() != MessageType.TRACKER_SEARCH_RESULTS) {
                    throw new IOException("Unexpected tracker response: " + response.type());
                }
                int count = Integer.parseInt(response.requireHeader("count"));
                List<vn.edu.p2p.common.model.SearchResult> results = vn.edu.p2p.common.model.CatalogueCodec.decodeSearchResults(response.payload());
                if (results.size() != count) {
                    throw new IOException("Mismatched search results count: header=" + count + ", decoded=" + results.size());
                }
                return results;
            } catch (Exception ex) {
                synchronized (lifecycleLock) {
                    try {
                        currentSocket.close();
                    } catch (IOException ignored) {
                    }
                    if (this.socket == currentSocket) {
                        this.socket = null;
                    }
                }
                if (ex instanceof IOException ioEx) {
                    throw ioEx;
                }
                throw new IOException("Failed to search files: " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void close() throws IOException {
        Socket s;
        synchronized (lifecycleLock) {
            closed = true;
            s = this.socket;
            this.socket = null;
        }
        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
