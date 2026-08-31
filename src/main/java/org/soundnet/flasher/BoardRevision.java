package org.soundnet.flasher;

/**
 * Sensor hardware revisions, mirroring the SENSOR_TYPE constants in
 * firmware/libraries/xsens/xsensmessage.h.
 *
 * <p>The revision is a compile-time constant in the firmware: it selects pin
 * mappings (XSENS_RX/TX, XSENS_PWR) and which sensors are compiled in. A given
 * .hex is therefore valid for exactly one revision, and flashing the wrong one
 * leaves a sensor that enumerates but never talks to the Xsens.
 */
public enum BoardRevision {
    SOUNDNET_V1_R1(1),
    SOUNDNET_V1_R2(2),
    SOUNDNET_V1_R3(3),
    SOUNDNET_V1_R4(4),
    SOUNDNET_V1_R5(5),
    SOUNDNET_V1_R6(6),
    SOUNDNET_V2_R1(10),
    SOUNDNET_V2_R2(11),
    SOUNDNET_V2_R3(12),
    SENSLOGGER_V1(200);

    private final int id;

    BoardRevision(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** @return the revision with this SENSOR_TYPE id, or null if unrecognised. */
    public static BoardRevision fromId(int id) {
        for (BoardRevision r : values()) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    public static BoardRevision fromName(String name) {
        for (BoardRevision r : values()) {
            if (r.name().equalsIgnoreCase(name)) {
                return r;
            }
        }
        return null;
    }
}
