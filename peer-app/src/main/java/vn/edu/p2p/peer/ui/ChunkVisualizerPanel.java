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
import java.awt.RenderingHints;
import java.util.BitSet;

public final class ChunkVisualizerPanel extends JPanel {
    private static final Color COLOR_RECEIVED = new Color(46, 133, 64);
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
            label.setText("Select a transfer to inspect chunk progress");
            canvas.reset();
            return;
        }

        int percent = update.progressPercent();
        String speed = update.formatSpeed();
        String eta = update.formatEta();
        String sources = update.formatSources();

        label.setText("File: %s | Progress: %d%% | Speed: %s | ETA: %s | %s"
                .formatted(update.fileName(), percent, speed, eta, sources));

        long total = update.totalChunks();
        BitSet bits;
        if (update.receivedChunksMask() != null) {
            bits = BitSet.valueOf(update.receivedChunksMask());
        } else if (update.status() == TransferStatus.COMPLETED) {
            bits = new BitSet((int) Math.max(1, total));
            bits.set(0, (int) Math.max(1, total));
        } else if (total > 0) {
            bits = new BitSet((int) total);
            int estReceived = (int) ((percent * total) / 100);
            if (estReceived > 0) {
                bits.set(0, Math.min((int) total, estReceived));
            }
        } else {
            bits = new BitSet();
        }

        canvas.setChunks((int) Math.max(0, total), bits);
    }

    private static final class ChunkGridCanvas extends JPanel {
        private int totalChunks = 0;
        private BitSet received = new BitSet();

        private ChunkGridCanvas() {
            setBackground(Color.WHITE);
            setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        }

        private void reset() {
            this.totalChunks = 0;
            this.received = new BitSet();
            repaint();
        }

        private void setChunks(int totalChunks, BitSet received) {
            this.totalChunks = totalChunks;
            this.received = received != null ? received : new BitSet();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (totalChunks <= 0) {
                g.setColor(Color.GRAY);
                g.drawString("No chunks to display", 12, getHeight() / 2 + 4);
                return;
            }

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth() - 8;
            int height = getHeight() - 8;
            int maxBlocks = Math.min(totalChunks, 64);
            int blockWidth = Math.max(4, width / maxBlocks);
            int blockHeight = Math.min(24, height - 4);
            int y = (getHeight() - blockHeight) / 2;

            for (int i = 0; i < maxBlocks; i++) {
                int x = 4 + i * blockWidth;
                int chunkIndex = (int) ((i * (long) totalChunks) / maxBlocks);
                boolean isDone = received.get(chunkIndex);

                g2.setColor(isDone ? COLOR_RECEIVED : COLOR_PENDING);
                g2.fillRect(x, y, blockWidth - 1, blockHeight);
                g2.setColor(COLOR_BORDER);
                g2.drawRect(x, y, blockWidth - 1, blockHeight);
            }
        }
    }
}
