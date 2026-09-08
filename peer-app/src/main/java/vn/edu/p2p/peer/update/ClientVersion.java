package vn.edu.p2p.peer.update;

import java.util.Objects;

public record ClientVersion(int major, int minor, int patch) implements Comparable<ClientVersion> {

    public ClientVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components must be non-negative: " + major + "." + minor + "." + patch);
        }
    }

    public static ClientVersion parse(String raw) {
        Objects.requireNonNull(raw, "Version string cannot be null");
        String text = raw.trim();

        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1);
        }

        String[] parts = text.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Version must have exactly 3 components (MAJOR.MINOR.PATCH): " + raw);
        }

        int major = parseComponent(parts[0], "major", raw);
        int minor = parseComponent(parts[1], "minor", raw);
        int patch = parseComponent(parts[2], "patch", raw);

        return new ClientVersion(major, minor, patch);
    }

    private static int parseComponent(String part, String name, String full) {
        if (part == null || part.isEmpty()) {
            throw new IllegalArgumentException("Version " + name + " component is empty in: " + full);
        }
        if (part.length() > 1 && part.startsWith("0")) {
            throw new IllegalArgumentException("Leading zeros not allowed in version component '" + part + "' in: " + full);
        }
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Non-digit character in version component '" + part + "' in: " + full);
            }
        }
        try {
            long val = Long.parseLong(part);
            if (val > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Version " + name + " exceeds Integer.MAX_VALUE: " + part);
            }
            return (int) val;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Version " + name + " cannot be parsed: " + part, ex);
        }
    }

    @Override
    public int compareTo(ClientVersion other) {
        Objects.requireNonNull(other, "other version cannot be null");
        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(this.minor, other.minor);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(this.patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    public String toTag() {
        return "v" + toString();
    }
}
