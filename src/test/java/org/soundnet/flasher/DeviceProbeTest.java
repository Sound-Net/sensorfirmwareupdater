package org.soundnet.flasher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeviceProbeTest {

    /**
     * XbusMessage_format in xsensmessage.c seeds the checksum with -BID and then
     * subtracts every subsequent byte, so the whole frame after the preamble sums
     * to zero modulo 256.
     */
    @Test
    void frameChecksumMatchesTheFirmware() {
        byte[] frame = DeviceProbe.frame((byte) 0x6A); // XMID_ReqDeviceType

        assertEquals(5, frame.length);
        assertEquals((byte) 0xFA, frame[0]); // preamble
        assertEquals((byte) 0xFF, frame[1]); // master device
        assertEquals((byte) 0x6A, frame[2]); // message id
        assertEquals((byte) 0x00, frame[3]); // length

        int sum = 0;
        for (int i = 1; i < frame.length; i++) {
            sum += frame[i] & 0xFF;
        }
        assertEquals(0, sum & 0xFF, "bytes after the preamble must sum to zero");
    }

    @Test
    void firmwareVersionRequestIsAlsoWellFormed() {
        byte[] frame = DeviceProbe.frame((byte) 0x6C); // XMID_ReqFirmwareVersion
        int sum = 0;
        for (int i = 1; i < frame.length; i++) {
            sum += frame[i] & 0xFF;
        }
        assertEquals(0, sum & 0xFF);
    }
}
