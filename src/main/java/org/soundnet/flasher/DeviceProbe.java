package org.soundnet.flasher;

import com.fazecast.jSerialComm.SerialPort;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Asks a running sensor what it is, before anything is overwritten.
 *
 * <p>The firmware answers two XBus requests over USB serial (see
 * {@code print.cpp}, {@code printReqMessage}):
 *
 * <pre>
 *   XMID_ReqDeviceType      0x6A  ->  "DID &lt;SENSOR_TYPE&gt;"
 *   XMID_ReqFirmwareVersion 0x6C  ->  "FV &lt;FIRMWARE_VERSION&gt;"
 * </pre>
 *
 * <p>This is best-effort: a sensor asleep in a low-power state, or one already
 * sitting in its bootloader, will not reply, and that is not an error. When it
 * does reply the answer is worth having, because SENSOR_TYPE selects pin
 * mappings at compile time - flashing a revision the board is not will produce a
 * sensor that powers up but never reads the Xsens.
 */
public final class DeviceProbe {

    private static final byte XBUS_PREAMBLE = (byte) 0xFA;
    private static final byte XBUS_MASTERDEVICE = (byte) 0xFF;
    private static final byte MID_REQ_DEVICE_TYPE = 0x6A;
    private static final byte MID_REQ_FIRMWARE_VERSION = 0x6C;

    /** Matches SERIAL_BAUD_RATE in globals.h. */
    private static final int BAUD = 38400;
    private static final int REPLY_TIMEOUT_MS = 1500;

    /** What a sensor reported about itself. Either field may be absent. */
    public static final class Identity {
        private final BoardRevision revision;
        private final String firmwareVersion;

        Identity(BoardRevision revision, String firmwareVersion) {
            this.revision = revision;
            this.firmwareVersion = firmwareVersion;
        }

        public BoardRevision revision() {
            return revision;
        }

        public String firmwareVersion() {
            return firmwareVersion;
        }

        @Override
        public String toString() {
            String rev = revision != null ? revision.name() : "unknown revision";
            String ver = firmwareVersion != null ? " running v" + firmwareVersion : "";
            return rev + ver;
        }
    }

    private DeviceProbe() {
    }

    /**
     * @return what the sensor says it is, or empty if it did not answer in time.
     */
    public static Optional<Identity> identify(String portName, FlashListener listener) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(BAUD, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 300, 300);
        if (!port.openPort()) {
            listener.log("Could not open " + portName + " to identify the sensor.");
            return Optional.empty();
        }
        try {
            // The sketch restarts when the port opens; give it a moment to boot.
            SerialPortService.sleep(1500);

            BoardRevision revision = null;
            String version = null;

            String didReply = exchange(port, MID_REQ_DEVICE_TYPE, "DID");
            if (didReply != null) {
                try {
                    revision = BoardRevision.fromId(Integer.parseInt(didReply.trim()));
                } catch (NumberFormatException ignored) {
                    listener.log("Sensor reported an unreadable device type: " + didReply);
                }
            }

            String fvReply = exchange(port, MID_REQ_FIRMWARE_VERSION, "FV");
            if (fvReply != null) {
                version = fvReply.trim();
            }

            if (revision == null && version == null) {
                listener.log("The sensor did not identify itself "
                        + "(it may be asleep or in low-power mode).");
                return Optional.empty();
            }
            Identity identity = new Identity(revision, version);
            listener.log("Sensor identified: " + identity);
            return Optional.of(identity);
        } finally {
            port.closePort();
        }
    }

    /** Sends one request and returns the text after {@code prefix}, or null. */
    private static String exchange(SerialPort port, byte mid, String prefix) {
        port.writeBytes(frame(mid), 5);

        StringBuilder received = new StringBuilder();
        byte[] buffer = new byte[256];
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int n = port.readBytes(buffer, buffer.length);
            if (n > 0) {
                received.append(new String(buffer, 0, n, StandardCharsets.US_ASCII));
                for (String line : received.toString().split("\\R")) {
                    if (line.startsWith(prefix + " ")) {
                        return line.substring(prefix.length() + 1);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Builds a zero-payload XBus frame. Matches XbusMessage_format in
     * xsensmessage.c: the checksum is the two's complement of the sum of every
     * byte after the preamble.
     */
    static byte[] frame(byte mid) {
        byte length = 0;
        byte checksum = (byte) -(XBUS_MASTERDEVICE + mid + length);
        return new byte[]{XBUS_PREAMBLE, XBUS_MASTERDEVICE, mid, length, checksum};
    }
}
