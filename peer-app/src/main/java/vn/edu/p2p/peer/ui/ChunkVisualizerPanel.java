package vn.edu.p2p.peer.ui;

import vn.edu.p2p.peer.transfer.TransferStatus;
import vn.edu.p2p.peer.transfer.TransferUpdate;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.Arrays;

public final class ChunkVisualizerPanel extends JPanel {

    private enum BucketState {
        MISSING,
        PARTIAL,
        RECEIVED
    }

    private final JLabel statusLabel = new JLabel("Status: -");
    private final JTextArea fileNameArea = new JTextArea("-");
    private final JLabel peerSourcesLabel = new JLabel("Peer / Sources: -");
    private final JLabel bytesLabel = new JLabel("Transferred: -");
    private final JLabel speedEtaLabel = new JLabel("Speed / ETA: -");
    private final JTextArea messageArea = new JTextArea("");

    private final JPanel coverageCardPanel = new JPanel(new BorderLayout(4, 4));
    private final JLabel coverageHeaderLabel = new JLabel("Chunk coverage");
    private final ChunkCoverageCanvas coverageCanvas = new ChunkCoverageCanvas();
    private final JPanel legendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));

    private final JProgressBar fallbackProgress = new JProgressBar(0, 100);
    private final JLabel fallbackLabel = new JLabel("Per-chunk map unavailable for this transfer.");

    public ChunkVisualizerPanel() {
        super(new BorderLayout());
        buildUi();
    }

    private void buildUi() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Inspector Title
        JLabel title = new JLabel("Selected Transfer Details");
        title.putClientProperty("FlatLaf.styleClass", "h4");
        title.putClientProperty("html.disable", Boolean.TRUE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(8));

        // Details grid
        JPanel detailsGrid = new JPanel(new GridBagLayout());
        detailsGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(2, 0, 4, 12);

        // Filename
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        JLabel fnTitle = new JLabel("File:");
        fnTitle.putClientProperty("html.disable", Boolean.TRUE);
        detailsGrid.add(fnTitle, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        fileNameArea.setEditable(false);
        fileNameArea.setLineWrap(true);
        fileNameArea.setWrapStyleWord(true);
        fileNameArea.setOpaque(false);
        fileNameArea.setFont(UIManager.getFont("Label.font"));
        fileNameArea.putClientProperty("html.disable", Boolean.TRUE);
        detailsGrid.add(fileNameArea, gbc);

        // Status & Peer
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        statusLabel.putClientProperty("html.disable", Boolean.TRUE);
        detailsGrid.add(statusLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        peerSourcesLabel.putClientProperty("html.disable", Boolean.TRUE);
        detailsGrid.add(peerSourcesLabel, gbc);

        // Bytes & Speed/ETA
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        bytesLabel.putClientProperty("html.disable", Boolean.TRUE);
        detailsGrid.add(bytesLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        speedEtaLabel.putClientProperty("html.disable", Boolean.TRUE);
        detailsGrid.add(speedEtaLabel, gbc);

        // Message
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        JLabel msgTitle = new JLabel("Message:");
        msgTitle.putClientProperty("html.disable", Boolean.TRUE);
        detailsGrid.add(msgTitle, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        messageArea.setEditable(false);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        messageArea.setOpaque(false);
        messageArea.setFont(UIManager.getFont("Label.font"));
        messageArea.putClientProperty("html.disable", Boolean.TRUE);
        detailsGrid.add(messageArea, gbc);

        content.add(detailsGrid);
        content.add(Box.createVerticalStrut(10));

        // Coverage Header & Legend
        coverageHeaderLabel.putClientProperty("FlatLaf.styleClass", "h4");
        coverageHeaderLabel.putClientProperty("html.disable", Boolean.TRUE);
        coverageHeaderLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        legendPanel.setOpaque(false);
        legendPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        legendPanel.add(new LegendItem("Received", () -> getSemanticColor("P2P.success.foreground", new Color(35, 122, 70))));
        legendPanel.add(new LegendItem("Partial range", () -> getSemanticColor("P2P.accent.foreground", new Color(33, 93, 176))));
        legendPanel.add(new LegendItem("Missing", () -> getSemanticColor("P2P.pending.background", new Color(222, 229, 237))));

        JPanel headerLegendRow = new JPanel(new BorderLayout(8, 0));
        headerLegendRow.setOpaque(false);
        headerLegendRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerLegendRow.add(coverageHeaderLabel, BorderLayout.WEST);
        headerLegendRow.add(legendPanel, BorderLayout.EAST);
        content.add(headerLegendRow);
        content.add(Box.createVerticalStrut(6));

        // Coverage Canvas & Fallback container
        coverageCanvas.setPreferredSize(new Dimension(500, 24));
        coverageCanvas.setAlignmentX(Component.LEFT_ALIGNMENT);

        fallbackProgress.setStringPainted(true);
        fallbackProgress.setAlignmentX(Component.LEFT_ALIGNMENT);
        fallbackLabel.putClientProperty("html.disable", Boolean.TRUE);
        fallbackLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        coverageCardPanel.setOpaque(false);
        coverageCardPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        coverageCardPanel.add(coverageCanvas, BorderLayout.CENTER);
        content.add(coverageCardPanel);

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);
        add(scrollPane, BorderLayout.CENTER);

        // Initial empty state
        updateFrom(null);
    }

    public void updateFrom(TransferUpdate update) {
        if (update == null) {
            fileNameArea.setText("No transfer selected");
            statusLabel.setText("Status: -");
            statusLabel.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
            peerSourcesLabel.setText("Peer / Sources: -");
            bytesLabel.setText("Transferred: -");
            speedEtaLabel.setText("Speed / ETA: -");
            messageArea.setText("");
            legendPanel.setVisible(false);
            coverageHeaderLabel.setText("Chunk coverage");
            coverageCanvas.setChunks(0, null);
            coverageCardPanel.removeAll();
            coverageCardPanel.add(coverageCanvas, BorderLayout.CENTER);
            revalidate();
            repaint();
            return;
        }

        fileNameArea.setText(update.fileName());
        TransferStatus status = update.status();
        statusLabel.setText("Status: " + formatSentenceCase(status));
        statusLabel.setForeground(getStatusColor(status));

        peerSourcesLabel.setText("Peer / Sources: " + update.formatSources());
        bytesLabel.setText("Transferred: %s / %s (%d%%)".formatted(
                humanBytes(update.bytesTransferred()),
                humanBytes(update.totalBytes()),
                update.progressPercent()
        ));
        speedEtaLabel.setText("Speed: %s  |  ETA: %s".formatted(update.formatSpeed(), update.formatEta()));

        String msg = update.message();
        messageArea.setText(msg != null && !msg.isBlank() ? msg : "—");

        long totalChunks = update.totalChunks();
        long[] mask = update.receivedChunksMask();

        if (update.totalBytes() == 0 && totalChunks == 0) {
            legendPanel.setVisible(false);
            coverageHeaderLabel.setText("Empty file; no chunks.");
            coverageCanvas.setChunks(0, null);
            coverageCardPanel.removeAll();
            coverageCardPanel.add(coverageCanvas, BorderLayout.CENTER);
        } else if (mask != null && totalChunks > 0) {
            legendPanel.setVisible(true);
            coverageHeaderLabel.setText("Chunk coverage (%d total chunks)".formatted(totalChunks));
            coverageCanvas.setChunks(totalChunks, mask);
            coverageCardPanel.removeAll();
            coverageCardPanel.add(coverageCanvas, BorderLayout.CENTER);
        } else {
            // Per-chunk map unavailable fallback
            legendPanel.setVisible(false);
            coverageHeaderLabel.setText("Overall progress");
            fallbackProgress.setValue(update.progressPercent());
            fallbackProgress.setString(update.progressPercent() + "%");
            JPanel fallbackPanel = new JPanel(new BorderLayout(0, 4));
            fallbackPanel.setOpaque(false);
            fallbackPanel.add(fallbackProgress, BorderLayout.CENTER);
            fallbackPanel.add(fallbackLabel, BorderLayout.SOUTH);
            coverageCardPanel.removeAll();
            coverageCardPanel.add(fallbackPanel, BorderLayout.CENTER);
        }

        revalidate();
        repaint();
    }

    private static String formatSentenceCase(TransferStatus status) {
        if (status == null) return "-";
        return switch (status) {
            case PREPARING -> "Preparing";
            case WAITING_FOR_ACCEPTANCE -> "Waiting for acceptance";
            case TRANSFERRING -> "Transferring";
            case VERIFYING -> "Verifying";
            case COMPLETED -> "Completed";
            case REJECTED -> "Rejected";
            case CANCELLED -> "Cancelled";
            case FAILED -> "Failed";
        };
    }

    private Color getStatusColor(TransferStatus status) {
        if (status == null) return getSemanticColor("P2P.text.foreground", Color.BLACK);
        return switch (status) {
            case COMPLETED -> getSemanticColor("P2P.success.foreground", new Color(35, 122, 70));
            case TRANSFERRING -> getSemanticColor("P2P.accent.foreground", new Color(33, 93, 176));
            case WAITING_FOR_ACCEPTANCE, VERIFYING -> getSemanticColor("P2P.warning.foreground", new Color(131, 94, 18));
            case FAILED -> getSemanticColor("P2P.error.foreground", new Color(176, 43, 54));
            case REJECTED, CANCELLED -> getSemanticColor("P2P.muted.foreground", new Color(82, 97, 116));
            case PREPARING -> getSemanticColor("P2P.text.foreground", new Color(21, 33, 47));
        };
    }

    static Color getSemanticColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static String humanBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return "%.2f %s".formatted(bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private static final class LegendItem extends JPanel {
        private final java.util.function.Supplier<Color> colorSupplier;

        LegendItem(String labelText, java.util.function.Supplier<Color> colorSupplier) {
            super(new FlowLayout(FlowLayout.LEFT, 4, 0));
            setOpaque(false);
            this.colorSupplier = colorSupplier;

            JPanel swatch = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(colorSupplier.get());
                        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
                        g2.setColor(getSemanticColor("P2P.borderColor", Color.LIGHT_GRAY));
                        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
                    } finally {
                        g2.dispose();
                    }
                }
            };
            swatch.setPreferredSize(new Dimension(14, 14));
            swatch.setOpaque(false);
            add(swatch);

            JLabel label = new JLabel(labelText);
            label.setFont(UIManager.getFont("Label.font").deriveFont(11f));
            label.putClientProperty("html.disable", Boolean.TRUE);
            add(label);
        }
    }

    private static final class ChunkCoverageCanvas extends JPanel {
        private static final int MAX_BLOCKS = 64;

        private long totalChunks;
        private long[] receivedMask;

        // Cached bucket states
        private int cachedBucketCount = -1;
        private BucketState[] cachedBuckets = new BucketState[0];

        ChunkCoverageCanvas() {
            setOpaque(false);
        }

        void setChunks(long totalChunks, long[] mask) {
            this.totalChunks = Math.max(0, totalChunks);
            this.receivedMask = mask != null ? mask.clone() : null;
            this.cachedBucketCount = -1; // Invalidate cache on new update
            repaint();
        }

        private void ensureBuckets(int bucketCount) {
            if (bucketCount <= 0 || totalChunks <= 0) {
                cachedBuckets = new BucketState[0];
                cachedBucketCount = 0;
                return;
            }
            if (cachedBucketCount == bucketCount && cachedBuckets.length == bucketCount) {
                return;
            }

            cachedBuckets = new BucketState[bucketCount];
            cachedBucketCount = bucketCount;

            long q = totalChunks / bucketCount;
            long r = totalChunks % bucketCount;

            for (int b = 0; b < bucketCount; b++) {
                long start = b * q + Math.min((long) b, r);
                long length = q + (b < r ? 1 : 0);
                long end = start + length;

                if (length <= 0) {
                    cachedBuckets[b] = BucketState.MISSING;
                    continue;
                }

                if (receivedMask == null || receivedMask.length == 0) {
                    cachedBuckets[b] = BucketState.MISSING;
                    continue;
                }

                int startWord = (int) (start >> 6);
                int endWord = (int) ((end - 1) >> 6);

                long setBits = 0;
                for (int w = startWord; w <= endWord; w++) {
                    if (w < 0 || w >= receivedMask.length) {
                        continue;
                    }
                    long val = receivedMask[w];
                    long wordStartBit = (long) w << 6;
                    long wordEndBit = wordStartBit + 64;

                    long bitLow = Math.max(start, wordStartBit);
                    long bitHigh = Math.min(end, wordEndBit);

                    int lowOffset = (int) (bitLow - wordStartBit);
                    int highOffset = (int) (bitHigh - wordStartBit);

                    long highMask = (highOffset == 64) ? -1L : ((1L << highOffset) - 1);
                    long lowMask = (lowOffset == 64) ? 0L : ((1L << lowOffset) - 1);
                    long wordBitMask = highMask & ~lowMask;

                    setBits += Long.bitCount(val & wordBitMask);
                }

                if (setBits == length) {
                    cachedBuckets[b] = BucketState.RECEIVED;
                } else if (setBits > 0) {
                    cachedBuckets[b] = BucketState.PARTIAL;
                } else {
                    cachedBuckets[b] = BucketState.MISSING;
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            if (totalChunks <= 0) {
                return;
            }

            Insets insets = getInsets();
            int availableWidth = getWidth() - insets.left - insets.right;
            int blockHeight = Math.max(12, getHeight() - insets.top - insets.bottom);

            if (availableWidth <= 0 || blockHeight <= 0) {
                return;
            }

            int bucketCount = (int) Math.min(Math.min(totalChunks, MAX_BLOCKS), Math.max(1, availableWidth / 6));
            if (bucketCount <= 0) {
                return;
            }

            ensureBuckets(bucketCount);

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Color receivedColor = getSemanticColor("P2P.success.foreground", new Color(35, 122, 70));
                Color partialColor = getSemanticColor("P2P.accent.foreground", new Color(33, 93, 176));
                Color missingColor = getSemanticColor("P2P.pending.background", new Color(222, 229, 237));
                Color borderColor = getSemanticColor("P2P.borderColor", new Color(207, 215, 225));

                int xStart = insets.left;
                int y = insets.top;
                int blockWidth = availableWidth / bucketCount;

                for (int b = 0; b < cachedBuckets.length; b++) {
                    int x = xStart + b * blockWidth;
                    int width = (b == cachedBuckets.length - 1) ? (availableWidth - b * blockWidth) : blockWidth;
                    int rectWidth = Math.max(1, width - 1);

                    Color fill = switch (cachedBuckets[b]) {
                        case RECEIVED -> receivedColor;
                        case PARTIAL -> partialColor;
                        case MISSING -> missingColor;
                    };

                    g2.setColor(fill);
                    g2.fillRoundRect(x, y, rectWidth, blockHeight, 4, 4);
                    g2.setColor(borderColor);
                    g2.drawRoundRect(x, y, rectWidth, blockHeight, 4, 4);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
