package org.soundnet.flasher;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/**
 * Small vector icons drawn as SVG paths.
 *
 * <p>Transit ships {@code MDL2IconFont}, but that depends on the Segoe MDL2
 * Assets font, which exists on Windows and not on macOS or Linux - the icons
 * would come out as empty boxes on two of the three platforms we build for.
 * Paths render identically everywhere and take their colour from CSS, so they
 * follow the light/dark theme.
 */
final class Icons {

    /** The paths below are drawn on a 24x24 grid. */
    private static final double SOURCE_SIZE = 24.0;
    private static final double DEFAULT_SIZE = 15.0;

    private static final String REFRESH =
            "M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 "
                    + "6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 "
                    + "6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z";

    private static final String CLOUD_DOWNLOAD =
            "M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 "
                    + "14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM17 "
                    + "13l-5 5-5-5h3V9h4v4h3z";

    private Icons() {
    }

    /** Circular arrow - re-scan the serial ports. */
    static Node refresh() {
        return icon(REFRESH);
    }

    /** Cloud with an arrow - go and look at the firmware repository. */
    static Node cloudDownload() {
        return icon(CLOUD_DOWNLOAD);
    }

    private static Node icon(String path) {
        SVGPath shape = new SVGPath();
        shape.setContent(path);
        // Fill comes from the ".icon" rule in style.css so it flips with the theme.
        shape.getStyleClass().add("icon");
        double factor = DEFAULT_SIZE / SOURCE_SIZE;
        shape.getTransforms().add(new Scale(factor, factor));
        // A Group reports the transformed size, so the button lays out correctly.
        return new Group(shape);
    }
}
