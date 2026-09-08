package vn.edu.p2p.peer.ui;

import vn.edu.p2p.peer.transfer.TransferUpdate;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TransferTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            "Direction", "File", "Peer / Sources", "Progress", "Speed", "ETA", "Status"
    };

    private final List<TransferUpdate> rows = new ArrayList<>();
    private final Map<String, Integer> rowIndices = new HashMap<>();

    public void update(TransferUpdate update) {
        String id = update.transferId();
        Integer index = rowIndices.get(id);
        if (index != null) {
            rows.set(index, update);
            fireTableRowsUpdated(index, index);
        } else {
            int newIndex = rows.size();
            rows.add(update);
            rowIndices.put(id, newIndex);
            fireTableRowsInserted(newIndex, newIndex);
        }
    }

    public TransferUpdate getUpdateAt(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < rows.size()) {
            return rows.get(rowIndex);
        }
        return null;
    }

    @Override
    public int getRowCount() {
        return rows.size();
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
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return null;
        }
        TransferUpdate row = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.direction();
            case 1 -> row.fileName();
            case 2 -> row.formatSources();
            case 3 -> row.progressPercent() + "%";
            case 4 -> row.formatSpeed();
            case 5 -> row.formatEta();
            case 6 -> row.status() + (row.message() == null || row.message().isBlank() ? "" : " - " + row.message());
            default -> "";
        };
    }
}
