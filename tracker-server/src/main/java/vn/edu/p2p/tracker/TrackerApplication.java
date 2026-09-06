package vn.edu.p2p.tracker;

public final class TrackerApplication {
    private TrackerApplication() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 5000;
        TrackerServer tracker = new TrackerServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                tracker.close();
            } catch (Exception ignored) {
            }
        }));
        tracker.start();
    }
}
