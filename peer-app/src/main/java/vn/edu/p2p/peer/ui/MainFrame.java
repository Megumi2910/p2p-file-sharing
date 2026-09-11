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
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.border.Border;
import com.formdev.flatlaf.util.UIScale;
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
    private static JTextArea createWrappingLabel(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.putClientProperty("FlatLaf.styleClass", "muted");
        area.putClientProperty("html.disable", Boolean.TRUE);
        return area;
    }


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
    private final JTextArea peerEmptySubtitle = createWrappingLabel("Start another peer, then refresh.");
    private final JTextArea peerStatusArea = createWrappingLabel("Connecting to tracker...");
    private final JButton refreshButton = new JButton("Refresh peers");
    private final JButton sendButton = new JButton("Send file...");
    private final JPopupMenu peerPopupMenu = new JPopupMenu();
    private final JMenuItem sendMenuItem = new JMenuItem("Send file...");
    private JSplitPane mainSplit;

    // Sharing strip controls
    private final JTextField sharedDirPathField = new JTextField();
    private final JTextArea sharingStatusArea = createWrappingLabel("Initializing shared folder...");
    private final JButton rescanButton = new JButton("Rescan shared folder");
    private volatile boolean rescanning = false;
    private final JLabel searchHeaderLabel = new JLabel();

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
    private final JTextArea searchEmptySubtitle = createWrappingLabel("Enter a filename to search the catalogue.");
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
    private String lastSuccessfulQuery = null;
    private long lastSeenSnapshotRevision = -1L;
    private PeerRuntime.TrackerState lastObservedTrackerState = null;
    private final Consumer<PeerRuntime.RuntimeSnapshot> snapshotListener;
    private boolean ignoreThemeEvents = false;
    private boolean stopped = false;
    private boolean restartFrozen = false;
    private PeerListCellRenderer peerRenderer;
    private final Runnable themeListener = this::onThemeChanged;
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
        this.snapshotListener = snapshot -> {
            SwingUtilities.invokeLater(() -> onRuntimeSnapshot(snapshot));
        };
        buildUi();
        runtime.addStateListener(snapshotListener);
        wireUpdateListener();
        DesktopTheme.addThemeChangeListener(themeListener);
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

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBorder(BorderFactory.createEmptyBorder(
                UIScale.scale(12), UIScale.scale(16), UIScale.scale(16), UIScale.scale(16)
        ));

        // 1. Header
        root.add(buildHeader(), BorderLayout.NORTH);

        // 2. Main Workspace split pane
        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildPeersPanel(), buildTabsPanel());
        mainSplit.setResizeWeight(0.0);
        mainSplit.setContinuousLayout(true);
        root.add(mainSplit, BorderLayout.CENTER);

        setContentPane(root);

        wireActions();
        updateActionStates();

        DesktopLayout.fitWindow(this, new Dimension(1200, 760), new Dimension(980, 620));
        setLocationRelativeTo(null);

        int btnW = Math.max(sendButton.getPreferredSize().width, refreshButton.getPreferredSize().width);
        int sidebarInitialWidth = Math.max(UIScale.scale(260), btnW + UIScale.scale(24));
        mainSplit.setDividerLocation(sidebarInitialWidth);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new GridBagLayout());
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, UIScale.scale(8), 0));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(UIScale.scale(2), UIScale.scale(4), UIScale.scale(2), UIScale.scale(4));

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
        titlePanel.add(Box.createVerticalStrut(UIScale.scale(2)));
        titlePanel.add(subtitleLabel);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        header.add(titlePanel, gbc);

        // Right: Settings button + Theme selector
        JPanel controlsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints cgbc = new GridBagConstraints();
        cgbc.insets = new Insets(0, UIScale.scale(4), 0, UIScale.scale(4));
        cgbc.fill = GridBagConstraints.NONE;
        cgbc.anchor = GridBagConstraints.EAST;

        settingsButton.putClientProperty("html.disable", Boolean.TRUE);
        settingsButton.addActionListener(e -> openSettingsDialog());
        cgbc.gridx = 0;
        controlsPanel.add(settingsButton, cgbc);

        JLabel themeLabel = new JLabel("Theme:");
        themeLabel.setLabelFor(themeCombo);
        themeLabel.putClientProperty("html.disable", Boolean.TRUE);
        cgbc.gridx = 1;
        controlsPanel.add(themeLabel, cgbc);

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
        cgbc.gridx = 2;
        controlsPanel.add(themeCombo, cgbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        header.add(controlsPanel, gbc);

        // Updates row below
        JPanel updateToolBar = new JPanel(new GridBagLayout());
        updateToolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), 0, UIScale.scale(2), 0));
        GridBagConstraints ugbc = new GridBagConstraints();
        ugbc.insets = new Insets(0, 0, 0, UIScale.scale(8));
        ugbc.gridx = 0;
        ugbc.gridy = 0;
        ugbc.weightx = 0.0;
        ugbc.fill = GridBagConstraints.NONE;
        ugbc.anchor = GridBagConstraints.WEST;

        updateButton.putClientProperty("html.disable", Boolean.TRUE);
        updateButton.addActionListener(e -> openUpdateDialog());
        updateToolBar.add(updateButton, ugbc);

        updateBadgeLabel.putClientProperty("html.disable", Boolean.TRUE);
        updateBadgeLabel.setVisible(false);
        ugbc.gridx = 1;
        ugbc.weightx = 1.0;
        ugbc.fill = GridBagConstraints.HORIZONTAL;
        updateToolBar.add(updateBadgeLabel, ugbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        header.add(updateToolBar, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        header.add(new JSeparator(SwingConstants.HORIZONTAL), gbc);

        return header;
    }

    private JPanel buildPeersPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, UIScale.scale(8)));
        panel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), 0, 0, UIScale.scale(8)));

        // Header caption
        JPanel topHeader = new JPanel(new BorderLayout(0, UIScale.scale(2)));
        JLabel title = new JLabel("Peers");
        title.putClientProperty("FlatLaf.styleClass", "h4");
        title.putClientProperty("html.disable", Boolean.TRUE);

        peerStatusArea.setEditable(false);
        peerStatusArea.setFocusable(false);
        peerStatusArea.setOpaque(false);
        peerStatusArea.setLineWrap(true);
        peerStatusArea.setWrapStyleWord(true);
        peerStatusArea.putClientProperty("FlatLaf.styleClass", "muted");
        peerStatusArea.putClientProperty("html.disable", Boolean.TRUE);

        topHeader.add(title, BorderLayout.NORTH);
        topHeader.add(peerStatusArea, BorderLayout.SOUTH);
        panel.add(topHeader, BorderLayout.NORTH);

        // Center: Peer list with CardLayout (list vs empty)
        peerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        peerRenderer = new PeerListCellRenderer();
        peerList.setCellRenderer(peerRenderer);
        peerList.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateActionStates();
            }
        });
        // Double-click to send file directly, right-click context menu
        peerList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int index = peerList.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        Rectangle bounds = peerList.getCellBounds(index, index);
                        if (bounds != null && bounds.contains(e.getPoint())) {
                            peerList.setSelectedIndex(index);
                            if (canSend()) {
                                chooseAndSend();
                            }
                        }
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int index = peerList.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        Rectangle bounds = peerList.getCellBounds(index, index);
                        if (bounds != null && bounds.contains(e.getPoint())) {
                            peerList.setSelectedIndex(index);
                            updateActionStates();
                            if (canOperate()) {
                                peerPopupMenu.show(peerList, e.getX(), e.getY());
                            }
                        }
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(peerList);
        scrollPane.setBorder(BorderFactory.createLineBorder(getSemanticColor("P2P.borderColor", Color.LIGHT_GRAY)));
        peerListCardPanel.add(scrollPane, "list");

        JPanel emptyPanel = new JPanel();
        emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
        emptyPanel.setBorder(BorderFactory.createEmptyBorder(
                UIScale.scale(24), UIScale.scale(12), UIScale.scale(12), UIScale.scale(12)
        ));
        peerEmptyTitle.putClientProperty("FlatLaf.styleClass", "h4");
        peerEmptyTitle.putClientProperty("html.disable", Boolean.TRUE);
        peerEmptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        peerEmptySubtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyPanel.add(peerEmptyTitle);
        emptyPanel.add(Box.createVerticalStrut(UIScale.scale(4)));
        emptyPanel.add(peerEmptySubtitle);
        peerListCardPanel.add(emptyPanel, "empty");

        peerListCardLayout.show(peerListCardPanel, "empty");
        panel.add(peerListCardPanel, BorderLayout.CENTER);

        // Buttons
        JPanel buttonsPanel = new JPanel(new GridLayout(2, 1, 0, UIScale.scale(8)));
        sendButton.putClientProperty("html.disable", Boolean.TRUE);
        sendButton.putClientProperty("FlatLaf.styleClass", "primary");
        sendButton.setToolTipText("Select an online peer to send a file");

        refreshButton.putClientProperty("html.disable", Boolean.TRUE);
        refreshButton.setToolTipText("Refresh the list of active peers from the tracker");

        buttonsPanel.add(sendButton);
        buttonsPanel.add(refreshButton);
        panel.add(buttonsPanel, BorderLayout.SOUTH);

        int btnW = Math.max(sendButton.getPreferredSize().width, refreshButton.getPreferredSize().width);
        int sidebarInitialWidth = Math.max(UIScale.scale(260), btnW + UIScale.scale(24));
        int sidebarMinWidth = Math.max(UIScale.scale(200), btnW + UIScale.scale(16));
        panel.setMinimumSize(new Dimension(sidebarMinWidth, UIScale.scale(200)));
        panel.setPreferredSize(new Dimension(sidebarInitialWidth, UIScale.scale(400)));

        return panel;
    }

    private JPanel buildTabsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 0));

        tabbedPane.addTab("Transfers", buildTransfersTab());
        tabbedPane.addTab("Catalogue Search", buildSearchTab());
        tabbedPane.addTab("Activity Log", buildLogTab());

        updateTableRowHeights();

        panel.add(tabbedPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildTransfersTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

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

        JTextArea emptySubtitle = createWrappingLabel("Select a peer and choose Send file, or download from Catalogue Search.");
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

        // Top container: Sharing strip + Search toolbar
        JPanel topContainer = new JPanel(new GridBagLayout());
        topContainer.setBorder(BorderFactory.createEmptyBorder(0, 0, UIScale.scale(4), 0));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(UIScale.scale(2), UIScale.scale(4), UIScale.scale(2), UIScale.scale(4));

        // Row 0: Shared folder path
        JLabel pathLabel = new JLabel("Shared folder:");
        pathLabel.putClientProperty("html.disable", Boolean.TRUE);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        topContainer.add(pathLabel, gbc);

        sharedDirPathField.setText(runtime.config().sharedDir().toAbsolutePath().normalize().toString());
        sharedDirPathField.setEditable(false);
        sharedDirPathField.putClientProperty("html.disable", Boolean.TRUE);
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        topContainer.add(sharedDirPathField, gbc);

        // Row 1: Sharing status (full width)
        sharingStatusArea.setEditable(false);
        sharingStatusArea.setFocusable(false);
        sharingStatusArea.setOpaque(false);
        sharingStatusArea.setLineWrap(true);
        sharingStatusArea.setWrapStyleWord(true);
        sharingStatusArea.putClientProperty("FlatLaf.styleClass", "muted");
        sharingStatusArea.putClientProperty("html.disable", Boolean.TRUE);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        topContainer.add(sharingStatusArea, gbc);

        // Row 2: Rescan button + explanation
        rescanButton.putClientProperty("html.disable", Boolean.TRUE);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        topContainer.add(rescanButton, gbc);

        JLabel autoShareHint = new JLabel("Stable top-level files are shared automatically.");
        autoShareHint.putClientProperty("FlatLaf.styleClass", "muted");
        autoShareHint.putClientProperty("html.disable", Boolean.TRUE);
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        topContainer.add(autoShareHint, gbc);

        // Row 3: Separator
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        topContainer.add(new JSeparator(SwingConstants.HORIZONTAL), gbc);

        // Row 4: Keyword label + Search field
        JLabel kwLabel = new JLabel("Keyword:");
        kwLabel.setLabelFor(searchField);
        kwLabel.putClientProperty("html.disable", Boolean.TRUE);
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        topContainer.add(kwLabel, gbc);

        searchField.putClientProperty("html.disable", Boolean.TRUE);
        gbc.gridx = 1;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        topContainer.add(searchField, gbc);

        // Row 5: Action buttons (Search Catalogue & Download Selected) + searchHeaderLabel
        JPanel searchButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(4), 0));
        searchButton.putClientProperty("html.disable", Boolean.TRUE);
        downloadButton.putClientProperty("html.disable", Boolean.TRUE);
        downloadButton.putClientProperty("FlatLaf.styleClass", "primary");
        searchButtonsPanel.add(searchButton);
        searchButtonsPanel.add(downloadButton);

        searchHeaderLabel.putClientProperty("html.disable", Boolean.TRUE);
        searchHeaderLabel.setVisible(false);
        searchButtonsPanel.add(searchHeaderLabel);

        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        topContainer.add(searchButtonsPanel, gbc);

        panel.add(topContainer, BorderLayout.NORTH);

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
        colModel.getColumn(3).setCellRenderer(new MonospacedCellRenderer());
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
        logArea.putClientProperty("FlatLaf.styleClass", "monospaced");
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

        JTextArea logEmptySubtitle = createWrappingLabel("Events appear here while this application is running.");
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
        sendMenuItem.putClientProperty("html.disable", Boolean.TRUE);
        sendMenuItem.addActionListener(e -> chooseAndSend());
        peerPopupMenu.add(sendMenuItem);
        searchButton.addActionListener(e -> performSearch());
        searchField.addActionListener(e -> performSearch());
        downloadButton.addActionListener(e -> downloadSelected());
        rescanButton.addActionListener(e -> onRescanSharedFolder());

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void check() {
                if (searchHeaderLabel.isVisible()) {
                    String currentText = searchField.getText().trim();
                    if (!currentText.equals(lastSuccessfulQuery != null ? lastSuccessfulQuery.trim() : "")) {
                        searchHeaderLabel.setText("Last results for \"" + lastSuccessfulQuery + "\" — does not match edited keyword");
                    } else {
                        searchHeaderLabel.setText("Last results for \"" + lastSuccessfulQuery + "\" — tracker unavailable; availability unverified");
                    }
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                check();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                check();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                check();
            }
        });
    }

    private void appendActivityLog(String message) {
        SwingUtilities.invokeLater(() -> {
            if (logArea.getText().isEmpty()) {
                logCardLayout.show(logCardPanel, "log");
            }
            String ts = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.append("[%s] %s\n".formatted(ts, message));
            if (logArea.getLineCount() > 500) {
                try {
                    int endOffset = logArea.getLineEndOffset(logArea.getLineCount() - 400);
                    logArea.replaceRange("", 0, endOffset);
                } catch (Exception ignored) {
                }
            }
        });
    }
    private void onRescanSharedFolder() {
        if (rescanning || !canOperate()) return;
        rescanning = true;
        sharingStatusArea.setText("Scanning shared folder...");
        updateActionStates();

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                runtime.refreshSharedFiles();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String msg = cause.getMessage() != null && !cause.getMessage().isBlank()
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    sharingStatusArea.setText("Rescan failed: " + msg);
                    sharingStatusArea.setForeground(getSemanticColor("P2P.error.foreground", Color.RED));
                    appendActivityLog("Local shared folder rescan failed: " + msg);
                } finally {
                    rescanning = false;
                    updateActionStates();
                }
            }
        }.execute();
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
            this.stopped = true;
            subtitleLabel.setText("Stopped — relaunch manually");
            subtitleLabel.setForeground(getSemanticColor("P2P.error.foreground", Color.RED));
            updateButton.setEnabled(false);
            settingsButton.setEnabled(false);
            updateActionStates();

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
            this.restartFrozen = freeze;
            updateButton.setEnabled(!freeze && !stopped);
            settingsButton.setEnabled(!freeze && !stopped);
            if (settingsDialog != null && settingsDialog.isDisplayable()) {
                settingsDialog.freezeEditing(freeze);
            }
            updateActionStates();
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
        updateHeaderSubtitle(runtime.snapshot());
        updateActionStates();
    }

    private boolean canOperate() {
        return !starting && !stopped && !restartFrozen;
    }

    private boolean canSend() {
        return canOperate() && peerList.getSelectedValue() != null;
    }

    private void updateActionStates() {
        boolean operate = canOperate();
        boolean trackerConnected = runtime.snapshot().trackerState() == PeerRuntime.TrackerState.CONNECTED;

        refreshButton.setEnabled(operate && !refreshing);
        boolean sendActive = canSend();
        sendButton.setEnabled(sendActive);
        sendMenuItem.setEnabled(sendActive);

        rescanButton.setEnabled(!stopped && !restartFrozen && !starting && !rescanning);

        searchField.setEnabled(operate && trackerConnected);
        searchButton.setEnabled(operate && !searching && trackerConnected);
        downloadButton.setEnabled(operate && !searching && hasUsableSearchResultSelected());

        settingsButton.setEnabled(!stopped && !restartFrozen);
        updateButton.setEnabled(!stopped && !restartFrozen);
    }

    private static String formatTime(Instant instant) {
        if (instant == null) return "recently";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        return formatter.format(instant);
    }

    private void updateHeaderSubtitle(PeerRuntime.RuntimeSnapshot snap) {
        if (stopped) {
            subtitleLabel.setText("Stopped — relaunch manually");
            subtitleLabel.setForeground(getSemanticColor("P2P.error.foreground", Color.RED));
            return;
        }
        if (restartFrozen) {
            subtitleLabel.setText("Peer: " + runtime.config().displayName() + " / Restart in progress...");
            subtitleLabel.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
            return;
        }
        if (starting) {
            subtitleLabel.setText("Peer: " + runtime.config().displayName() + " / Starting...");
            subtitleLabel.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
            return;
        }

        String displayName = runtime.config().displayName();
        switch (snap.trackerState()) {
            case CONNECTED -> {
                subtitleLabel.setText("Peer: " + displayName + " / Tracker connected");
                subtitleLabel.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
            }
            case RECONNECTING -> {
                subtitleLabel.setText("Peer: " + displayName + " / Reconnecting to tracker...");
                subtitleLabel.setForeground(getSemanticColor("P2P.warning.foreground", new Color(180, 100, 0)));
            }
            case OFFLINE, CLOSED -> {
                subtitleLabel.setText("Peer: " + displayName + " / Tracker unavailable — direct transfers available");
                subtitleLabel.setForeground(getSemanticColor("P2P.warning.foreground", new Color(180, 100, 0)));
            }
        }
    }

    private void onRuntimeSnapshot(PeerRuntime.RuntimeSnapshot snap) {
        if (snap.revision() < lastSeenSnapshotRevision) {
            return;
        }
        lastSeenSnapshotRevision = snap.revision();

        if (lastObservedTrackerState != snap.trackerState()) {
            if (lastObservedTrackerState != null) {
                appendActivityLog("Tracker state: " + snap.trackerState() + " (" + snap.trackerDetail() + ")");
            }
            lastObservedTrackerState = snap.trackerState();
        }
        if (snap.trackerState() == PeerRuntime.TrackerState.CONNECTED) {
            String timeStr = snap.peersUpdatedAt() != null ? formatTime(snap.peersUpdatedAt()) : "recently";
            peerStatusArea.setText("Last refreshed " + timeStr);
            peerStatusArea.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));

            if (!refreshing) {
                String selectedId = peerList.getSelectedValue() != null ? peerList.getSelectedValue().peerId() : null;
                peerModel.clear();
                PeerInfo toReselect = null;
                for (PeerInfo peer : snap.peers()) {
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
            }
        } else {
            String detail = snap.trackerDetail().isBlank() ? "Tracker unavailable" : snap.trackerDetail();
            if (!snap.peers().isEmpty()) {
                String timeStr = snap.peersUpdatedAt() != null ? formatTime(snap.peersUpdatedAt()) : "earlier";
                peerStatusArea.setText("Tracker unavailable (" + detail + ") — showing last known peers from " + timeStr + ". Direct sends may still work.");
            } else {
                peerStatusArea.setText("Tracker unavailable (" + detail + ") — no previously discovered peers. Retrying automatically.");
            }
            peerStatusArea.setForeground(getSemanticColor("P2P.warning.foreground", new Color(180, 100, 0)));
        }

        sharingStatusArea.setText(snap.sharingDetail());
        if (snap.sharingState() == PeerRuntime.SharingState.ERROR) {
            sharingStatusArea.setForeground(getSemanticColor("P2P.error.foreground", Color.RED));
        } else if (snap.sharingState() == PeerRuntime.SharingState.PENDING_PUBLISH) {
            sharingStatusArea.setForeground(getSemanticColor("P2P.warning.foreground", new Color(180, 100, 0)));
        } else {
            sharingStatusArea.setForeground(getSemanticColor("P2P.muted.foreground", Color.GRAY));
        }

        if (snap.trackerState() != PeerRuntime.TrackerState.CONNECTED) {
            if (lastSuccessfulQuery != null && searchModel.getRowCount() > 0) {
                searchHeaderLabel.setText("Last results for \"" + lastSuccessfulQuery + "\" — tracker unavailable; availability unverified");
                searchHeaderLabel.setForeground(getSemanticColor("P2P.warning.foreground", new Color(180, 100, 0)));
                searchHeaderLabel.setVisible(true);
            }
        } else {
            searchHeaderLabel.setVisible(false);
        }

        updateActionStates();
    }

    private void updateTableRowHeights() {
        Font font = UIManager.getFont("Table.font");
        if (font == null) {
            font = transfersTable.getFont();
        }
        int fontH = font != null ? transfersTable.getFontMetrics(font).getHeight() : 16;
        int progH = UIScale.scale(16);
        int contentH = Math.max(fontH, progH);
        int computedH = Math.max(UIScale.scale(36), contentH + UIScale.scale(12));
        transfersTable.setRowHeight(computedH);
        searchTable.setRowHeight(computedH);
    }

    private void onThemeChanged() {
        updateTableRowHeights();

        if (peerRenderer != null) {
            peerRenderer.refreshTypography();
        }
        peerList.setCellRenderer(peerRenderer);
        peerList.revalidate();
        peerList.repaint();

        if (visualizer != null) {
            visualizer.refreshTheme();
        }

        if (peerPopupMenu != null) {
            SwingUtilities.updateComponentTreeUI(peerPopupMenu);
        }

        revalidate();
        repaint();
    }

    @Override
    public void dispose() {
        ++searchSequence;
        runtime.removeStateListener(snapshotListener);
        DesktopTheme.removeThemeChangeListener(themeListener);
        super.dispose();
    }

    public boolean confirmDiscardUnsavedSettings() {
        if (settingsDialog != null && settingsDialog.isDisplayable() && settingsDialog.isVisible()) {
            return settingsDialog.confirmDiscardChanges();
        }
        return true;
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
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String msg = cause.getMessage() != null && !cause.getMessage().isBlank()
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    appendActivityLog("Peer refresh failed: " + msg);
                    runtime.requestImmediateRecovery();
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
                    lastSuccessfulQuery = query;
                    searchModel.setResults(results);
                    if (results.isEmpty()) {
                        searchEmptySubtitle.setText("Try another keyword.");
                        searchCardLayout.show(searchCardPanel, "empty");
                    } else {
                        searchCardLayout.show(searchCardPanel, "results");
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String msg = cause.getMessage() != null && !cause.getMessage().isBlank()
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    appendActivityLog("Catalogue search failed: " + msg);

                    if (searchModel.getRowCount() > 0 && lastSuccessfulQuery != null) {
                        searchHeaderLabel.setText("Last results for \"" + lastSuccessfulQuery + "\" — tracker unavailable; availability unverified");
                        searchHeaderLabel.setForeground(getSemanticColor("P2P.warning.foreground", new Color(180, 100, 0)));
                        searchHeaderLabel.setVisible(true);
                        searchCardLayout.show(searchCardPanel, "results");
                    } else {
                        searchEmptyTitle.setText("Search failed");
                        searchEmptySubtitle.setText("Tracker unavailable (" + msg + ")");
                        searchCardLayout.show(searchCardPanel, "empty");
                    }
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
        if (!canSend()) {
            return;
        }
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

    private static final class MonospacedCellRenderer extends DefaultTableCellRenderer {
        MonospacedCellRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            Font mono = UIManager.getFont("monospaced.font");
            if (mono != null) {
                setFont(mono);
            }
            return this;
        }
    }

    // ---------------------------------------------------------
    // Custom Renderers
    // ---------------------------------------------------------

    private static final class PeerListCellRenderer extends DefaultListCellRenderer {
        private final JPanel cellPanel = new JPanel(new GridBagLayout());
        private final JLabel nameLabel = new JLabel();
        private final JLabel endpointLabel = new JLabel();

        PeerListCellRenderer() {
            refreshTypography();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(0, 0, UIScale.scale(4), 0);
            cellPanel.add(nameLabel, gbc);

            gbc.gridy = 1;
            gbc.insets = new Insets(0, 0, 0, 0);
            cellPanel.add(endpointLabel, gbc);
        }

        void refreshTypography() {
            Font base = UIManager.getFont("Label.font");
            if (base != null) {
                nameLabel.setFont(base.deriveFont(Font.BOLD));
                endpointLabel.setFont(base);
            }
            nameLabel.putClientProperty("FlatLaf.styleClass", "h4");
            nameLabel.putClientProperty("html.disable", Boolean.TRUE);
            endpointLabel.putClientProperty("FlatLaf.styleClass", "muted");
            endpointLabel.putClientProperty("html.disable", Boolean.TRUE);
            cellPanel.setBorder(BorderFactory.createEmptyBorder(
                    UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10)
            ));
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

                if (cellHasFocus) {
                    Border focusBorder = UIManager.getBorder("List.focusCellHighlightBorder");
                    if (focusBorder != null) {
                        cellPanel.setBorder(BorderFactory.createCompoundBorder(
                                focusBorder,
                                BorderFactory.createEmptyBorder(
                                        UIScale.scale(7), UIScale.scale(9), UIScale.scale(7), UIScale.scale(9)
                                )
                        ));
                    } else {
                        cellPanel.setBorder(BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(getSemanticColor("P2P.accent.foreground", Color.BLUE), 1),
                                BorderFactory.createEmptyBorder(
                                        UIScale.scale(7), UIScale.scale(9), UIScale.scale(7), UIScale.scale(9)
                                )
                        ));
                    }
                } else {
                    cellPanel.setBorder(BorderFactory.createEmptyBorder(
                            UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10)
                    ));
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
