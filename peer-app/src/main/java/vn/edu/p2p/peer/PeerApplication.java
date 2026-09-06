package vn.edu.p2p.peer;

import vn.edu.p2p.peer.config.AppConfig;
import vn.edu.p2p.peer.ui.MainFrame;
import vn.edu.p2p.peer.ui.SwingIncomingFilePrompt;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PeerApplication {
    private PeerApplication() {
    }

    public static void main(String[] args) {
        Path configPath = Path.of(args.length >= 1 ? args[0] : "peer.properties");

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                if (!Files.exists(configPath)) {
                    throw new IllegalArgumentException("Config file not found: " + configPath.toAbsolutePath());
                }

                AppConfig config = AppConfig.load(configPath);
                PeerRuntime runtime = new PeerRuntime(config);
                MainFrame frame = new MainFrame(runtime);
                runtime.transferManager().setListener(frame);
                runtime.transferManager().setIncomingFilePrompt(new SwingIncomingFilePrompt(frame));
                runtime.start();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        runtime.close();
                    } catch (Exception ignored) {
                    }
                }));

                frame.setVisible(true);
                frame.refreshPeers();
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(
                        null,
                        ex.getMessage(),
                        "Peer startup failed",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });
    }
}
