package vn.edu.p2p.tracker;

public final class TrackerApplication {
    private TrackerApplication() {
    }

    public static void main(String[] args) throws Exception {
        int port = 5000;
        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Tracker port must be a valid integer: " + args[0], ex);
            }
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Tracker port must be between 1 and 65535: " + port);
        }
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
