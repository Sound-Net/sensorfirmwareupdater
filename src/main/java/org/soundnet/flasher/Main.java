package org.soundnet.flasher;

/**
 * Launcher.
 *
 * <p>A separate entry point from {@link FlasherApp} on purpose: a main class that
 * does not itself extend {@code Application} lets the JavaFX runtime start from
 * the plain classpath, which is what keeps the packaged application a single
 * double-clickable thing with no module-path incantations.
 */
public final class Main {
    public static void main(String[] args) {
        FlasherApp.main(args);
    }
}
