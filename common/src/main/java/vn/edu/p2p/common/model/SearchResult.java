package vn.edu.p2p.common.model;

import java.util.List;
import java.util.Objects;

public record SearchResult(
        FileRecord file,
        List<PeerInfo> providers
) {
    public SearchResult {
        Objects.requireNonNull(file, "file cannot be null");
        providers = providers == null ? List.of() : List.copyOf(providers);
    }
}
