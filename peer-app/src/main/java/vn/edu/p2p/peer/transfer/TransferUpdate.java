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
            return status == TransferStatus.COMPLETED ? 100 : 0;
        }
        if (bytesTransferred <= 0) {
            return 0;
        }
        double ratio = (double) bytesTransferred / (double) totalBytes;
        int percent = (int) Math.floor(ratio * 100.0);
        return Math.clamp(percent, 0, 100);
    }
}
