package vn.edu.p2p.common.protocol;

import java.util.Arrays;

public enum MessageType {
    TRACKER_REGISTER(1),
    TRACKER_REGISTER_OK(2),
    TRACKER_LIST_PEERS(3),
    TRACKER_PEER_LIST(4),
    TRACKER_DISCONNECT(5),
    TRACKER_PUBLISH_FILES(6),
    TRACKER_PUBLISH_OK(7),
    TRACKER_SEARCH(8),
    TRACKER_SEARCH_RESULTS(9),

    FILE_OFFER(100),
    FILE_ACCEPT(101),
    FILE_REJECT(102),
    CHUNK_DATA(103),
    CHUNK_ACK(104),
    TRANSFER_COMPLETE(105),
    VERIFY_RESULT(106),
    FILE_REQUEST(107),

    ERROR(900);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MessageType fromCode(int code) {
        return Arrays.stream(values())
                .filter(type -> type.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown message type: " + code));
    }
}
