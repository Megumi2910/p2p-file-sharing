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
        String message
) {
    public int progressPercent() {
        if (totalBytes <= 0) {
            return 0;
        }
        return (int) Math.min(100, (bytesTransferred * 100L) / totalBytes);
    }
}
