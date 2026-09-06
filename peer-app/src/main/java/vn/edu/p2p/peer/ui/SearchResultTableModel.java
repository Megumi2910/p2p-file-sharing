package vn.edu.p2p.peer.ui;

import vn.edu.p2p.common.model.PeerInfo;
import vn.edu.p2p.common.model.SearchResult;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class SearchResultTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            "File Name", "Size", "Chunks", "File SHA-256", "Providers"
    };

    private final List<SearchResult> results = new ArrayList<>();

    @Override
    public int getRowCount() {
        return results.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= results.size()) {
            return null;
        }
        SearchResult r = results.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> r.file().fileName();
            case 1 -> humanBytes(r.file().fileSize());
            case 2 -> r.file().totalChunks();
            case 3 -> r.file().fileId();
            case 4 -> r.providers().stream().map(PeerInfo::displayName).collect(Collectors.joining(", "));
            default -> null;
        };
    }

    public SearchResult getResultAt(int row) {
        if (row >= 0 && row < results.size()) {
            return results.get(row);
        }
        return null;
    }

    public void setResults(List<SearchResult> newResults) {
        results.clear();
        if (newResults != null) {
            results.addAll(newResults);
        }
        fireTableDataChanged();
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return "%.1f KiB".formatted(kib);
        double mib = kib / 1024.0;
        if (mib < 1024) return "%.1f MiB".formatted(mib);
        return "%.2f GiB".formatted(mib / 1024.0);
    }
}
