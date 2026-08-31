package org.soundnet.flasher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Avr109ProgrammerTest {

    @Test
    void acceptsTheCaterinaIdentifier() {
        assertDoesNotThrow(() -> Avr109Programmer.requireBootloader("CATERIN"));
    }

    /**
     * The real failure this guard exists for: a sensor running its own firmware
     * answers 'S' with the first seven bytes of "Connection to PC Established",
     * and AVR109 has no handshake that would otherwise catch it.
     */
    @Test
    void rejectsARunningSensor() {
        FlashException e = assertThrows(FlashException.class,
                () -> Avr109Programmer.requireBootloader("Connect"));
        assertTrue(e.getMessage().contains("still running its normal firmware"), e.getMessage());
        assertTrue(e.getMessage().contains("Connect"), "should quote what it actually got");
    }

    /**
     * Whatever comes back is untrusted bytes off a serial line, not text. Control
     * characters must not be pasted raw into a dialog.
     */
    @Test
    void sanitisesUnprintableReplies() {
        FlashException e = assertThrows(FlashException.class,
                () -> Avr109Programmer.requireBootloader("\u0001\u0002abcde"));
        assertTrue(e.getMessage().contains("??abcde"), e.getMessage());
    }

    @Test
    void applicationLimitStopsShortOfTheBootloader() {
        // The Caterina bootloader lives above 0x7000 and must survive an update.
        assertEquals(0x7000, Avr109Programmer.APPLICATION_MAX_BYTES);
    }
}
