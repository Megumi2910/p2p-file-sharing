package vn.edu.p2p.peer.config;

import java.io.IOException;

public class ConfigConflictException extends IOException {
    public ConfigConflictException(String message) {
        super(message);
    }

    public ConfigConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
