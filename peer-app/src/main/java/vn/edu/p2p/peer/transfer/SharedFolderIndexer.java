package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.util.FileNameUtil;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SharedFolderIndexer {
    private SharedFolderIndexer() {
    }

    public static List<FileRecord> index(AppConfig config) throws IOException {
        Path sharedDir = config.sharedDir();
        Files.createDirectories(sharedDir);

        List<FileRecord> records = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sharedDir)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                    continue;
                }
                String rawName = path.getFileName().toString();
                if (rawName.startsWith(".p2p-") || rawName.endsWith(".part") || rawName.endsWith(".meta")) {
                    continue;
                }

                String safeName;
                try {
                    safeName = FileNameUtil.safeBaseName(rawName);
                } catch (Exception ex) {
                    continue;
                }

                long fileSize = Files.size(path);
                String fileHash = HashUtil.sha256(path);
                int chunkSize = config.chunkSizeBytes();
                long totalChunks = fileSize == 0 ? 0 : (fileSize / chunkSize + (fileSize % chunkSize == 0 ? 0 : 1));

                records.add(new FileRecord(fileHash, safeName, fileSize, chunkSize, totalChunks));
            }
        }
        return records;
    }
}
