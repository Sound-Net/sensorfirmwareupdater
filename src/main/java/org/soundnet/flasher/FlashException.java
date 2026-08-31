package org.soundnet.flasher;

/**
 * A failure the researcher needs to read. Messages are written for someone who
 * has never heard of a bootloader, and carry the suggested next step.
 */
public class FlashException extends Exception {
    public FlashException(String message) {
        super(message);
    }

    public FlashException(String message, Throwable cause) {
        super(message, cause);
    }
}
