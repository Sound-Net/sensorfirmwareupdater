package org.soundnet.flasher;

/** One serial port as offered in the drop-down. */
public final class PortInfo {

    /** SparkFun's USB vendor id, shared by the Pro Micro in both of its modes. */
    public static final int SPARKFUN_VID = 0x1B4F;

    private final String systemName;
    private final String description;
    private final int vid;
    private final int pid;

    public PortInfo(String systemName, String description, int vid, int pid) {
        this.systemName = systemName;
        this.description = description;
        this.vid = vid;
        this.pid = pid;
    }

    public String systemName() {
        return systemName;
    }

    public int vid() {
        return vid;
    }

    public int pid() {
        return pid;
    }

    /**
     * True when this is a Pro Micro running the sketch.
     *
     * <p>These are the {@code build.pid} values from SparkFun's boards.txt - the
     * product id compiled into the sketch's own USB descriptor:
     *
     * <pre>
     *   promicro.menu.cpu.8MHzatmega32U4.build.pid  = 0x9204
     *   promicro.menu.cpu.16MHzatmega32U4.build.pid = 0x9206
     * </pre>
     *
     * <p>Do not swap these with the bootloader ids below. Getting them the wrong
     * way round makes the updater mistake a running sensor for a bootloader,
     * skip the 1200-baud reset, and speak AVR109 at the live firmware.
     */
    public boolean isSensor() {
        return vid == SPARKFUN_VID && (pid == 0x9204 || pid == 0x9206);
    }

    /**
     * True when this is a Pro Micro sitting in its Caterina bootloader.
     *
     * <p>The {@code build.pid.0} values from boards.txt: 0x9203 at 8 MHz and
     * 0x9205 at 16 MHz.
     */
    public boolean isBootloader() {
        return vid == SPARKFUN_VID && (pid == 0x9203 || pid == 0x9205);
    }

    /** True for either mode of a Pro Micro. */
    public boolean isProMicro() {
        return isSensor() || isBootloader();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(systemName);
        if (isSensor()) {
            sb.append("  -  SoundNet sensor");
        } else if (isBootloader()) {
            sb.append("  -  SoundNet sensor (update mode)");
        } else if (description != null && !description.isBlank()) {
            sb.append("  -  ").append(description);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PortInfo && ((PortInfo) o).systemName.equals(systemName);
    }

    @Override
    public int hashCode() {
        return systemName.hashCode();
    }
}
