package org.soundnet.flasher;

import java.io.ByteArrayInputStream;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One selectable firmware build: a revision, a version, and where to get it. */
public final class FirmwareImage {

    private final BoardRevision revision;
    private final String version;
    private final String fileName;
    private final String notes;
    private final String released;
    private final boolean recommended;
    private final FirmwareSource source;

    FirmwareImage(BoardRevision revision, String version, String fileName, String notes,
                  String released, boolean recommended, FirmwareSource source) {
        this.revision = revision;
        this.version = version;
        this.fileName = fileName;
        this.notes = notes == null ? "" : notes;
        this.released = released;
        this.recommended = recommended;
        this.source = source;
    }

    public BoardRevision revision() {
        return revision;
    }

    public String version() {
        return version;
    }

    public String fileName() {
        return fileName;
    }

    public String notes() {
        return notes;
    }

    public String released() {
        return released;
    }

    public boolean recommended() {
        return recommended;
    }

    public FirmwareSource source() {
        return source;
    }

    public boolean availableOffline() {
        return source.availableOffline();
    }

    /** Identity for de-duplicating the bundled list against the online one. */
    String key() {
        return revision.name() + "@" + version;
    }

    /** Fetches (downloading if needed) and parses the image. */
    public IntelHex load(FlashListener listener) throws FlashException {
        byte[] bytes = source.fetch(listener);
        try {
            return IntelHex.parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new FlashException("The firmware file is not valid: " + e.getMessage(), e);
        }
    }

    /** A one-line description for the notes area under the drop-down. */
    public String detail() {
        StringBuilder sb = new StringBuilder();
        if (!notes.isBlank()) {
            sb.append(notes);
        }
        if (released != null && !released.isBlank()) {
            sb.append(sb.length() > 0 ? "   ·   " : "").append("Released ").append(released);
        }
        sb.append(sb.length() > 0 ? "   ·   " : "").append(source.describe());
        return sb.toString();
    }

    @Override
    public String toString() {
        String suffix = recommended ? "   (recommended)"
                : source.availableOffline() ? "" : "   ⤓";
        return revision.name() + "   —   v" + version + suffix;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FirmwareImage && ((FirmwareImage) o).key().equals(key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    /**
     * Newest first. Firmware versions are dotted numbers that sometimes carry a
     * letter suffix (2.0.6a), so compare the numeric parts numerically rather
     * than as text - otherwise 2.0.10 sorts below 2.0.6.
     */
    public static final Comparator<String> VERSION_ORDER = (a, b) -> {
        Pattern part = Pattern.compile("(\\d+)|([A-Za-z]+)");
        Matcher ma = part.matcher(a == null ? "" : a);
        Matcher mb = part.matcher(b == null ? "" : b);
        while (true) {
            boolean hasA = ma.find();
            boolean hasB = mb.find();
            if (!hasA || !hasB) {
                return Boolean.compare(hasA, hasB);
            }
            String ga = ma.group();
            String gb = mb.group();
            boolean numericA = Character.isDigit(ga.charAt(0));
            boolean numericB = Character.isDigit(gb.charAt(0));
            int cmp;
            if (numericA && numericB) {
                cmp = Long.compare(Long.parseLong(ga), Long.parseLong(gb));
            } else if (numericA != numericB) {
                // 2.0.6 comes before 2.0.6a
                cmp = numericA ? -1 : 1;
            } else {
                cmp = ga.compareToIgnoreCase(gb);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
    };

    /** Recommended builds first, then newest version, then newest revision. */
    public static final Comparator<FirmwareImage> DISPLAY_ORDER =
            Comparator.comparing(FirmwareImage::recommended).reversed()
                    .thenComparing(FirmwareImage::version, VERSION_ORDER.reversed())
                    .thenComparing(i -> i.revision().id(), Comparator.reverseOrder());
}
