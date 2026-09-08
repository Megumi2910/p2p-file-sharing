package vn.edu.p2p.peer.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;

public final class UpdateLocks {

    public static final String RUNTIME_LOCK = "runtime.lock";
    public static final String OPERATION_LOCK = "operation.lock";

    public static final Duration DEFAULT_EXCLUSIVE_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration DEFAULT_STARTUP_WAIT = Duration.ofSeconds(2);

    private UpdateLocks() {}

    private static void checkUpdateDir(Path updateDir) throws IOException {
        Objects.requireNonNull(updateDir, "updateDir cannot be null");
        if (!Files.isDirectory(updateDir)) {
            throw new IOException("Update directory does not exist or is not a directory: " + updateDir);
        }
        if (Files.isSymbolicLink(updateDir)) {
            throw new SecurityException("Update directory cannot be a symbolic link: " + updateDir);
        }
    }

    public static FileLockHandle acquireSharedRuntimeLock(Path updateDir) throws IOException {
        checkUpdateDir(updateDir);
        Path lockPath = updateDir.resolve(RUNTIME_LOCK);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock(0, Long.MAX_VALUE, true);
            return new FileLockHandle(channel, lock);
        } catch (Exception ex) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    public static FileLockHandle acquireExclusiveRuntimeLock(Path updateDir, Duration timeout)
            throws IOException, InterruptedException {
        checkUpdateDir(updateDir);
        Path lockPath = updateDir.resolve(RUNTIME_LOCK);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                FileLock lock = null;
                try {
                    lock = channel.tryLock(0, Long.MAX_VALUE, false);
                } catch (OverlappingFileLockException ex) {
                    lock = null;
                }
                if (lock != null) {
                    return new FileLockHandle(channel, lock);
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException("Timed out waiting for exclusive runtime lock (" + timeout.toSeconds() + "s)");
                }
                Thread.sleep(100);
            }
        } catch (Exception ex) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    public static FileLockHandle tryAcquireOperationLock(Path updateDir) throws IOException {
        checkUpdateDir(updateDir);
        Path lockPath = updateDir.resolve(OPERATION_LOCK);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = null;
            try {
                lock = channel.tryLock(0, Long.MAX_VALUE, false);
            } catch (OverlappingFileLockException ex) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                return null;
            }
            return new FileLockHandle(channel, lock);
        } catch (Exception ex) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    public static FileLockHandle acquireOperationLock(Path updateDir, Duration timeout)
            throws IOException, InterruptedException {
        checkUpdateDir(updateDir);
        Path lockPath = updateDir.resolve(OPERATION_LOCK);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                FileLock lock = null;
                try {
                    lock = channel.tryLock(0, Long.MAX_VALUE, false);
                } catch (OverlappingFileLockException ex) {
                    lock = null;
                }
                if (lock != null) {
                    return new FileLockHandle(channel, lock);
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException("Timed out waiting for operation lock (" + timeout.toSeconds() + "s)");
                }
                Thread.sleep(100);
            }
        } catch (Exception ex) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            throw ex;
        }
    }

    public static boolean isOperationActive(Path updateDir) {
        if (updateDir == null || !Files.isDirectory(updateDir)) {
            return false;
        }
        Path lockPath = updateDir.resolve(OPERATION_LOCK);
        if (!Files.exists(lockPath)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock(0, Long.MAX_VALUE, false);
            } catch (OverlappingFileLockException ex) {
                return true;
            }
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (Exception ex) {
            return true;
        }
    }

    public static boolean waitForOperationExit(Path updateDir, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!isOperationActive(updateDir)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isOperationActive(updateDir);
    }

    public static class FileLockHandle implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        public FileLockHandle(FileChannel channel, FileLock lock) {
            this.channel = Objects.requireNonNull(channel, "channel cannot be null");
            this.lock = Objects.requireNonNull(lock, "lock cannot be null");
        }

        public FileLock lock() {
            return lock;
        }

        public FileChannel channel() {
            return channel;
        }

        @Override
        public void close() throws IOException {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } finally {
                if (channel.isOpen()) {
                    channel.close();
                }
            }
        }
    }
}
