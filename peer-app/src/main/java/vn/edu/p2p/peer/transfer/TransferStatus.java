package vn.edu.p2p.peer.transfer;

public enum TransferStatus {
    PREPARING,
    WAITING_FOR_ACCEPTANCE,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    REJECTED,
    FAILED
}
