package org.soundnet.flasher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelHexTest {

    private static IntelHex parse(String text) throws IOException {
        return IntelHex.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void readsASimpleImage() throws Exception {
        IntelHex hex = parse(":03000000010203F7\n:00000001FF\n");
        assertEquals(3, hex.size());
        assertArrayEquals(new byte[]{1, 2, 3}, hex.data());
    }

    @Test
    void fillsGapsWithErasedFlashValue() throws Exception {
        // Two data records with a four byte hole between them.
        IntelHex hex = parse(":02000000AABB99\n:020006001122C5\n:00000001FF\n");
        assertEquals(8, hex.size());
        byte[] data = hex.data();
        assertEquals((byte) 0xAA, data[0]);
        assertEquals((byte) 0xBB, data[1]);
        for (int i = 2; i < 6; i++) {
            assertEquals((byte) 0xFF, data[i], "gap at " + i + " should read as erased flash");
        }
        assertEquals((byte) 0x11, data[6]);
        assertEquals((byte) 0x22, data[7]);
    }

    @Test
    void rejectsABadChecksum() {
        IOException e = assertThrows(IOException.class,
                () -> parse(":03000000010203F6\n:00000001FF\n"));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
    }

    @Test
    void rejectsATruncatedFile() {
        IOException e = assertThrows(IOException.class, () -> parse(":03000000010203F7\n"));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());
    }

    @Test
    void rejectsAnEmptyImage() {
        assertThrows(IOException.class, () -> parse(":00000001FF\n"));
    }

    @Test
    void honoursExtendedLinearAddressRecords() throws Exception {
        // Type 04 sets the upper 16 bits; 0x0000 keeps everything at the bottom.
        IntelHex hex = parse(":020000040000FA\n:03000000010203F7\n:00000001FF\n");
        assertEquals(3, hex.size());
    }
}
