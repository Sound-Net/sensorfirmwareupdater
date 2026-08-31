package org.soundnet.flasher;

import com.fazecast.jSerialComm.SerialPort;

import java.util.Arrays;

/**
 * Writes a flash image to an ATmega32U4 running the Caterina bootloader, using
 * the AVR109 ("butterfly") protocol that SparkFun's boards.txt specifies for the
 * Pro Micro:
 *
 * <pre>
 *   promicro.upload.protocol=avr109
 *   promicro.upload.speed=57600
 *   promicro.upload.maximum_size=28672
 * </pre>
 *
 * <p>The protocol is simple enough to speak directly, which keeps the updater a
 * single self-contained application - no bundled avrdude binary and no
 * avrdude.conf to ship, install or get quarantined by antivirus.
 */
public final class Avr109Programmer implements AutoCloseable {

    /** Bytes below the Caterina bootloader; writing past this would destroy it. */
    public static final int APPLICATION_MAX_BYTES = 28672; // 0x7000

    private static final byte CR = 0x0D;
    private static final int DEFAULT_BLOCK_SIZE = 128;
    private static final int IO_TIMEOUT_MS = 3000;

    private final SerialPort port;
    private final FlashListener listener;
    private int blockSize = DEFAULT_BLOCK_SIZE;

    private Avr109Programmer(SerialPort port, FlashListener listener) {
        this.port = port;
        this.listener = listener;
    }

    /** Opens the bootloader port and completes the protocol handshake. */
    public static Avr109Programmer open(String portName, FlashListener listener)
            throws FlashException {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(57600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, IO_TIMEOUT_MS, IO_TIMEOUT_MS);

        // Windows in particular can advertise the bootloader port a moment before
        // it is actually openable.
        FlashException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            if (port.openPort()) {
                last = null;
                break;
            }
            last = new FlashException("Could not open " + portName
                    + ". Another program may be using it - close any serial monitor and try again.");
            sleep(200);
        }
        if (last != null) {
            throw last;
        }

        Avr109Programmer programmer = new Avr109Programmer(port, listener);
        try {
            programmer.handshake();
            return programmer;
        } catch (FlashException e) {
            programmer.close();
            throw e;
        }
    }

    private void handshake() throws FlashException {
        // Caterina can have stale bytes queued from the reset; drain them first.
        drain();

        String id = new String(request((byte) 'S', 7), java.nio.charset.StandardCharsets.US_ASCII);
        listener.log("Bootloader identifier: " + id.trim());

        byte[] version = request((byte) 'V', 2);
        listener.log("Bootloader version: " + (char) version[0] + "." + (char) version[1]);

        byte[] type = request((byte) 'p', 1);
        if (type[0] != 'S') {
            listener.log("Unexpected programmer type: " + (char) type[0]);
        }

        // 'b' reports whether block transfer is supported and the buffer size.
        byte[] blockSupport = request((byte) 'b', 1);
        if (blockSupport[0] == 'Y') {
            byte[] size = readExactly(2);
            int reported = ((size[0] & 0xFF) << 8) | (size[1] & 0xFF);
            if (reported > 0 && reported <= 1024) {
                blockSize = reported;
            }
            listener.log("Block transfer supported, buffer " + blockSize + " bytes");
        } else {
            throw new FlashException("This bootloader does not support block transfers, "
                    + "so the updater cannot program it. The device may not be a Pro Micro.");
        }
    }

    /**
     * Programs {@code image} into flash and reads it back to confirm.
     *
     * @param image flash contents starting at address 0
     */
    public void writeFlash(byte[] image) throws FlashException {
        if (image.length > APPLICATION_MAX_BYTES) {
            throw new FlashException(String.format(
                    "This firmware is %,d bytes but only %,d bytes are available below the "
                            + "bootloader. Writing it would erase the bootloader and the sensor "
                            + "could no longer be updated over USB. Refusing to continue.",
                    image.length, APPLICATION_MAX_BYTES));
        }

        // Pad to a whole number of blocks; erased flash reads as 0xFF.
        int padded = ((image.length + blockSize - 1) / blockSize) * blockSize;
        byte[] buffer = Arrays.copyOf(image, padded);
        Arrays.fill(buffer, image.length, padded, (byte) 0xFF);

        listener.stage("Writing firmware...");
        // No chip erase: Caterina erases each page as part of the block write,
        // which is why the Arduino IDE passes -D to avrdude for this board.
        setAddress(0);
        for (int offset = 0; offset < buffer.length; offset += blockSize) {
            writeBlock(buffer, offset, blockSize);
            listener.progress(0.10 + 0.60 * ((double) (offset + blockSize) / buffer.length));
        }

        listener.stage("Verifying...");
        setAddress(0);
        for (int offset = 0; offset < buffer.length; offset += blockSize) {
            byte[] readBack = readBlock(blockSize);
            for (int i = 0; i < blockSize; i++) {
                if (readBack[i] != buffer[offset + i]) {
                    throw new FlashException(String.format(
                            "Verification failed at address 0x%04X: wrote 0x%02X but read back "
                                    + "0x%02X. The firmware on the sensor is incomplete - leave it "
                                    + "plugged in and flash it again.",
                            offset + i, buffer[offset + i], readBack[i]));
                }
            }
            listener.progress(0.70 + 0.28 * ((double) (offset + blockSize) / buffer.length));
        }
    }

    /** Leaves the bootloader and starts the freshly written firmware. */
    public void exitBootloader() {
        try {
            write(new byte[]{'E'});
            readExactly(1);
        } catch (FlashException e) {
            // The board frequently drops USB before acknowledging; that is fine,
            // the firmware is already written and verified.
            listener.log("Board disconnected on exit (normal).");
        }
    }

    private void setAddress(int byteAddress) throws FlashException {
        int word = byteAddress >> 1; // 'A' takes a word address
        expectCr(request(new byte[]{'A', (byte) (word >> 8), (byte) word}, 1),
                "set address");
    }

    private void writeBlock(byte[] source, int offset, int length) throws FlashException {
        byte[] command = new byte[4 + length];
        command[0] = 'B';
        command[1] = (byte) (length >> 8);
        command[2] = (byte) length;
        command[3] = 'F'; // flash memory
        System.arraycopy(source, offset, command, 4, length);
        expectCr(request(command, 1), "write block at 0x" + Integer.toHexString(offset));
    }

    private byte[] readBlock(int length) throws FlashException {
        return request(new byte[]{'g', (byte) (length >> 8), (byte) length, 'F'}, length);
    }

    private void expectCr(byte[] response, String what) throws FlashException {
        if (response.length != 1 || response[0] != CR) {
            throw new FlashException("The sensor rejected a '" + what + "' command. "
                    + "Unplug it, plug it back in and try again.");
        }
    }

    private byte[] request(byte command, int expected) throws FlashException {
        return request(new byte[]{command}, expected);
    }

    private byte[] request(byte[] command, int expected) throws FlashException {
        write(command);
        return readExactly(expected);
    }

    private void write(byte[] bytes) throws FlashException {
        int written = port.writeBytes(bytes, bytes.length);
        if (written != bytes.length) {
            throw new FlashException("Lost contact with the sensor while sending data. "
                    + "Check the USB cable and try again.");
        }
    }

    private byte[] readExactly(int count) throws FlashException {
        byte[] out = new byte[count];
        int got = 0;
        long deadline = System.currentTimeMillis() + IO_TIMEOUT_MS;
        while (got < count) {
            int n = port.readBytes(out, count - got, got);
            if (n > 0) {
                got += n;
            } else if (System.currentTimeMillis() > deadline) {
                throw new FlashException("The sensor stopped responding. "
                        + "Unplug it, plug it back in and try again.");
            }
        }
        return out;
    }

    private void drain() {
        int available = port.bytesAvailable();
        while (available > 0) {
            byte[] scratch = new byte[available];
            port.readBytes(scratch, available);
            available = port.bytesAvailable();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (port.isOpen()) {
            port.closePort();
        }
    }
}
