package vn.edu.p2p.common.model;

public record PeerInfo(String peerId, String displayName, String host, int port) {
    public PeerInfo {
        if (peerId == null || peerId.isBlank()) {
            throw new IllegalArgumentException("peerId cannot be blank");
        }
        if (peerId.length() > 255) {
            throw new IllegalArgumentException("peerId exceeds 255 characters");
        }
        for (int i = 0; i < peerId.length(); i++) {
            if (Character.isISOControl(peerId.charAt(i))) {
                throw new IllegalArgumentException("peerId cannot contain ISO control characters");
            }
        }

        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName cannot be blank");
        }
        if (displayName.length() > 255) {
            throw new IllegalArgumentException("displayName exceeds 255 characters");
        }
        for (int i = 0; i < displayName.length(); i++) {
            if (Character.isISOControl(displayName.charAt(i))) {
                throw new IllegalArgumentException("displayName cannot contain ISO control characters");
            }
        }

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (host.length() > 255) {
            throw new IllegalArgumentException("host exceeds 255 characters");
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535: " + port);
        }
    }

    @Override
    public String toString() {
        return displayName + " (" + host + ":" + port + ")";
    }
}
