package org.soundnet.flasher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FirmwareImageTest {

    /** 2.0.10 is newer than 2.0.6, which plain text ordering gets backwards. */
    @Test
    void ordersVersionsNumerically() {
        assertTrue(FirmwareImage.VERSION_ORDER.compare("2.0.10", "2.0.6") > 0);
        assertTrue(FirmwareImage.VERSION_ORDER.compare("2.0.7", "2.0.7") == 0);
        assertTrue(FirmwareImage.VERSION_ORDER.compare("10.0.0", "9.9.9") > 0);
    }

    /** The firmware has shipped suffixed versions such as 2.0.6a. */
    @Test
    void treatsALetterSuffixAsNewerThanThePlainVersion() {
        assertTrue(FirmwareImage.VERSION_ORDER.compare("2.0.6a", "2.0.6") > 0);
        assertTrue(FirmwareImage.VERSION_ORDER.compare("2.0.6b", "2.0.6a") > 0);
        assertTrue(FirmwareImage.VERSION_ORDER.compare("2.0.7", "2.0.6a") > 0);
    }
}
