package vn.edu.p2p.peer.ui;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.SearchResult;
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
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
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
    private final SearchResultTableModel searchModel = new SearchResultTableModel();
    private final JTable searchTable = new JTable(searchModel);
    private final JTextField searchField = new JTextField(20);
    private final JButton searchButton = new JButton("Search Catalogue");
    private final JButton downloadButton = new JButton("Download Selected");
    private final JButton refreshButton = new JButton("Refresh peers");
    private final JButton sendButton = new JButton("Send file...");
    private final JTabbedPane tabbedPane = new JTabbedPane();

    public MainFrame(PeerRuntime runtime) {
        super("P2P File Sharing - " + runtime.config().displayName());
        this.runtime = runtime;
        buildUi();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(950, 600));
        setLocationByPlatform(true);

        JLabel identity = new JLabel(
                "Peer: " + runtime.config().displayName()
                        + " | ID: " + runtime.config().peerId()
                        + " | Listen port: " + runtime.config().peerPort()
                        + " | Shared folder: " + runtime.config().sharedDir().toAbsolutePath()
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

        // Tab 1: Transfers
        JTable transfers = new JTable(transferModel);
        transfers.setFillsViewportHeight(true);
        JPanel transfersPanel = new JPanel(new BorderLayout());
        transfersPanel.add(new JScrollPane(transfers), BorderLayout.CENTER);
        tabbedPane.addTab("Transfers", transfersPanel);

        // Tab 2: Catalogue Search
        JPanel searchPanel = new JPanel(new BorderLayout(8, 8));
        JPanel searchTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        searchTop.add(new JLabel("Keyword:"));
        searchTop.add(searchField);
        searchTop.add(searchButton);
        searchTop.add(downloadButton);
        searchPanel.add(searchTop, BorderLayout.NORTH);

        searchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        searchTable.setFillsViewportHeight(true);
        searchPanel.add(new JScrollPane(searchTable), BorderLayout.CENTER);
        tabbedPane.addTab("Catalogue Search", searchPanel);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, peersPanel, tabbedPane);
        split.setResizeWeight(0.3);
        add(split, BorderLayout.CENTER);

        refreshButton.addActionListener(e -> refreshPeers());
        sendButton.addActionListener(e -> chooseAndSend());
        searchButton.addActionListener(e -> performSearch());
        searchField.addActionListener(e -> performSearch());
        downloadButton.addActionListener(e -> downloadSelected());
    }

    public void setStarting(boolean starting) {
        refreshButton.setEnabled(!starting);
        sendButton.setEnabled(!starting);
        searchButton.setEnabled(!starting);
        downloadButton.setEnabled(!starting);
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

    public void performSearch() {
        String query = searchField.getText();
        new SwingWorker<List<SearchResult>, Void>() {
            @Override
            protected List<SearchResult> doInBackground() throws Exception {
                return runtime.searchFiles(query);
            }

            @Override
            protected void done() {
                try {
                    List<SearchResult> results = get();
                    searchModel.setResults(results);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Search failed: " + cause.getMessage(),
                            "Search error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void downloadSelected() {
        int selectedRow = searchTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "Select a file from search results to download.");
            return;
        }
        SearchResult result = searchModel.getResultAt(selectedRow);
        if (result == null || result.providers().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No online providers available for this file.");
            return;
        }

        try {
            runtime.downloadFile(result);
            tabbedPane.setSelectedIndex(0); // Switch to Transfers tab to watch progress
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not start download: " + ex.getMessage(), "Download error", JOptionPane.ERROR_MESSAGE);
        }
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
                tabbedPane.setSelectedIndex(0);
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
