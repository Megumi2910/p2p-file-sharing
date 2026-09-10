package vn.edu.p2p.peer.ui;

import com.formdev.flatlaf.util.UIScale;

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.util.Objects;

final class DesktopLayout {
    private DesktopLayout() {
    }

    /**
     * Fits a window using unscaled preferred and minimum design sizes.
     * Inputs are scaled once, bounded to the usable screen bounds of the window's
     * current GraphicsConfiguration, populated content is packed, and the bounded
     * initial size is applied.
     *
     * @param window    the target window to fit
     * @param preferred unscaled design preferred dimensions
     * @param minimum   unscaled design minimum dimensions
     */
    static void fitWindow(Window window, Dimension preferred, Dimension minimum) {
        Objects.requireNonNull(window, "window cannot be null");
        Objects.requireNonNull(preferred, "preferred dimensions cannot be null");
        Objects.requireNonNull(minimum, "minimum dimensions cannot be null");

        int scaledPrefW = UIScale.scale(preferred.width);
        int scaledPrefH = UIScale.scale(preferred.height);
        int scaledMinW = UIScale.scale(minimum.width);
        int scaledMinH = UIScale.scale(minimum.height);

        Rectangle usableBounds = getUsableScreenBounds(window);

        int targetPrefW = scaledPrefW;
        int targetPrefH = scaledPrefH;
        int targetMinW = scaledMinW;
        int targetMinH = scaledMinH;

        if (usableBounds != null && usableBounds.width > 0 && usableBounds.height > 0) {
            targetPrefW = Math.min(scaledPrefW, usableBounds.width);
            targetPrefH = Math.min(scaledPrefH, usableBounds.height);
            targetMinW = Math.min(scaledMinW, usableBounds.width);
            targetMinH = Math.min(scaledMinH, usableBounds.height);
        }

        targetMinW = Math.min(targetMinW, targetPrefW);
        targetMinH = Math.min(targetMinH, targetPrefH);

        window.setMinimumSize(new Dimension(targetMinW, targetMinH));
        window.pack();
        window.setSize(new Dimension(targetPrefW, targetPrefH));
    }

    /**
     * Returns the usable bounds of the display containing the given window,
     * accounting for native system screen insets (taskbars, docks, panels).
     */
    static Rectangle getUsableScreenBounds(Window window) {
        if (window == null) {
            return null;
        }
        try {
            GraphicsConfiguration gc = window.getGraphicsConfiguration();
            if (gc != null) {
                Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                Rectangle screenBounds = gc.getBounds();
                return new Rectangle(
                        screenBounds.x + screenInsets.left,
                        screenBounds.y + screenInsets.top,
                        screenBounds.width - screenInsets.left - screenInsets.right,
                        screenBounds.height - screenInsets.top - screenInsets.bottom
                );
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
