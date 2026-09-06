package vn.edu.p2p.peer.transfer;

@FunctionalInterface
public interface TransferListener {
    void onUpdate(TransferUpdate update);

    static TransferListener noOp() {
        return update -> { };
    }
}
