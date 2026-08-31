package org.soundnet.flasher;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Port discovery and the reset dance that puts a Pro Micro into its bootloader.
 *
 * <p>SparkFun's boards.txt sets {@code use_1200bps_touch=true}: opening the port
 * at 1200 baud and dropping DTR makes the sketch reboot into Caterina. The board
 * then re-enumerates under a <em>different</em> USB product id, which on Windows
 * usually means a different COM number. Hiding that swap is the main reason this
 * tool exists - a researcher watching Device Manager has no way to know which of
 * the two ports to pick.
 */
public final class SerialPortService {

    /** Caterina only waits about 8 seconds before handing back to the sketch. */
    private static final long BOOTLOADER_WAIT_MS = 10_000;

    private SerialPortService() {
    }

    public static List<PortInfo> listPorts() {
        List<PortInfo> ports = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) {
            ports.add(new PortInfo(
                    p.getSystemPortName(),
                    p.getDescriptivePortName(),
                    p.getVendorID(),
                    p.getProductID()));
        }
        // Show recognised sensors first - usually the only thing anyone wants.
        ports.sort((a, b) -> {
            int rank = Boolean.compare(b.isProMicro(), a.isProMicro());
            return rank != 0 ? rank : a.systemName().compareTo(b.systemName());
        });
        return ports;
    }

    private static Set<String> portNames() {
        return listPorts().stream().map(PortInfo::systemName).collect(Collectors.toSet());
    }

    /**
     * Puts the sensor on {@code portName} into its bootloader and returns the port
     * the bootloader appears on - which is usually <em>not</em> {@code portName}.
     */
    public static String enterBootloader(String portName, FlashListener listener)
            throws FlashException {

        for (PortInfo p : listPorts()) {
            if (p.systemName().equals(portName) && p.isBootloader()) {
                listener.log("Port is already in update mode; no reset needed.");
                return portName;
            }
        }

        Set<String> before = portNames();
        listener.stage("Restarting the sensor in update mode...");
        listener.log("Ports before reset: " + String.join(", ", before));

        touch1200(portName, listener);

        long deadline = System.currentTimeMillis() + BOOTLOADER_WAIT_MS;
        String fallback = null;
        while (System.currentTimeMillis() < deadline) {
            sleep(250);
            List<PortInfo> now = listPorts();

            // Strongest signal: a port advertising the Caterina product id.
            for (PortInfo p : now) {
                if (p.isBootloader()) {
                    listener.log("Update mode found on " + p.systemName());
                    return p.systemName();
                }
            }
            // Otherwise, any port that was not there before the reset.
            for (PortInfo p : now) {
                if (!before.contains(p.systemName())) {
                    fallback = p.systemName();
                }
            }
            if (fallback != null) {
                listener.log("New port appeared after reset: " + fallback);
                // Give the descriptor a moment to settle before committing.
                sleep(500);
                return fallback;
            }
        }

        throw new FlashException(
                "The sensor did not restart in update mode within 10 seconds.\n\n"
                        + "Things to try:\n"
                        + "  - Unplug the sensor, plug it back in, and try again.\n"
                        + "  - Make sure no other program (Arduino IDE, a serial monitor, "
                        + "PAMGuard) has the port open.\n"
                        + "  - Use a USB cable known to carry data, not a charge-only cable.");
    }

    private static void touch1200(String portName, FlashListener listener) throws FlashException {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(1200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        if (!port.openPort()) {
            throw new FlashException("Could not open " + portName + ".\n\n"
                    + "Close any other program that might be using the sensor "
                    + "(Arduino IDE, a serial monitor) and try again.");
        }
        try {
            // Dropping DTR is what Caterina watches for.
            port.clearDTR();
            sleep(250);
        } finally {
            port.closePort();
        }
        listener.log("Sent 1200 baud reset to " + portName);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ports present now, as a set - used by callers that want to diff. */
    static Set<String> snapshot() {
        return new HashSet<>(portNames());
    }
}
