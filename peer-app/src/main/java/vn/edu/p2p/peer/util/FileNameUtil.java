package vn.edu.p2p.peer.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileNameUtil {
    private FileNameUtil() {
    }

    public static String safeBaseName(String untrustedName) {
        String baseName = Path.of(untrustedName).getFileName().toString();
        return baseName.replaceAll("[\\r\\n\\u0000]", "_");
    }

    public static Path uniqueDestination(Path directory, String fileName) throws IOException {
        Files.createDirectories(directory);
        String safe = safeBaseName(fileName);
        Path candidate = directory.resolve(safe);
        if (!Files.exists(candidate) && !Files.exists(Path.of(candidate + ".part"))) {
            return candidate;
        }

        String name = safe;
        String extension = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            name = safe.substring(0, dot);
            extension = safe.substring(dot);
        }

        for (int i = 1; ; i++) {
            candidate = directory.resolve(name + " (" + i + ")" + extension);
            if (!Files.exists(candidate) && !Files.exists(Path.of(candidate + ".part"))) {
                return candidate;
            }
        }
    }
}
