package vn.edu.p2p.peer.ui;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.transfer.TransferListener;
import vn.edu.p2p.peer.transfer.TransferUpdate;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

public final class MainFrame extends JFrame implements TransferListener {
    private final PeerRuntime runtime;
    private final DefaultListModel<PeerInfo> peerModel = new DefaultListModel<>();
    private final JList<PeerInfo> peerList = new JList<>(peerModel);
    private final TransferTableModel transferModel = new TransferTableModel();
    private final JButton refreshButton = new JButton("Refresh peers");
    private final JButton sendButton = new JButton("Send file...");

    public MainFrame(PeerRuntime runtime) {
        super("P2P File Sharing - " + runtime.config().displayName());
        this.runtime = runtime;
        buildUi();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 560));
        setLocationByPlatform(true);

        JLabel identity = new JLabel(
                "Peer: " + runtime.config().displayName()
                        + " | ID: " + runtime.config().peerId()
                        + " | Listen port: " + runtime.config().peerPort()
        );
        identity.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(identity, BorderLayout.NORTH);

        peerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel peersPanel = new JPanel(new BorderLayout(8, 8));
        peersPanel.setBorder(BorderFactory.createTitledBorder("Online peers"));
        peersPanel.add(new JScrollPane(peerList), BorderLayout.CENTER);

        JPanel peerButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        peerButtons.add(refreshButton);
        peerButtons.add(sendButton);
        peersPanel.add(peerButtons, BorderLayout.SOUTH);

        JTable transfers = new JTable(transferModel);
        transfers.setFillsViewportHeight(true);
        JPanel transfersPanel = new JPanel(new BorderLayout());
        transfersPanel.setBorder(BorderFactory.createTitledBorder("Transfers"));
        transfersPanel.add(new JScrollPane(transfers), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, peersPanel, transfersPanel);
        split.setResizeWeight(0.3);
        add(split, BorderLayout.CENTER);

        refreshButton.addActionListener(e -> refreshPeers());
        sendButton.addActionListener(e -> chooseAndSend());
    }

    public void setStarting(boolean starting) {
        refreshButton.setEnabled(!starting);
        sendButton.setEnabled(!starting);
    }

    public void refreshPeers() {
        new SwingWorker<List<PeerInfo>, Void>() {
            @Override
            protected List<PeerInfo> doInBackground() throws Exception {
                return runtime.listPeers();
            }

            @Override
            protected void done() {
                try {
                    List<PeerInfo> peers = get();
                    peerModel.clear();
                    for (PeerInfo peer : peers) {
                        peerModel.addElement(peer);
                    }
                } catch (Exception ex) {
                    peerModel.clear();
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String msg = cause.getMessage() != null && !cause.getMessage().isBlank()
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Could not refresh peers: " + msg,
                            "Tracker error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void chooseAndSend() {
        PeerInfo target = peerList.getSelectedValue();
        if (target == null) {
            JOptionPane.showMessageDialog(this, "Select an online peer first.");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path file = chooser.getSelectedFile().toPath();
            try {
                runtime.sendFile(target, file);
            } catch (RejectedExecutionException ex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Transfer rejected: maximum concurrent transfers reached (" + runtime.config().maxConcurrentTransfers() + ")",
                        "Transfer Busy",
                        JOptionPane.WARNING_MESSAGE
                );
            }
        }
    }

    @Override
    public void onUpdate(TransferUpdate update) {
        SwingUtilities.invokeLater(() -> transferModel.update(update));
    }
}
