package vn.edu.p2p.peer.transfer;

public record TransferUpdate(
        String transferId,
        String fileName,
        String peerName,
        TransferDirection direction,
        TransferStatus status,
        long bytesTransferred,
        long totalBytes,
        double bytesPerSecond,
        int activeSourceCount,
        long[] receivedChunksMask,
        long totalChunks,
        String message
) {
    public TransferUpdate {
        receivedChunksMask = (receivedChunksMask != null) ? receivedChunksMask.clone() : null;
    }

    @Override
    public long[] receivedChunksMask() {
        return receivedChunksMask != null ? receivedChunksMask.clone() : null;
    }

    public TransferUpdate(
            String transferId,
            String fileName,
            String peerName,
            TransferDirection direction,
            TransferStatus status,
            long bytesTransferred,
            long totalBytes,
            double bytesPerSecond,
            String message
    ) {
        this(
                transferId,
                fileName,
                peerName,
                direction,
                status,
                bytesTransferred,
                totalBytes,
                bytesPerSecond,
                status == TransferStatus.TRANSFERRING ? 1 : 0,
                null,
                0,
                message
        );
    }

    public int progressPercent() {
        if (totalBytes <= 0) {
            return status == TransferStatus.COMPLETED ? 100 : 0;
        }
        if (bytesTransferred <= 0) {
            return 0;
        }
        double ratio = (double) bytesTransferred / (double) totalBytes;
        int percent = (int) Math.floor(ratio * 100.0);
        return Math.clamp(percent, 0, 100);
    }

    public String formatSpeed() {
        if (!Double.isFinite(bytesPerSecond) || bytesPerSecond <= 0) {
            return "--";
        }
        double kib = bytesPerSecond / 1024.0;
        if (kib < 1024) {
            return "%.1f KiB/s".formatted(kib);
        }
        double mib = kib / 1024.0;
        return "%.2f MiB/s".formatted(mib);
    }

    public String formatEta() {
        if (status == TransferStatus.COMPLETED) {
            return "Done";
        }
        if (status != TransferStatus.TRANSFERRING || !Double.isFinite(bytesPerSecond) || bytesPerSecond <= 1.0) {
            return "--";
        }
        if (totalBytes <= 0 || bytesTransferred < 0) {
            return "--";
        }
        long remainingBytes = Math.max(0L, totalBytes - Math.min(bytesTransferred, totalBytes));
        double remainingSecs = remainingBytes / bytesPerSecond;
        if (!Double.isFinite(remainingSecs) || remainingSecs < 0 || remainingSecs > 86400.0 * 7) {
            return "--";
        }
        long seconds = (long) Math.ceil(remainingSecs);
        long mins = seconds / 60;
        long secs = seconds % 60;
        if (mins >= 60) {
            long hours = mins / 60;
            mins = mins % 60;
            return "%02d:%02d:%02d".formatted(hours, mins, secs);
        }
        return "%02d:%02d".formatted(mins, secs);
    }

    public String formatSources() {
        if (activeSourceCount <= 1) {
            return peerName;
        }
        return peerName + " (" + activeSourceCount + " sources)";
    }
}
