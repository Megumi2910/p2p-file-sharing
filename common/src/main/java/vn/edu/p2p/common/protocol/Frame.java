package vn.edu.p2p.common.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Frame(MessageType type, Map<String, String> headers, byte[] payload) {
    public Frame {
        headers = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        payload = payload == null ? new byte[0] : payload;
    }

    public Frame(MessageType type, Map<String, String> headers) {
        this(type, headers, new byte[0]);
    }

    public Frame(MessageType type) {
        this(type, Map.of(), new byte[0]);
    }

    public String requireHeader(String key) {
        String value = headers.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required header: " + key);
        }
        return value;
    }
}
