package vn.edu.p2p.peer.ui;

import vn.edu.p2p.peer.transfer.TransferUpdate;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TransferTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            "Direction", "File", "Peer / Sources", "Progress", "Speed", "ETA", "Status"
    };

    private final Map<String, TransferUpdate> byId = new LinkedHashMap<>();

    public void update(TransferUpdate update) {
        byId.put(update.transferId(), update);
        fireTableDataChanged();
    }

    public List<TransferUpdate> rows() {
        return new ArrayList<>(byId.values());
    }

    public TransferUpdate getUpdateAt(int rowIndex) {
        List<TransferUpdate> list = rows();
        if (rowIndex >= 0 && rowIndex < list.size()) {
            return list.get(rowIndex);
        }
        return null;
    }

    @Override
    public int getRowCount() {
        return byId.size();
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
        List<TransferUpdate> list = rows();
        if (rowIndex < 0 || rowIndex >= list.size()) {
            return null;
        }
        TransferUpdate row = list.get(rowIndex);
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
