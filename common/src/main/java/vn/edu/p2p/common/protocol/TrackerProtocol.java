package vn.edu.p2p.common.protocol;

public final class TrackerProtocol {
    public static final int MAX_ACTIVE_PEERS = 64;
    public static final int MAX_PEER_LIST_PAYLOAD_BYTES = 1024 * 1024; // 1 MiB

    private TrackerProtocol() {
    }
}
