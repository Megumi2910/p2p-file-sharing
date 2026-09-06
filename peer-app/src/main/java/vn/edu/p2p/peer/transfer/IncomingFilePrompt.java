package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.model.FileMetadata;

import java.net.InetSocketAddress;

@FunctionalInterface
public interface IncomingFilePrompt {
    boolean accept(FileMetadata metadata, InetSocketAddress sender, long timeoutMillis);
}
