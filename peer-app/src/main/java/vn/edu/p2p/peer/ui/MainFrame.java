package vn.edu.p2p.peer.ui;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.SearchResult;
import vn.edu.p2p.peer.PeerRuntime;
import vn.edu.p2p.peer.transfer.TransferDirection;
import vn.edu.p2p.peer.transfer.TransferListener;
import vn.edu.p2p.peer.transfer.TransferStatus;
import vn.edu.p2p.peer.transfer.TransferUpdate;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.RejectedExecutionException;

public final class MainFrame extends JFrame implements TransferListener {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PeerRuntime runtime;
    private final vn.edu.p2p.peer.config.ConfigStore configStore;
    private final vn.edu.p2p.peer.update.UpdateService updateService;
    private final vn.edu.p2p.peer.update.RestartCoordinator restartCoordinator;

    private SettingsDialog settingsDialog;
    private UpdateDialog updateDialog;

    // Header controls
    private final JLabel subtitleLabel = new JLabel("Peer: Starting...");
    private final JComboBox<String> themeCombo = new JComboBox<>(new String[]{"Light", "Dark"});
    private final JButton settingsButton = new JButton("Settings...");
    private final JButton updateButton = new JButton("Software updates...");
    private final JLabel updateBadgeLabel = new JLabel();
    private Runnable closeHandler;
    // Peer panel controls
    private final DefaultListModel<PeerInfo> peerModel = new DefaultListModel<>();
    private final JList<PeerInfo> peerList = new JList<>(peerModel);
    private final CardLayout peerListCardLayout = new CardLayout();
    private final JPanel peerListCardPanel = new JPanel(peerListCardLayout);
    private final JLabel peerEmptyTitle = new JLabel("No peers listed");
    private final JLabel peerEmptySubtitle = new JLabel("Start another peer, then refresh.");
    private final JButton refreshButton = new JButton("Refresh peers");
    private final JButton sendButton = new JButton("Send file...");

    // Workspace tabbed pane
    private final JTabbedPane tabbedPane = new JTabbedPane();

    // Transfers tab
    private final TransferTableModel transferModel = new TransferTableModel();
    private final JTable transfersTable = new JTable(transferModel);
    private final ChunkVisualizerPanel visualizer = new ChunkVisualizerPanel();
    private final CardLayout transfersCardLayout = new CardLayout();
    private final JPanel transfersCardPanel = new JPanel(transfersCardLayout);

    // Search tab
    private final SearchResultTableModel searchModel = new SearchResultTableModel();
    private final JTable searchTable = new JTable(searchModel);
    private final JTextField searchField = new JTextField(24);
    private final JButton searchButton = new JButton("Search Catalogue");
    private final JButton downloadButton = new JButton("Download Selected");
    private final CardLayout searchCardLayout = new CardLayout();
    private final JPanel searchCardPanel = new JPanel(searchCardLayout);
    private final JLabel searchEmptyTitle = new JLabel("Find shared files");
    private final JLabel searchEmptySubtitle = new JLabel("Enter a filename to search the catalogue.");
    private final JPanel searchDetailPanel = new JPanel(new BorderLayout(4, 4));
    private final JTextArea searchDetailText = new JTextArea();

    // Log tab
    private final JTextArea logArea = new JTextArea();
    private final CardLayout logCardLayout = new CardLayout();
    private final JPanel logCardPanel = new JPanel(logCardLayout);
    private final JButton clearLogBtn = new JButton("Clear Log");

    // State flags
    private boolean starting = true;
    private boolean refreshing = false;
    private boolean searching = false;
    private long searchSequence = 0;
    private boolean ignoreThemeEvents = false;

    public MainFrame(
            PeerRuntime runtime,
            vn.edu.p2p.peer.config.ConfigStore configStore,
            vn.edu.p2p.peer.update.UpdateService updateService,
            vn.edu.p2p.peer.update.RestartCoordinator restartCoordinator
    ) {
        super("P2P File Sharing - " + runtime.config().displayName());
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime cannot be null");
        this.configStore = java.util.Objects.requireNonNull(configStore, "configStore cannot be null");
        this.updateService = java.util.Objects.requireNonNull(updateService, "updateService cannot be null");
        this.restartCoordinator = restartCoordinator;
        buildUi();
        wireUpdateListener();
    }

    public void setCloseHandler(Runnable closeHandler) {
        this.closeHandler = closeHandler;
    }
    private void buildUi() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (closeHandler != null) {
                    closeHandler.run();
                } else {
                    dispose();
                }
            }
        });
        setLocationByPlatform(true);

        applyWindowBounds();

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));

        // 1. Header
        root.add(buildHeader(), BorderLayout.NORTH);

        // 2. Main Workspace split pane
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildPeersPanel(), buildTabsPanel());
        mainSplit.setDividerLocation(248);
        mainSplit.setResizeWeight(0.0);
        mainSplit.setContinuousLayout(true);
        root.add(mainSplit, BorderLayout.CENTER);

        setContentPane(root);

        wireActions();
        updateActionStates();
    }

    private void applyWindowBounds() {
        Rectangle maxBounds = null;
        try {
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc != null) {
                Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                Rectangle screenBounds = gc.getBounds();
                maxBounds = new Rectangle(
                        screenBounds.x + screenInsets.left,
                        screenBounds.y + screenInsets.top,
                        screenBounds.width - screenInsets.left - screenInsets.right,
                        screenBounds.height - screenInsets.top - screenInsets.bottom
                );
            }
        } catch (Exception ignored) {
        }

        int targetWidth = 1200;
        int targetHeight = 760;
        int minWidth = 980;
        int minHeight = 620;

        if (maxBounds != null) {
            targetWidth = Math.min(targetWidth, maxBounds.width);
            targetHeight = Math.min(targetHeight, maxBounds.height);
            minWidth = Math.min(minWidth, maxBounds.width);
            minHeight = Math.min(minHeight, maxBounds.height);
        }

        setPreferredSize(new Dimension(targetWidth, targetHeight));
        setMinimumSize(new Dimension(minWidth, minHeight));
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 8));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        // Left: Title + Subtitle
        JPanel titlePanel = new JPanel();
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel("P2P File Sharing");
        titleLabel.putClientProperty("FlatLaf.styleClass", "h2");
        titleLabel.putClientProperty("html.disable", Boolean.TRUE);

        subtitleLabel.setText("Peer: " + runtime.config().displayName() + " / Starting...");
        subtitleLabel.putClientProperty("FlatLaf.styleClass", "muted");
        subtitleLabel.putClientProperty("html.disable", Boolean.TRUE);

        titlePanel.add(titleLabel);
        titlePanel.add(Box.createVerticalStrut(2));
        titlePanel.add(subtitleLabel);
        header.add(titlePanel, BorderLayout.WEST);

        // Right: Settings button + Theme selector
        JPanel controlsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));

        settingsButton.putClientProperty("html.disable", Boolean.TRUE);
        settingsButton.addActionListener(e -> openSettingsDialog());
        controlsPanel.add(settingsButton);
        JLabel themeLabel = new JLabel("Theme:");
        themeLabel.setLabelFor(themeCombo);
        themeLabel.putClientProperty("html.disable", Boolean.TRUE);
        controlsPanel.add(themeLabel);

        UIManager.addPropertyChangeListener(evt -> {
            if ("lookAndFeel".equals(evt.getPropertyName())) {
                ignoreThemeEvents = true;
                try {
                    themeCombo.setSelectedItem(DesktopTheme.isDark() ? "Dark" : "Light");
                } finally {
                    ignoreThemeEvents = false;
                }
            }
        });
        themeCombo.setToolTipText("Applies to this session");
        themeCombo.getAccessibleContext().setAccessibleName("Theme");
        themeCombo.putClientProperty("html.disable", Boolean.TRUE);
        themeCombo.setSelectedItem(DesktopTheme.isDark() ? "Dark" : "Light");
        themeCombo.addActionListener(e -> onThemeSelectionChanged());
        controlsPanel.add(themeCombo);

        header.add(controlsPanel, BorderLayout.EAST);

        // Separate toolbar for updates
        JPanel updateToolBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        updateToolBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        updateButton.putClientProperty("html.disable", Boolean.TRUE);
        updateButton.addActionListener(e -> openUpdateDialog());
        updateToolBar.add(updateButton);

        updateBadgeLabel.putClientProperty("html.disable", Boolean.TRUE);
        updateBadgeLabel.setVisible(false);
        updateToolBar.add(updateBadgeLabel);

        JPanel headerWrapper = new JPanel(new BorderLayout());
        headerWrapper.add(header, BorderLayout.NORTH);
        headerWrapper.add(updateToolBar, BorderLayout.CENTER);
        headerWrapper.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.SOUTH);
        return headerWrapper;
    }

    private JPanel buildPeersPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 8));
        panel.setMinimumSize(new Dimension(216, 200));
        panel.setPreferredSize(new Dimension(248, 400));

        // Header caption
        JPanel topHeader = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Online peers");
        title.putClientProperty("FlatLaf.styleClass", "h4");
        title.putClientProperty("html.disable", Boolean.TRUE);
        JLabel caption = new JLabel("Last refresh only");
        caption.setFont(UIManager.getFont("Label.font").deriveFont(11f));
        caption.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
        caption.putClientProperty("html.disable", Boolean.TRUE);
        topHeader.add(title, BorderLayout.NORTH);
        topHeader.add(caption, BorderLayout.SOUTH);
        panel.add(topHeader, BorderLayout.NORTH);

        // Center: Peer list with CardLayout (list vs empty)
        peerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        peerList.setFixedCellHeight(56);
        peerList.setCellRenderer(new PeerListCellRenderer());
        peerList.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateActionStates();
            }
        });

        JScrollPane scrollPane = new JScrollPane(peerList);
        scrollPane.setBorder(BorderFactory.createLineBorder(getSemanticColor("P2P.borderColor", Color.LIGHT_GRAY)));
        peerListCardPanel.add(scrollPane, "list");

        JPanel emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBorder(BorderFactory.createEmptyBorder(24, 12, 12, 12));
        peerEmptyTitle.putClientProperty("FlatLaf.styleClass", "h4");
        peerEmptyTitle.putClientProperty("html.disable", Boolean.TRUE);
        peerEmptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        peerEmptySubtitle.setFont(UIManager.getFont("Label.font").deriveFont(12f));
        peerEmptySubtitle.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
        peerEmptySubtitle.putClientProperty("html.disable", Boolean.TRUE);
        peerEmptySubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyPanel.add(peerEmptyTitle);
        emptyPanel.add(Box.createVerticalStrut(4));
        emptyPanel.add(peerEmptySubtitle);
        peerListCardPanel.add(emptyPanel, "empty");

        peerListCardLayout.show(peerListCardPanel, "empty");
        panel.add(peerListCardPanel, BorderLayout.CENTER);

        // Buttons
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        refreshButton.putClientProperty("html.disable", Boolean.TRUE);
        sendButton.putClientProperty("html.disable", Boolean.TRUE);
        sendButton.putClientProperty("FlatLaf.styleClass", "primary");

        buttonsPanel.add(refreshButton);
        buttonsPanel.add(sendButton);
        panel.add(buttonsPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildTabsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 0));

        tabbedPane.addTab("Transfers", buildTransfersTab());
        tabbedPane.addTab("Catalogue Search", buildSearchTab());
        tabbedPane.addTab("Activity Log", buildLogTab());

        panel.add(tabbedPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildTransfersTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        transfersTable.setRowHeight(36);
        transfersTable.setFillsViewportHeight(true);
        transfersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        transfersTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // Setup columns
        setupTransferColumns();

        // Responsive width handling: expand only File column on viewport width change
        JScrollPane tableScroll = new JScrollPane(transfersTable);
        tableScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                adjustTransferColumnWidths(tableScroll.getViewport().getWidth());
            }
        });

        // Selection listener for inspector
        transfersTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int viewRow = transfersTable.getSelectedRow();
                if (viewRow >= 0) {
                    int modelRow = transfersTable.convertRowIndexToModel(viewRow);
                    visualizer.updateFrom(transferModel.getUpdateAt(modelRow));
                } else {
                    visualizer.updateFrom(null);
                }
            }
        });

        // Vertical split pane for Table + Inspector
        JSplitPane transferSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, visualizer);
        transferSplit.setResizeWeight(0.65);
        transferSplit.setContinuousLayout(true);
        transferSplit.setBorder(BorderFactory.createEmptyBorder());

        transfersCardPanel.add(transferSplit, "table");

        // Empty state panel
        JPanel emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBorder(BorderFactory.createEmptyBorder(48, 24, 24, 24));

        JLabel emptyTitle = new JLabel("No transfers yet");
        emptyTitle.putClientProperty("FlatLaf.styleClass", "h3");
        emptyTitle.putClientProperty("html.disable", Boolean.TRUE);
        emptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel emptySubtitle = new JLabel("Select a peer and choose Send file, or download from Catalogue Search.");
        emptySubtitle.setFont(UIManager.getFont("Label.font").deriveFont(13f));
        emptySubtitle.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
        emptySubtitle.putClientProperty("html.disable", Boolean.TRUE);
        emptySubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        emptyPanel.add(emptyTitle);
        emptyPanel.add(Box.createVerticalStrut(8));
        emptyPanel.add(emptySubtitle);

        transfersCardPanel.add(emptyPanel, "empty");
        transfersCardLayout.show(transfersCardPanel, "empty");

        panel.add(transfersCardPanel, BorderLayout.CENTER);
        return panel;
    }

    private void setupTransferColumns() {
        int[] widths = {80, 230, 150, 116, 96, 72, 130};
        TableColumnModel colModel = transfersTable.getColumnModel();

        for (int i = 0; i < widths.length && i < colModel.getColumnCount(); i++) {
            TableColumn col = colModel.getColumn(i);
            col.setPreferredWidth(widths[i]);
            col.setMinWidth(Math.min(widths[i], 60));
        }

        // Renderers
        colModel.getColumn(1).setCellRenderer(new LeftAlignCellRenderer());
        colModel.getColumn(2).setCellRenderer(new LeftAlignCellRenderer());
        colModel.getColumn(3).setCellRenderer(new ProgressCellRenderer());
        colModel.getColumn(4).setCellRenderer(new RightAlignCellRenderer());
        colModel.getColumn(5).setCellRenderer(new RightAlignCellRenderer());
        colModel.getColumn(6).setCellRenderer(new StatusCellRenderer());
    }

    private void adjustTransferColumnWidths(int viewportWidth) {
        TableColumnModel colModel = transfersTable.getColumnModel();
        if (colModel.getColumnCount() < 7) return;

        int totalOther = 80 + 150 + 116 + 96 + 72 + 130;
        int remaining = viewportWidth - totalOther;
        TableColumn fileCol = colModel.getColumn(1);
        fileCol.setPreferredWidth(Math.max(230, remaining));
    }

    private JPanel buildSearchTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        // Top search bar
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JLabel kwLabel = new JLabel("Keyword:");
        kwLabel.setLabelFor(searchField);
        kwLabel.putClientProperty("html.disable", Boolean.TRUE);

        searchField.putClientProperty("html.disable", Boolean.TRUE);
        searchButton.putClientProperty("html.disable", Boolean.TRUE);
        downloadButton.putClientProperty("html.disable", Boolean.TRUE);
        downloadButton.putClientProperty("FlatLaf.styleClass", "primary");

        topBar.add(kwLabel);
        topBar.add(searchField);
        topBar.add(searchButton);
        topBar.add(downloadButton);
        panel.add(topBar, BorderLayout.NORTH);

        // Search table
        searchTable.setRowHeight(36);
        searchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        searchTable.setFillsViewportHeight(true);
        setupSearchColumns();

        searchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSearchDetail();
                updateActionStates();
            }
        });

        // Search detail area (compact scrollable)
        searchDetailPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, getSemanticColor("P2P.borderColor", Color.LIGHT_GRAY)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        searchDetailText.setEditable(false);
        searchDetailText.setOpaque(false);
        searchDetailText.setLineWrap(true);
        searchDetailText.setWrapStyleWord(true);
        searchDetailText.putClientProperty("html.disable", Boolean.TRUE);

        JScrollPane detailScroll = new JScrollPane(searchDetailText);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailScroll.setPreferredSize(new Dimension(500, 72));
        searchDetailPanel.add(detailScroll, BorderLayout.CENTER);
        searchDetailPanel.setVisible(false);

        // Results panel containing Table + Details
        JPanel resultsPanel = new JPanel(new BorderLayout(0, 0));
        resultsPanel.add(new JScrollPane(searchTable), BorderLayout.CENTER);
        resultsPanel.add(searchDetailPanel, BorderLayout.SOUTH);

        searchCardPanel.add(resultsPanel, "results");

        // Empty card panel
        JPanel emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBorder(BorderFactory.createEmptyBorder(48, 24, 24, 24));
        searchEmptyTitle.putClientProperty("FlatLaf.styleClass", "h3");
        searchEmptyTitle.putClientProperty("html.disable", Boolean.TRUE);
        searchEmptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        searchEmptySubtitle.setFont(UIManager.getFont("Label.font").deriveFont(13f));
        searchEmptySubtitle.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
        searchEmptySubtitle.putClientProperty("html.disable", Boolean.TRUE);
        searchEmptySubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        emptyPanel.add(searchEmptyTitle);
        emptyPanel.add(Box.createVerticalStrut(8));
        emptyPanel.add(searchEmptySubtitle);

        searchCardPanel.add(emptyPanel, "empty");
        searchCardLayout.show(searchCardPanel, "empty");

        panel.add(searchCardPanel, BorderLayout.CENTER);
        return panel;
    }

    private void setupSearchColumns() {
        int[] widths = {260, 96, 80, 180, 190};
        TableColumnModel colModel = searchTable.getColumnModel();

        for (int i = 0; i < widths.length && i < colModel.getColumnCount(); i++) {
            TableColumn col = colModel.getColumn(i);
            col.setPreferredWidth(widths[i]);
            col.setMinWidth(60);
        }

        colModel.getColumn(0).setCellRenderer(new LeftAlignCellRenderer());
        colModel.getColumn(1).setCellRenderer(new RightAlignCellRenderer());
        colModel.getColumn(2).setCellRenderer(new RightAlignCellRenderer());

        DefaultTableCellRenderer monoRenderer = new DefaultTableCellRenderer();
        monoRenderer.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        colModel.getColumn(3).setCellRenderer(monoRenderer);

        colModel.getColumn(4).setCellRenderer(new LeftAlignCellRenderer());
    }

    private void updateSearchDetail() {
        int selectedRow = searchTable.getSelectedRow();
        if (selectedRow < 0) {
            searchDetailText.setText("");
            searchDetailPanel.setVisible(false);
            return;
        }

        int modelRow = searchTable.convertRowIndexToModel(selectedRow);
        SearchResult result = searchModel.getResultAt(modelRow);
        if (result == null) {
            searchDetailText.setText("");
            searchDetailPanel.setVisible(false);
            return;
        }

        String providersList = result.providers().stream()
                .map(p -> p.displayName() + " (" + p.host() + ":" + p.port() + ")")
                .collect(Collectors.joining(", "));
        String details = "File: %s\nSHA-256: %s\nProviders (%d): %s".formatted(
                result.file().fileName(),
                result.file().fileId(),
                result.providers().size(),
                providersList
        );
        searchDetailText.setText(details);
        searchDetailPanel.setVisible(true);
    }

    private JPanel buildLogTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.putClientProperty("html.disable", Boolean.TRUE);

        JScrollPane logScroll = new JScrollPane(logArea);
        logCardPanel.add(logScroll, "log");

        JPanel emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBorder(BorderFactory.createEmptyBorder(48, 24, 24, 24));
        JLabel logEmptyTitle = new JLabel("No transfer events yet");
        logEmptyTitle.putClientProperty("FlatLaf.styleClass", "h3");
        logEmptyTitle.putClientProperty("html.disable", Boolean.TRUE);
        logEmptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel logEmptySubtitle = new JLabel("Events appear here while this application is running.");
        logEmptySubtitle.setFont(UIManager.getFont("Label.font").deriveFont(13f));
        logEmptySubtitle.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
        logEmptySubtitle.putClientProperty("html.disable", Boolean.TRUE);
        logEmptySubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        emptyPanel.add(logEmptyTitle);
        emptyPanel.add(Box.createVerticalStrut(8));
        emptyPanel.add(logEmptySubtitle);

        logCardPanel.add(emptyPanel, "empty");
        logCardLayout.show(logCardPanel, "empty");

        panel.add(logCardPanel, BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        clearLogBtn.putClientProperty("html.disable", Boolean.TRUE);
        clearLogBtn.addActionListener(e -> {
            logArea.setText("");
            logCardLayout.show(logCardPanel, "empty");
        });
        bottomBar.add(clearLogBtn);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    private void wireActions() {
        refreshButton.addActionListener(e -> refreshPeers());
        sendButton.addActionListener(e -> chooseAndSend());
        searchButton.addActionListener(e -> performSearch());
        searchField.addActionListener(e -> performSearch());
        downloadButton.addActionListener(e -> downloadSelected());
    }

    private void onThemeSelectionChanged() {
        if (ignoreThemeEvents) return;
        String selected = (String) themeCombo.getSelectedItem();
        boolean wantDark = "Dark".equalsIgnoreCase(selected);
        if (wantDark == DesktopTheme.isDark()) return;

        try {
            DesktopTheme.setDark(wantDark);
        } catch (Exception ex) {
            ignoreThemeEvents = true;
            try {
                themeCombo.setSelectedItem(DesktopTheme.isDark() ? "Dark" : "Light");
            } finally {
                ignoreThemeEvents = false;
            }
            JOptionPane.showMessageDialog(
                    this,
                    "Could not change theme: " + ex.getMessage(),
                    "Theme Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void openSettingsDialog() {
        if (settingsDialog == null || !settingsDialog.isDisplayable()) {
            settingsDialog = new SettingsDialog(this, configStore, runtime, restartCoordinator);
        }
        settingsDialog.setVisible(true);
        settingsDialog.toFront();
    }

    private void openUpdateDialog() {
        if (updateDialog == null || !updateDialog.isDisplayable()) {
            updateDialog = new UpdateDialog(this, updateService, runtime, restartCoordinator, configStore);
        }
        updateDialog.setVisible(true);
        updateDialog.toFront();
    }

    public boolean hasUnsavedSettings() {
        return settingsDialog != null && settingsDialog.isDisplayable() && settingsDialog.hasUnsavedChanges();
    }

    public void setStopped(String detail) {
        SwingUtilities.invokeLater(() -> {
            subtitleLabel.setText("Stopped — relaunch manually");
            subtitleLabel.setForeground(Color.RED);
            updateButton.setEnabled(false);
            settingsButton.setEnabled(false);
            refreshButton.setEnabled(false);
            sendButton.setEnabled(false);
            searchButton.setEnabled(false);
            downloadButton.setEnabled(false);

            JOptionPane.showMessageDialog(
                    this,
                    detail,
                    "Application Stopped",
                    JOptionPane.WARNING_MESSAGE
            );
        });
    }

    public void freezeForRestart(boolean freeze) {
        SwingUtilities.invokeLater(() -> {
            updateButton.setEnabled(!freeze);
            settingsButton.setEnabled(!freeze);
            if (settingsDialog != null && settingsDialog.isDisplayable()) {
                settingsDialog.freezeEditing(freeze);
            }
        });
    }

    private void wireUpdateListener() {
        updateService.addListener(snapshot -> SwingUtilities.invokeLater(() -> {
            if (snapshot.state() == vn.edu.p2p.peer.update.UpdateService.UpdateState.UPDATE_AVAILABLE) {
                updateBadgeLabel.setText("Update available: " + snapshot.availableVersion());
                updateBadgeLabel.setForeground(new Color(0, 120, 215));
                updateBadgeLabel.setVisible(true);
            } else if (snapshot.state() == vn.edu.p2p.peer.update.UpdateService.UpdateState.READY_TO_RESTART) {
                updateBadgeLabel.setText("Update ready to install");
                updateBadgeLabel.setForeground(new Color(0, 150, 0));
                updateBadgeLabel.setVisible(true);
            } else {
                updateBadgeLabel.setVisible(false);
            }
        }));
    }

    public void setStarting(boolean starting) {
        this.starting = starting;
        subtitleLabel.setText("Peer: " + runtime.config().displayName() + " / " + (starting ? "Starting..." : "Ready"));
        updateActionStates();
    }

    private void updateActionStates() {
        refreshButton.setEnabled(!starting && !refreshing);
        sendButton.setEnabled(!starting && peerList.getSelectedValue() != null);
        searchField.setEnabled(!starting);
        searchButton.setEnabled(!starting);
        downloadButton.setEnabled(!starting && !searching && hasUsableSearchResultSelected());
    }

    private boolean hasUsableSearchResultSelected() {
        int selectedRow = searchTable.getSelectedRow();
        if (selectedRow < 0) return false;
        int modelRow = searchTable.convertRowIndexToModel(selectedRow);
        SearchResult result = searchModel.getResultAt(modelRow);
        return result != null && result.providers() != null && !result.providers().isEmpty();
    }

    public void refreshPeers() {
        if (refreshing) return;
        refreshing = true;
        peerEmptyTitle.setText("Refreshing peers...");
        peerEmptySubtitle.setText("Contacting tracker...");
        if (peerModel.isEmpty()) {
            peerListCardLayout.show(peerListCardPanel, "empty");
        }
        updateActionStates();

        PeerInfo selectedPeer = peerList.getSelectedValue();
        final String selectedId = selectedPeer != null ? selectedPeer.peerId() : null;

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
                    PeerInfo toReselect = null;
                    for (PeerInfo peer : peers) {
                        peerModel.addElement(peer);
                        if (selectedId != null && selectedId.equals(peer.peerId())) {
                            toReselect = peer;
                        }
                    }

                    if (peerModel.isEmpty()) {
                        peerEmptyTitle.setText("No peers listed");
                        peerEmptySubtitle.setText("Start another peer, then refresh.");
                        peerListCardLayout.show(peerListCardPanel, "empty");
                    } else {
                        peerListCardLayout.show(peerListCardPanel, "list");
                        if (toReselect != null) {
                            peerList.setSelectedValue(toReselect, true);
                        }
                    }
                } catch (Exception ex) {
                    peerModel.clear();
                    peerEmptyTitle.setText("Refresh failed");
                    peerEmptySubtitle.setText("Check tracker connection.");
                    peerListCardLayout.show(peerListCardPanel, "empty");

                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String msg = cause.getMessage() != null && !cause.getMessage().isBlank()
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    JOptionPane.showMessageDialog(
                            MainFrame.this,
                            "Could not refresh peers: " + msg,
                            "Tracker error",
                            JOptionPane.ERROR_MESSAGE
                    );
                } finally {
                    refreshing = false;
                    updateActionStates();
                }
            }
        }.execute();
    }

    public void performSearch() {
        String query = searchField.getText();
        final long currentSeq = ++searchSequence;
        searching = true;
        searchEmptyTitle.setText("Searching catalogue...");
        searchEmptySubtitle.setText("Query: \"" + query + "\"");
        searchCardLayout.show(searchCardPanel, "empty");
        updateActionStates();

        new SwingWorker<List<SearchResult>, Void>() {
            @Override
            protected List<SearchResult> doInBackground() throws Exception {
                return runtime.searchFiles(query);
            }

            @Override
            protected void done() {
                if (currentSeq != searchSequence) {
                    return;
                }
                searching = false;
                try {
                    List<SearchResult> results = get();
                    searchModel.setResults(results);
                    if (results.isEmpty()) {
                        searchEmptyTitle.setText("No matching files");
                        searchEmptySubtitle.setText("Try another keyword.");
                        searchCardLayout.show(searchCardPanel, "empty");
                    } else {
                        searchCardLayout.show(searchCardPanel, "results");
                    }
                } catch (Exception ex) {
                    searchModel.setResults(List.of());
                    searchEmptyTitle.setText("Search failed");
                    searchEmptySubtitle.setText("Check tracker connection.");
                    searchCardLayout.show(searchCardPanel, "empty");

                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(
                            MainFrame.this,
                            "Search failed: " + cause.getMessage(),
                            "Search error",
                            JOptionPane.ERROR_MESSAGE
                    );
                } finally {
                    updateSearchDetail();
                    updateActionStates();
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
        int modelRow = searchTable.convertRowIndexToModel(selectedRow);
        SearchResult result = searchModel.getResultAt(modelRow);
        if (result == null || result.providers() == null || result.providers().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No online providers available for this file.");
            return;
        }

        try {
            runtime.downloadFile(result);
            tabbedPane.setSelectedIndex(0);
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
        SwingUtilities.invokeLater(() -> {
            boolean wasEmpty = transferModel.getRowCount() == 0;
            transferModel.update(update);

            if (wasEmpty && transferModel.getRowCount() > 0) {
                transfersCardLayout.show(transfersCardPanel, "table");
            }

            int selectedViewRow = transfersTable.getSelectedRow();
            if (selectedViewRow < 0) {
                // Auto-select the first arriving transfer when none is selected
                transfersTable.setRowSelectionInterval(0, 0);
                visualizer.updateFrom(update);
            } else {
                int selectedModelRow = transfersTable.convertRowIndexToModel(selectedViewRow);
                TransferUpdate selectedUpdate = transferModel.getUpdateAt(selectedModelRow);
                if (selectedUpdate != null && update.transferId().equals(selectedUpdate.transferId())) {
                    visualizer.updateFrom(update);
                }
            }

            // Append to log
            if (logArea.getText().isEmpty()) {
                logCardLayout.show(logCardPanel, "log");
            }
            String ts = LocalTime.now().format(TIME_FORMATTER);
            logArea.append("[%s] [%s] %s (%s) %d%% %s%s\n".formatted(
                    ts, update.direction(), update.fileName(), update.formatSources(),
                    update.progressPercent(), update.status(),
                    (update.message() != null && !update.message().isBlank() ? " - " + update.message() : "")
            ));

            if (logArea.getLineCount() > 500) {
                try {
                    int endOffset = logArea.getLineEndOffset(logArea.getLineCount() - 400);
                    logArea.replaceRange("", 0, endOffset);
                } catch (Exception ignored) {
                }
            }
        });
    }

    static Color getSemanticColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    // ---------------------------------------------------------
    // Custom Renderers
    // ---------------------------------------------------------

    private static final class PeerListCellRenderer extends DefaultListCellRenderer {
        private final JPanel cellPanel = new JPanel(new BorderLayout(4, 2));
        private final JLabel nameLabel = new JLabel();
        private final JLabel endpointLabel = new JLabel();

        PeerListCellRenderer() {
            cellPanel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            nameLabel.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
            nameLabel.putClientProperty("html.disable", Boolean.TRUE);

            endpointLabel.setFont(UIManager.getFont("Label.font").deriveFont(11f));
            endpointLabel.putClientProperty("html.disable", Boolean.TRUE);

            cellPanel.add(nameLabel, BorderLayout.NORTH);
            cellPanel.add(endpointLabel, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            if (value instanceof PeerInfo peer) {
                nameLabel.setText(peer.displayName());
                String endpoint = peer.host() + ":" + peer.port();
                endpointLabel.setText(endpoint);
                cellPanel.setToolTipText("Peer ID: " + peer.peerId() + " | Address: " + endpoint);

                if (isSelected) {
                    cellPanel.setBackground(list.getSelectionBackground());
                    nameLabel.setForeground(list.getSelectionForeground());
                    endpointLabel.setForeground(list.getSelectionForeground());
                } else {
                    cellPanel.setBackground(list.getBackground());
                    nameLabel.setForeground(list.getForeground());
                    endpointLabel.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
                }
                return cellPanel;
            }
            return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        }
    }

    private static final class LeftAlignCellRenderer extends DefaultTableCellRenderer {
        LeftAlignCellRenderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
            putClientProperty("html.disable", Boolean.TRUE);
        }
    }

    private static final class RightAlignCellRenderer extends DefaultTableCellRenderer {
        RightAlignCellRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
            putClientProperty("html.disable", Boolean.TRUE);
        }
    }

    private final class ProgressCellRenderer extends DefaultTableCellRenderer {
        private final JProgressBar progressBar = new JProgressBar(0, 100);

        ProgressCellRenderer() {
            progressBar.setStringPainted(true);
            progressBar.putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            TransferUpdate update = transferModel.getUpdateAt(modelRow);
            int percent = update != null ? update.progressPercent() : 0;
            progressBar.setValue(percent);
            progressBar.setString(percent + "%");
            progressBar.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            return progressBar;
        }
    }

    private final class StatusCellRenderer extends DefaultTableCellRenderer {
        StatusCellRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            int modelRow = table.convertRowIndexToModel(row);
            TransferUpdate update = transferModel.getUpdateAt(modelRow);
            if (update != null) {
                TransferStatus status = update.status();
                setText(formatSentenceCase(status));
                String fullMessage = status + (update.message() != null && !update.message().isBlank() ? " - " + update.message() : "");
                setToolTipText("Status: " + fullMessage);

                if (!isSelected) {
                    setForeground(getStatusColor(status));
                }
            }
            return this;
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
    }
}
