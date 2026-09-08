package vn.edu.p2p.peer.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DesktopTheme {
    private static final String THEME_PACKAGE = "vn.edu.p2p.peer.ui.theme";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private DesktopTheme() {
    }

    public static void install() throws UnsupportedLookAndFeelException {
        if (INSTALLED.compareAndSet(false, true)) {
            FlatLaf.registerCustomDefaultsSource(THEME_PACKAGE);
        }
        UIManager.setLookAndFeel(new FlatLightLaf());
    }

    public static void setDark(boolean dark) throws UnsupportedLookAndFeelException {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("setDark must be called on the Event Dispatch Thread");
        }
        if (isDark() == dark) {
            return;
        }
        UIManager.setLookAndFeel(dark ? new FlatDarkLaf() : new FlatLightLaf());
        FlatLaf.updateUI();
    }

    public static boolean isDark() {
        return UIManager.getLookAndFeel() instanceof FlatDarkLaf;
    }
}
