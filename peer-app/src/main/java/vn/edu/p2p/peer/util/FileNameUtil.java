package vn.edu.p2p.peer.util;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class FileNameUtil {
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private FileNameUtil() {
    }

    public static String safeBaseName(String untrustedName) {
        if (untrustedName == null || untrustedName.isBlank()) {
            throw new IllegalArgumentException("untrustedName cannot be blank");
        }

        // Handle both slash styles
        String normalizedSlashes = untrustedName.replace('\\', '/');
        int lastSlash = normalizedSlashes.lastIndexOf('/');
        String base = lastSlash >= 0 ? normalizedSlashes.substring(lastSlash + 1) : normalizedSlashes;

        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw new IllegalArgumentException("Invalid basename: " + untrustedName);
        }

        // Replace Windows forbidden characters and ISO controls with underscore
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '<' || c == '>' || c == ':' || c == '"' || c == '/' || c == '\\'
                    || c == '|' || c == '?' || c == '*' || Character.isISOControl(c)) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }

        String sanitized = sb.toString();

        // Strip trailing spaces and dots
        int end = sanitized.length();
        while (end > 0 && (sanitized.charAt(end - 1) == ' ' || sanitized.charAt(end - 1) == '.')) {
            end--;
        }
        sanitized = sanitized.substring(0, end);

        // Revalidate resulting basename: must not be empty or . or ..
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new IllegalArgumentException("Invalid basename after normalization: " + untrustedName);
        }

        // Check Windows device names (including with extensions)
        int dot = sanitized.indexOf('.');
        String stem = dot >= 0 ? sanitized.substring(0, dot) : sanitized;
        if (WINDOWS_DEVICE_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
            sanitized = "_" + sanitized;
        }

        return sanitized;
    }

    public static Path createPartial(Path directory) throws IOException {
        Files.createDirectories(directory);
        return Files.createTempFile(directory, ".p2p-", ".part");
    }

    public static Path publishVerified(Path part, Path directory, String fileName) throws IOException {
        Files.createDirectories(directory);
        String safe = safeBaseName(fileName);

        String name = safe;
        String extension = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            name = safe.substring(0, dot);
            extension = safe.substring(dot);
        }

        for (int i = 0; ; i++) {
            String candidateName = (i == 0) ? safe : (name + " (" + i + ")" + extension);
            Path candidate = directory.resolve(candidateName);
            try {
                Files.createLink(candidate, part);
                return candidate;
            } catch (FileAlreadyExistsException ex) {
                // Suffix collision, try next number
            } catch (UnsupportedOperationException | SecurityException ex) {
                throw new IOException("Hard-link publication unsupported or denied on filesystem for " + candidate
                        + " (retaining verified partial at " + part + ")", ex);
            }
        }
    }
}
