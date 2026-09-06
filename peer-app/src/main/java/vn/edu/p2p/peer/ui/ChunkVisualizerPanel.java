package vn.edu.p2p.peer.ui;

import vn.edu.p2p.peer.transfer.TransferStatus;
import vn.edu.p2p.peer.transfer.TransferUpdate;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

public final class ChunkVisualizerPanel extends JPanel {
    private static final Color COLOR_RECEIVED = new Color(46, 133, 64);
    private static final Color COLOR_PARTIAL = new Color(56, 189, 248);
    private static final Color COLOR_PENDING = new Color(220, 224, 230);
    private static final Color COLOR_BORDER = new Color(180, 185, 195);

    private final JLabel label = new JLabel("Select a transfer to inspect chunk progress");
    private final ChunkGridCanvas canvas = new ChunkGridCanvas();

    public ChunkVisualizerPanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createTitledBorder("Chunk visualizer (active/selected transfer)"));
        label.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(label, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        setPreferredSize(new Dimension(500, 110));
    }

    public void updateFrom(TransferUpdate update) {
        if (update == null) {
            label.setText("No transfer selected");
            canvas.setChunks(0, null, false, 0);
            return;
        }

        int percent = update.progressPercent();
        String speed = update.formatSpeed();
        String eta = update.formatEta();
        String sources = update.formatSources();

        label.setText("File: %s | Progress: %d%% | Speed: %s | ETA: %s | %s"
                .formatted(update.fileName(), percent, speed, eta, sources));

        long total = update.totalChunks();
        long[] mask = update.receivedChunksMask();
        boolean isCompleted = update.status() == TransferStatus.COMPLETED;

        if (total <= 0 && update.totalBytes() > 0) {
            total = Math.max(1, (update.totalBytes() + 1048575) / 1048576);
        }

        canvas.setChunks(total, mask, isCompleted, percent);
    }

    private static final class ChunkGridCanvas extends JPanel {
        private static final int MAX_BLOCKS = 64;

        private long totalChunks;
        private long[] receivedMask;
        private boolean isCompleted;
        private int percent;

        public ChunkGridCanvas() {
            setOpaque(true);
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(new Color(230, 235, 240)));
        }

        public void setChunks(long totalChunks, long[] mask, boolean isCompleted, int percent) {
            this.totalChunks = Math.max(0, totalChunks);
            this.receivedMask = mask;
            this.isCompleted = isCompleted;
            this.percent = percent;
            repaint();
        }

        private static boolean isBitSet(long[] mask, long bitIndex) {
            if (mask == null || bitIndex < 0) return false;
            int word = (int) (bitIndex >> 6);
            if (word < 0 || word >= mask.length) return false;
            return (mask[word] & (1L << (bitIndex & 63))) != 0;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (totalChunks <= 0) {
                g.setColor(Color.GRAY);
                g.drawString("No chunks to display", 12, getHeight() / 2 + 4);
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Insets insets = getInsets();
                int xStart = insets.left + 4;
                int y = insets.top + 6;
                int availableWidth = getWidth() - insets.left - insets.right - 8;
                int blockHeight = Math.max(12, getHeight() - insets.top - insets.bottom - 14);

                if (availableWidth <= 0 || blockHeight <= 0) {
                    return;
                }

                int maxBlocksByWidth = Math.max(1, availableWidth / 6);
                int displayBlocks = (int) Math.min(Math.min(totalChunks, MAX_BLOCKS), maxBlocksByWidth);
                if (displayBlocks <= 0) {
                    return;
                }

                int blockWidth = availableWidth / displayBlocks;

                for (int b = 0; b < displayBlocks; b++) {
                    int x = xStart + b * blockWidth;

                    Color fillColor;
                    if (isCompleted) {
                        fillColor = COLOR_RECEIVED;
                    } else if (receivedMask != null) {
                        long rangeStart = (long) Math.floor((double) b * totalChunks / displayBlocks);
                        long rangeEnd = (long) Math.floor((double) (b + 1) * totalChunks / displayBlocks);
                        rangeEnd = Math.max(rangeStart + 1, rangeEnd);
                        rangeEnd = Math.min(rangeEnd, totalChunks);

                        long setBits = 0;
                        long rangeLength = rangeEnd - rangeStart;
                        for (long i = rangeStart; i < rangeEnd; i++) {
                            if (isBitSet(receivedMask, i)) {
                                setBits++;
                            }
                        }

                        if (setBits == rangeLength) {
                            fillColor = COLOR_RECEIVED;
                        } else if (setBits > 0) {
                            fillColor = COLOR_PARTIAL;
                        } else {
                            fillColor = COLOR_PENDING;
                        }
                    } else {
                        // Sequential estimate based on percent
                        int blockThreshold = (int) Math.round((double) b * 100.0 / displayBlocks);
                        fillColor = (percent > blockThreshold) ? COLOR_RECEIVED : COLOR_PENDING;
                    }

                    g2.setColor(fillColor);
                    g2.fillRect(x, y, Math.max(1, blockWidth - 1), blockHeight);
                    g2.setColor(COLOR_BORDER);
                    g2.drawRect(x, y, Math.max(1, blockWidth - 1), blockHeight);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
