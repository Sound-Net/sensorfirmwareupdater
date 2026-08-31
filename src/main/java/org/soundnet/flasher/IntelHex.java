package org.soundnet.flasher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Intel HEX reader, sufficient for the ATmega32U4 images the Arduino
 * toolchain emits (record types 00, 01 and 04).
 */
public final class IntelHex {

    private final byte[] data;
    private final int maxAddress;

    private IntelHex(byte[] data, int maxAddress) {
        this.data = data;
        this.maxAddress = maxAddress;
    }

    /** Flash image, zero-padded from address 0 up to the highest byte written. */
    public byte[] data() {
        return data;
    }

    /** One past the highest address written to. */
    public int size() {
        return maxAddress;
    }

    public static IntelHex parse(InputStream in) throws IOException {
        byte[] buffer = new byte[0x20000];
        boolean[] written = new boolean[buffer.length];
        int highest = 0;
        int segmentBase = 0;
        int lineNo = 0;
        boolean sawEof = false;

        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) != ':') {
                    throw new IOException("Line " + lineNo + ": record does not start with ':'");
                }
                byte[] record = decodeHex(line.substring(1), lineNo);
                if (record.length < 5) {
                    throw new IOException("Line " + lineNo + ": record too short");
                }

                int count = record[0] & 0xFF;
                if (record.length != count + 5) {
                    throw new IOException("Line " + lineNo + ": length byte disagrees with record");
                }
                int sum = 0;
                for (byte b : record) {
                    sum += b & 0xFF;
                }
                if ((sum & 0xFF) != 0) {
                    throw new IOException("Line " + lineNo + ": bad checksum");
                }

                int address = ((record[1] & 0xFF) << 8) | (record[2] & 0xFF);
                int type = record[3] & 0xFF;

                switch (type) {
                    case 0x00: { // data
                        int base = segmentBase + address;
                        if (base + count > buffer.length) {
                            throw new IOException(
                                    "Line " + lineNo + ": address 0x" + Integer.toHexString(base)
                                            + " is beyond the supported flash window");
                        }
                        for (int i = 0; i < count; i++) {
                            buffer[base + i] = record[4 + i];
                            written[base + i] = true;
                        }
                        highest = Math.max(highest, base + count);
                        break;
                    }
                    case 0x01: // end of file
                        sawEof = true;
                        break;
                    case 0x04: // extended linear address
                        if (count != 2) {
                            throw new IOException("Line " + lineNo + ": malformed type 04 record");
                        }
                        segmentBase = (((record[4] & 0xFF) << 8) | (record[5] & 0xFF)) << 16;
                        break;
                    case 0x02: // extended segment address
                        if (count != 2) {
                            throw new IOException("Line " + lineNo + ": malformed type 02 record");
                        }
                        segmentBase = (((record[4] & 0xFF) << 8) | (record[5] & 0xFF)) << 4;
                        break;
                    case 0x03:
                    case 0x05:
                        break; // start address records - irrelevant for AVR
                    default:
                        throw new IOException("Line " + lineNo + ": unsupported record type " + type);
                }
            }
        }

        if (!sawEof) {
            throw new IOException("File ended without an end-of-file record - it may be truncated");
        }
        if (highest == 0) {
            throw new IOException("File contains no program data");
        }

        byte[] image = new byte[highest];
        System.arraycopy(buffer, 0, image, 0, highest);
        // Unwritten gaps read as 0xFF on erased flash; match that so verification
        // compares like with like.
        for (int i = 0; i < highest; i++) {
            if (!written[i]) {
                image[i] = (byte) 0xFF;
            }
        }
        return new IntelHex(image, highest);
    }

    private static byte[] decodeHex(String s, int lineNo) throws IOException {
        if ((s.length() & 1) != 0) {
            throw new IOException("Line " + lineNo + ": odd number of hex digits");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IOException("Line " + lineNo + ": invalid hex digit");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
