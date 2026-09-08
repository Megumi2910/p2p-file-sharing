package vn.edu.p2p.peer;

import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.ui.DesktopTheme;
import vn.edu.p2p.peer.ui.MainFrame;
import vn.edu.p2p.peer.ui.SwingIncomingFilePrompt;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PeerApplication {
    private PeerApplication() {
    }

    public static void main(String[] args) {
        Path configPath = Path.of(args.length >= 1 ? args[0] : "peer.properties");

        try {
            try {
                DesktopTheme.install();
            } catch (Exception ex) {
                System.err.println("Could not initialize the desktop theme: " + ex.getMessage());
                ex.printStackTrace();
                return;
            }

            if (!Files.exists(configPath)) {
                throw new IllegalArgumentException("Config file not found: " + configPath.toAbsolutePath());
            }
            AppConfig config = AppConfig.load(configPath);
            PeerRuntime runtime = new PeerRuntime(config);

            SwingUtilities.invokeLater(() -> {
                MainFrame frame = new MainFrame(runtime);
                frame.setStarting(true);
                frame.setVisible(true);

                runtime.transferManager().setListener(frame);
                runtime.transferManager().setIncomingFilePrompt(new SwingIncomingFilePrompt(frame));

                Thread shutdownHook = new Thread(() -> {
                    try {
                        runtime.close();
                    } catch (Exception ignored) {
                    }
                }, "peer-shutdown-hook");
                Runtime.getRuntime().addShutdownHook(shutdownHook);

                frame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        new Thread(() -> {
                            try {
                                runtime.close();
                            } catch (Exception ignored) {
                            }
                        }, "peer-window-close").start();
                    }
                });

                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() throws Exception {
                        runtime.start();
                        return runtime.trackerObservedHost();
                    }

                    @Override
                    protected void done() {
                        try {
                            get();
                            frame.setStarting(false);
                            frame.refreshPeers();
                        } catch (Exception ex) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            JOptionPane.showMessageDialog(
                                    frame,
                                    "Peer startup failed: " + cause.getMessage(),
                                    "Startup Error",
                                    JOptionPane.ERROR_MESSAGE
                            );
                            frame.dispose();
                            try {
                                runtime.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }.execute();
            });
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(
                    null,
                    ex.getMessage(),
                    "Peer startup failed",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }
}
