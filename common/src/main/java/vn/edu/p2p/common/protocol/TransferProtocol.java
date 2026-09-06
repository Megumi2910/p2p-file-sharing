package vn.edu.p2p.common.protocol;

public final class TransferProtocol {
    public static final int DEFAULT_CHUNK_BYTES = 1024 * 1024;      // 1 MiB
    public static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;        // 8 MiB

    private TransferProtocol() {
    }
}
