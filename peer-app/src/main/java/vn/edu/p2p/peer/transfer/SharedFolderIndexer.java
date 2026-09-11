package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.FileRecord;
import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.util.FileNameUtil;
import vn.edu.p2p.peer.util.HashUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class SharedFolderIndexer {

    public record ScanResult(List<FileRecord> files, int pendingFiles, List<String> warnings) {
        public ScanResult {
            files = files == null ? List.of() : List.copyOf(files);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    @FunctionalInterface
    interface FileHasher {
        String sha256(Path path) throws IOException;
    }

    private record FileObservation(long size, FileTime mtime, Object fileKey, long observedNanoTime) {}

    private record CachedRecord(long size, FileTime mtime, Object fileKey, long generationAtHash, FileRecord record) {}

    private final AppConfig config;
    private final FileHasher hasher;
    private final LongSupplier nanoTime;

    private final ConcurrentHashMap<Path, AtomicLong> dirtyGenerations = new ConcurrentHashMap<>();
    private final AtomicLong globalDirty = new AtomicLong(0);

    private final Map<Path, CachedRecord> cache = new HashMap<>();
    private final Map<Path, FileObservation> pendingObservations = new HashMap<>();

    public SharedFolderIndexer(AppConfig config) {
        this(config, HashUtil::sha256, System::nanoTime);
    }

    SharedFolderIndexer(AppConfig config, FileHasher hasher, LongSupplier nanoTime) {
        this.config = Objects.requireNonNull(config, "config");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void markDirty(Path path) {
        if (path == null) {
            globalDirty.incrementAndGet();
            return;
        }
        Path sharedDir = config.sharedDir().toAbsolutePath().normalize();
        Path norm = path.isAbsolute() ? path.normalize() : sharedDir.resolve(path).normalize();
        Path parent = norm.getParent();
        if (parent != null && parent.equals(sharedDir)) {
            dirtyGenerations.computeIfAbsent(norm, p -> new AtomicLong(0)).incrementAndGet();
        } else if (norm.equals(sharedDir)) {
            globalDirty.incrementAndGet();
        }
    }

    public synchronized ScanResult scan(boolean forceRehash) throws IOException {
        Path sharedDir = config.sharedDir().toAbsolutePath().normalize();
        try {
            BasicFileAttributes dirAttrs = Files.readAttributes(sharedDir, BasicFileAttributes.class);
            if (!dirAttrs.isDirectory()) {
                cache.clear();
                pendingObservations.clear();
                dirtyGenerations.clear();
                return new ScanResult(List.of(), 0, List.of("Shared path is not a directory: " + sharedDir));
            }
        } catch (java.nio.file.NoSuchFileException ex) {
            cache.clear();
            pendingObservations.clear();
            dirtyGenerations.clear();
            return new ScanResult(List.of(), 0, List.of("Shared directory does not exist: " + sharedDir));
        } catch (IOException ex) {
            // Traversal or access denied failure: preserve cache and throw so caller treats it as failure
            throw ex;
        }

        List<FileRecord> records = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int pendingCount = 0;
        Set<Path> currentPaths = new HashSet<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sharedDir)) {
            for (Path path : stream) {
                Path normPath = path.toAbsolutePath().normalize();
                if (!Files.isRegularFile(normPath) || !Files.isReadable(normPath)) {
                    continue;
                }
                String rawName = normPath.getFileName().toString();
                if (rawName.startsWith(".p2p-") || rawName.endsWith(".part") || rawName.endsWith(".meta")) {
                    continue;
                }

                String safeName;
                try {
                    safeName = FileNameUtil.safeBaseName(rawName);
                } catch (Exception ex) {
                    warnings.add("Excluded file with invalid name: " + rawName);
                    continue;
                }

                currentPaths.add(normPath);

                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(normPath, BasicFileAttributes.class);
                } catch (IOException ex) {
                    warnings.add("Failed to read attributes for " + rawName + ": " + ex.getMessage());
                    cache.remove(normPath);
                    pendingObservations.remove(normPath);
                    continue;
                }

                CachedRecord cached = cache.get(normPath);
                long currentGen = dirtyGenerations.getOrDefault(normPath, new AtomicLong(0)).get() + globalDirty.get();

                boolean cacheValid = !forceRehash
                        && cached != null
                        && cached.generationAtHash == currentGen
                        && cached.size == attrs.size()
                        && cached.mtime.equals(attrs.lastModifiedTime())
                        && Objects.equals(cached.fileKey, attrs.fileKey());

                if (cacheValid) {
                    records.add(cached.record);
                    pendingObservations.remove(normPath);
                    continue;
                }

                // File is new, modified, or forceRehash requested
                long now = nanoTime.getAsLong();
                boolean isExistingCachedStable = cached != null
                        && cached.size == attrs.size()
                        && cached.mtime.equals(attrs.lastModifiedTime())
                        && Objects.equals(cached.fileKey, attrs.fileKey());

                if (forceRehash && isExistingCachedStable) {
                    // Stable cached file being rehashed on explicit rescan
                } else {
                    FileObservation prev = pendingObservations.get(normPath);
                    boolean attrsMatchPrev = prev != null
                            && prev.size == attrs.size()
                            && prev.mtime.equals(attrs.lastModifiedTime())
                            && Objects.equals(prev.fileKey, attrs.fileKey());

                    boolean stable = attrsMatchPrev && (now - prev.observedNanoTime() >= 1_000_000_000L);

                    if (!stable) {
                        if (!attrsMatchPrev) {
                            pendingObservations.put(normPath, new FileObservation(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey(), now));
                        }
                        cache.remove(normPath);
                        pendingCount++;
                        continue;
                    }
                }

                // Ready for hashing with pre and post stability verification
                long genBefore = dirtyGenerations.getOrDefault(normPath, new AtomicLong(0)).get() + globalDirty.get();
                BasicFileAttributes preAttrs;
                try {
                    preAttrs = Files.readAttributes(normPath, BasicFileAttributes.class);
                } catch (IOException ex) {
                    warnings.add("Failed to read attributes before hashing " + rawName + ": " + ex.getMessage());
                    cache.remove(normPath);
                    pendingObservations.put(normPath, new FileObservation(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey(), now));
                    pendingCount++;
                    continue;
                }

                String fileHash;
                try {
                    fileHash = hasher.sha256(normPath);
                } catch (IOException ex) {
                    warnings.add("Failed to hash " + rawName + ": " + ex.getMessage());
                    cache.remove(normPath);
                    pendingObservations.put(normPath, new FileObservation(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey(), now));
                    pendingCount++;
                    continue;
                }

                BasicFileAttributes postAttrs;
                try {
                    postAttrs = Files.readAttributes(normPath, BasicFileAttributes.class);
                } catch (IOException ex) {
                    warnings.add("Failed to read attributes after hashing " + rawName + ": " + ex.getMessage());
                    cache.remove(normPath);
                    pendingObservations.put(normPath, new FileObservation(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey(), now));
                    pendingCount++;
                    continue;
                }

                long genAfter = dirtyGenerations.getOrDefault(normPath, new AtomicLong(0)).get() + globalDirty.get();

                boolean unchangedDuringHash = genBefore == genAfter
                        && preAttrs.size() == postAttrs.size()
                        && preAttrs.lastModifiedTime().equals(postAttrs.lastModifiedTime())
                        && Objects.equals(preAttrs.fileKey(), postAttrs.fileKey());

                if (!unchangedDuringHash) {
                    warnings.add("File modified while hashing: " + rawName);
                    cache.remove(normPath);
                    pendingObservations.put(normPath, new FileObservation(postAttrs.size(), postAttrs.lastModifiedTime(), postAttrs.fileKey(), now));
                    pendingCount++;
                    continue;
                }

                int chunkSize = config.chunkSizeBytes();
                long fileSize = postAttrs.size();
                long totalChunks = fileSize == 0 ? 0 : (fileSize / chunkSize + (fileSize % chunkSize == 0 ? 0 : 1));

                FileRecord record = new FileRecord(fileHash, safeName, fileSize, chunkSize, totalChunks);
                cache.put(normPath, new CachedRecord(fileSize, postAttrs.lastModifiedTime(), postAttrs.fileKey(), genAfter, record));
                pendingObservations.remove(normPath);
                records.add(record);
            }
        }

        // Clean up deleted files from cache and state
        cache.keySet().removeIf(p -> !currentPaths.contains(p));
        pendingObservations.keySet().removeIf(p -> !currentPaths.contains(p));
        dirtyGenerations.keySet().removeIf(p -> !currentPaths.contains(p));

        records.sort(Comparator.comparing(FileRecord::fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(FileRecord::fileName)
                .thenComparing(FileRecord::fileId));

        return new ScanResult(List.copyOf(records), pendingCount, List.copyOf(warnings));
    }
}
