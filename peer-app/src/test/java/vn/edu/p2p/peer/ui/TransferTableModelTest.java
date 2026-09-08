package vn.edu.p2p.peer.ui;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.peer.transfer.TransferDirection;
import vn.edu.p2p.peer.transfer.TransferStatus;
import vn.edu.p2p.peer.transfer.TransferUpdate;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferTableModelTest {

    @Test
    void selectionPreservedOnUpdatesAndLatestValuesExposed() throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
            TransferTableModel model = new TransferTableModel();
            JTable table = new JTable(model);

            TransferUpdate txA1 = new TransferUpdate(
                    "tx-A", "fileA.txt", "peer1",
                    TransferDirection.SEND, TransferStatus.PREPARING,
                    0, 1000, 0.0, ""
            );
            TransferUpdate txB1 = new TransferUpdate(
                    "tx-B", "fileB.txt", "peer2",
                    TransferDirection.RECEIVE, TransferStatus.TRANSFERRING,
                    100, 2000, 50.0, ""
            );

            model.update(txA1);
            model.update(txB1);

            table.setRowSelectionInterval(0, 0);
            assertEquals(0, table.getSelectedRow(), "Row 0 should be selected");

            // Update B
            TransferUpdate txB2 = new TransferUpdate(
                    "tx-B", "fileB.txt", "peer2",
                    TransferDirection.RECEIVE, TransferStatus.TRANSFERRING,
                    500, 2000, 100.0, ""
            );
            model.update(txB2);

            // Append C
            TransferUpdate txC1 = new TransferUpdate(
                    "tx-C", "fileC.txt", "peer3",
                    TransferDirection.SEND, TransferStatus.COMPLETED,
                    3000, 3000, 0.0, ""
            );
            model.update(txC1);

            // Update A
            TransferUpdate txA2 = new TransferUpdate(
                    "tx-A", "fileA.txt", "peer1",
                    TransferDirection.SEND, TransferStatus.TRANSFERRING,
                    500, 1000, 250.0, ""
            );
            model.update(txA2);

            assertEquals(0, table.getSelectedRow(), "Selection should remain on transfer A without reselection");
            TransferUpdate selectedUpdate = model.getUpdateAt(table.getSelectedRow());
            assertEquals("tx-A", selectedUpdate.transferId());
            assertEquals(500, selectedUpdate.bytesTransferred());
            assertEquals(TransferStatus.TRANSFERRING, selectedUpdate.status());
        });
    }
}
