package org.soundnet.flasher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the USB product ids.
 *
 * <p>These were once the wrong way round, which made the updater treat a running
 * sensor as a bootloader: it skipped the 1200-baud reset and spoke AVR109 at the
 * live firmware, which replied with the opening bytes of "Connection to PC
 * Established". The values come from SparkFun's boards.txt, where
 * {@code build.pid} is the id compiled into the sketch and {@code build.pid.0}
 * is the bootloader's.
 */
class PortInfoTest {

    private static PortInfo port(int pid) {
        return new PortInfo("COM3", "USB Serial Device", PortInfo.SPARKFUN_VID, pid);
    }

    @Test
    void sketchProductIdsAreTheSensor() {
        for (int pid : new int[]{0x9204, 0x9206}) {
            assertTrue(port(pid).isSensor(),
                    () -> String.format("0x%04X is build.pid - a running sensor", pid));
            assertFalse(port(pid).isBootloader(),
                    () -> String.format("0x%04X must not be taken for a bootloader", pid));
        }
    }

    @Test
    void bootloaderProductIdsAreTheBootloader() {
        for (int pid : new int[]{0x9203, 0x9205}) {
            assertTrue(port(pid).isBootloader(),
                    () -> String.format("0x%04X is build.pid.0 - the Caterina bootloader", pid));
            assertFalse(port(pid).isSensor(),
                    () -> String.format("0x%04X must not be taken for a running sensor", pid));
        }
    }

    @Test
    void bothModesCountAsAProMicro() {
        for (int pid : new int[]{0x9203, 0x9204, 0x9205, 0x9206}) {
            assertTrue(port(pid).isProMicro());
        }
    }

    @Test
    void otherDevicesAreNeither() {
        PortInfo other = new PortInfo("COM9", "Some FTDI adapter", 0x0403, 0x6001);
        assertFalse(other.isSensor());
        assertFalse(other.isBootloader());
        assertFalse(other.isProMicro());
    }

    /** The same product id under another vendor is not one of ours. */
    @Test
    void productIdAloneIsNotEnough() {
        assertFalse(new PortInfo("COM9", "impostor", 0x2341, 0x9205).isBootloader());
    }

    @Test
    void describesTheTwoModesDifferently() {
        assertTrue(port(0x9206).toString().contains("SoundNet sensor"));
        assertTrue(port(0x9205).toString().contains("update mode"));
    }
}
