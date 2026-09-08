package vn.edu.p2p.peer.update;

import java.net.URI;

public final class ReleaseFixtureClient {

    private ReleaseFixtureClient() {
    }

    public static ReleaseClient create(BuildInfo buildInfo, ReleaseClient.HttpTransport transport, URI latestReleaseEndpoint) {
        return new ReleaseClient(buildInfo, transport, latestReleaseEndpoint);
    }

    public static ReleaseClient createDefault(BuildInfo buildInfo) {
        return new ReleaseClient(buildInfo);
    }
}
