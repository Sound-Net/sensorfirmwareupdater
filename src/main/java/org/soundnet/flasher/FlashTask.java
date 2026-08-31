package org.soundnet.flasher;

import javafx.application.Platform;
import javafx.concurrent.Task;

import java.util.function.Consumer;

/**
 * Runs one complete update on a background thread: reset the sensor into its
 * bootloader, write the firmware, verify it, and restart.
 */
public final class FlashTask extends Task<Void> implements FlashListener {

    private final String portName;
    private final FirmwareImage image;
    private final Consumer<String> logSink;

    public FlashTask(String portName, FirmwareImage image, Consumer<String> logSink) {
        this.portName = portName;
        this.image = image;
        this.logSink = logSink;
    }

    @Override
    protected Void call() throws Exception {
        stage(image.availableOffline() ? "Reading firmware..." : "Fetching firmware...");
        progress(0.02);
        // Downloads if this build has not been fetched from the firmware
        // repository yet, and verifies its checksum before anything is written.
        IntelHex hex = image.load(this);
        log(String.format("Firmware %s v%s: %,d bytes of %,d available",
                image.revision().name(), image.version(), hex.size(),
                Avr109Programmer.APPLICATION_MAX_BYTES));

        if (hex.size() > Avr109Programmer.APPLICATION_MAX_BYTES) {
            throw new FlashException(String.format(
                    "This firmware is %,d bytes, which is larger than the %,d bytes available "
                            + "below the bootloader. It cannot be installed safely.",
                    hex.size(), Avr109Programmer.APPLICATION_MAX_BYTES));
        }

        String bootloaderPort = SerialPortService.enterBootloader(portName, this);
        progress(0.10);

        stage("Connecting to the sensor...");
        try (Avr109Programmer programmer = Avr109Programmer.open(bootloaderPort, this)) {
            programmer.writeFlash(hex.data());
            stage("Restarting the sensor...");
            programmer.exitBootloader();
        }

        progress(1.0);
        stage("Update complete.");
        log("The sensor is now running " + image.revision().name() + " v" + image.version() + ".");
        return null;
    }

    @Override
    public void stage(String message) {
        Platform.runLater(() -> updateMessage(message));
        log(message);
    }

    @Override
    public void progress(double fraction) {
        Platform.runLater(() -> updateProgress(Math.min(1.0, Math.max(0.0, fraction)), 1.0));
    }

    @Override
    public void log(String message) {
        Platform.runLater(() -> logSink.accept(message));
    }
}
