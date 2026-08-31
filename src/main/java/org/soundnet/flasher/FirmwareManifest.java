package org.soundnet.flasher;

import java.util.Collections;
import java.util.List;

/**
 * The JSON index published by the firmware repository, and shipped inside the
 * application as the offline fallback. Gson populates these fields by name, so
 * they mirror the file exactly.
 *
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "updated": "2026-08-31",
 *   "firmware": [
 *     {
 *       "revision": "SOUNDNET_V1_R6",
 *       "version": "2.0.7",
 *       "file": "soundnet_firmware-2.0.7-SOUNDNET_V1_R6.hex",
 *       "sha256": "6f8a...",
 *       "released": "2026-08-31",
 *       "notes": "Fixes the random field lockups.",
 *       "recommended": true
 *     }
 *   ]
 * }
 * </pre>
 */
public final class FirmwareManifest {

    /** Bumped if the format ever changes incompatibly. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private int schemaVersion;
    private String updated;
    private List<Entry> firmware;

    public int schemaVersion() {
        return schemaVersion;
    }

    public String updated() {
        return updated;
    }

    public List<Entry> firmware() {
        return firmware == null ? Collections.emptyList() : firmware;
    }

    /** One published build. */
    public static final class Entry {
        private String revision;
        private String version;
        private String file;
        private String sha256;
        private String released;
        private String notes;
        private boolean recommended;

        public String revision() {
            return revision;
        }

        public String version() {
            return version;
        }

        public String file() {
            return file;
        }

        public String sha256() {
            return sha256;
        }

        public String released() {
            return released;
        }

        public String notes() {
            return notes == null ? "" : notes;
        }

        public boolean recommended() {
            return recommended;
        }

        /** True when the entry has everything the updater needs to use it. */
        public boolean isUsable() {
            return revision != null && !revision.isBlank()
                    && version != null && !version.isBlank()
                    && file != null && !file.isBlank();
        }
    }
}
