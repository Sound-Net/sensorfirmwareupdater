package org.soundnet.flasher;

/** Callbacks for driving the UI while a flash is in progress. */
public interface FlashListener {
    /** A human-readable step, shown next to the progress bar. */
    void stage(String message);

    /** Progress through the whole operation, 0.0 to 1.0. */
    void progress(double fraction);

    /** Detail for the log pane. */
    void log(String message);

    FlashListener NULL = new FlashListener() {
        public void stage(String message) { }
        public void progress(double fraction) { }
        public void log(String message) { }
    };
}
