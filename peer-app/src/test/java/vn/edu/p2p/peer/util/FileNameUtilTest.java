package vn.edu.p2p.peer.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileNameUtilTest {

    @Test
    void testSafeBaseNameTraversalsAndDots() {
        assertThrows(IllegalArgumentException.class, () -> FileNameUtil.safeBaseName("..."));
        assertThrows(IllegalArgumentException.class, () -> FileNameUtil.safeBaseName(".. "));
        assertThrows(IllegalArgumentException.class, () -> FileNameUtil.safeBaseName("."));
        assertThrows(IllegalArgumentException.class, () -> FileNameUtil.safeBaseName(".."));
        assertThrows(IllegalArgumentException.class, () -> FileNameUtil.safeBaseName("   "));
    }

    @Test
    void testSafeBaseNameTrailingDotsAndSpaces() {
        assertEquals("report.txt", FileNameUtil.safeBaseName("report.txt. "));
        assertEquals("report.txt", FileNameUtil.safeBaseName("report.txt..."));
        assertEquals("report.txt", FileNameUtil.safeBaseName("report.txt   "));
    }

    @Test
    void testSafeBaseNameWindowsDeviceNames() {
        assertEquals("_CON", FileNameUtil.safeBaseName("CON"));
        assertEquals("_con.txt", FileNameUtil.safeBaseName("con.txt"));
        assertEquals("_PRN.doc", FileNameUtil.safeBaseName("PRN.doc"));
        assertEquals("_AUX.tar.gz", FileNameUtil.safeBaseName("AUX.tar.gz"));
        assertEquals("_nul.bin", FileNameUtil.safeBaseName("nul.bin"));
        assertEquals("_com1.dat", FileNameUtil.safeBaseName("com1.dat"));
        assertEquals("_lpt9.log", FileNameUtil.safeBaseName("lpt9.log"));
    }

    @Test
    void testSafeBaseNameSlashesAndWindowsForbiddenCharacters() {
        assertEquals("file.txt", FileNameUtil.safeBaseName("dir/subdir/file.txt"));
        assertEquals("file.txt", FileNameUtil.safeBaseName("C:\\Windows\\system32\\file.txt"));
        assertEquals("b_a_d_name_.txt", FileNameUtil.safeBaseName("b:a*d?name|.txt"));
    }

    @Test
    void testSafeBaseNameUnicodePreserved() {
        assertEquals("tài_liệu.txt", FileNameUtil.safeBaseName("tài_liệu.txt"));
        assertEquals("ボブ.png", FileNameUtil.safeBaseName("path/to/ボブ.png"));
    }
}
