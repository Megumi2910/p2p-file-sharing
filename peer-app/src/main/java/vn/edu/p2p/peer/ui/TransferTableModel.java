package vn.edu.p2p.peer.ui;

import vn.edu.p2p.peer.transfer.TransferUpdate;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TransferTableModel extends AbstractTableModel {
    private final String[] columns = {"Direction", "File", "Peer", "Progress", "Speed", "Status"};
    private final Map<String, TransferUpdate> byId = new LinkedHashMap<>();

    public void update(TransferUpdate update) {
        byId.put(update.transferId(), update);
        fireTableDataChanged();
    }

    private List<TransferUpdate> rows() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public int getRowCount() {
        return byId.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        TransferUpdate row = rows().get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.direction();
            case 1 -> row.fileName();
            case 2 -> row.peerName();
            case 3 -> row.progressPercent() + "%";
            case 4 -> formatSpeed(row.bytesPerSecond());
            case 5 -> row.status() + (row.message() == null ? "" : " - " + row.message());
            default -> "";
        };
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond <= 0) return "-";
        double mib = bytesPerSecond / 1024.0 / 1024.0;
        return "%.2f MiB/s".formatted(mib);
    }
}
