package org.soundnet.flasher;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the list of firmware offered in the drop-down, from three places:
 *
 * <ol>
 *   <li><b>Bundled</b> - compiled into the application. Always present, always
 *       installable, no network. This is the floor: the updater is never useless.</li>
 *   <li><b>Cached</b> - the last manifest fetched from the firmware repository,
 *       plus any firmware already downloaded. Lets an updater that has been
 *       online once keep working offline.</li>
 *   <li><b>Online</b> - the live manifest from the firmware repository, fetched
 *       in the background so a slow or absent connection never delays startup.</li>
 * </ol>
 *
 * <p>Where the same build appears in more than one place, the copy that can be
 * installed without a download wins.
 */
public final class FirmwareCatalog {

    private static final String BUNDLED_MANIFEST = "/firmware/manifest.json";
    private static final Gson GSON = new Gson();

    private final FirmwareCache cache;

    public FirmwareCatalog() {
        this(new FirmwareCache());
    }

    FirmwareCatalog(FirmwareCache cache) {
        this.cache = cache;
    }

    /** The outcome of assembling a list. */
    public static final class Result {
        private final List<FirmwareImage> images;
        private final boolean online;
        private final String status;

        Result(List<FirmwareImage> images, boolean online, String status) {
            this.images = images;
            this.online = online;
            this.status = status;
        }

        public List<FirmwareImage> images() {
            return images;
        }

        public boolean online() {
            return online;
        }

        /** One line for the UI, e.g. "8 firmware versions available". */
        public String status() {
            return status;
        }
    }

    /**
     * Everything installable right now without touching the network. Fast enough
     * to call on the UI thread at startup.
     */
    public Result loadOffline(FlashListener listener) {
        Map<String, FirmwareImage> byKey = new LinkedHashMap<>();
        addAll(byKey, bundled(listener));
        addAll(byKey, localFolder(listener));

        String cachedManifest = cache.readManifest();
        if (cachedManifest != null) {
            // Only offer remote builds whose file is already downloaded; anything
            // else would fail the moment the user pressed Update.
            for (FirmwareImage image : parseRemote(cachedManifest, listener)) {
                if (image.availableOffline()) {
                    add(byKey, image);
                }
            }
        }

        List<FirmwareImage> images = sorted(byKey);
        return new Result(images, false, describeOffline(images.size()));
    }

    /**
     * The offline list plus whatever the firmware repository is publishing.
     * Blocking - call this on a background thread.
     */
    public Result fetchOnline(FlashListener listener) {
        Map<String, FirmwareImage> byKey = new LinkedHashMap<>();
        addAll(byKey, bundled(listener));
        addAll(byKey, localFolder(listener));

        String json = RemoteCatalog.fetchManifest(listener);
        boolean online = json != null;

        if (online) {
            cache.writeManifest(json);
        } else {
            json = cache.readManifest();
        }

        int remoteCount = 0;
        if (json != null) {
            for (FirmwareImage image : parseRemote(json, listener)) {
                // Offline copies are already in the map and are preferred; only
                // offer a download for builds we do not otherwise have.
                if (online || image.availableOffline()) {
                    if (add(byKey, image)) {
                        remoteCount++;
                    }
                }
            }
        }

        List<FirmwareImage> images = sorted(byKey);
        String status;
        if (!online) {
            status = describeOffline(images.size());
        } else if (images.isEmpty()) {
            status = "The firmware repository is not publishing any firmware yet.";
        } else {
            status = images.size() + " firmware version" + (images.size() == 1 ? "" : "s")
                    + " available"
                    + (remoteCount > 0 ? " (" + remoteCount + " from the firmware repository)" : "");
        }
        return new Result(images, online, status);
    }

    /** Wording for the offline case, which has a genuinely different meaning at zero. */
    private static String describeOffline(int count) {
        if (count == 0) {
            return "No firmware on this computer, and the firmware repository "
                    + "could not be reached.";
        }
        return "Offline - " + count + " firmware version" + (count == 1 ? "" : "s")
                + " available on this computer";
    }

    // ---------------------------------------------------------------- sources

    private List<FirmwareImage> bundled(FlashListener listener) {
        try (InputStream in = FirmwareCatalog.class.getResourceAsStream(BUNDLED_MANIFEST)) {
            if (in == null) {
                listener.log("No firmware is bundled with this build of the updater.");
                return List.of();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json, listener, entry ->
                    new FirmwareSource.Bundled("/firmware/" + entry.file()));
        } catch (IOException e) {
            listener.log("Could not read the built-in firmware list: " + e.getMessage());
            return List.of();
        }
    }

    /** An optional {@code firmware} folder beside the installed application. */
    private List<FirmwareImage> localFolder(FlashListener listener) {
        Path directory = externalDirectory();
        Path manifest = directory.resolve("manifest.json");
        if (!Files.isReadable(manifest)) {
            return List.of();
        }
        try {
            String json = Files.readString(manifest, StandardCharsets.UTF_8);
            List<FirmwareImage> images = parse(json, listener, entry -> {
                Path file = directory.resolve(entry.file());
                return Files.isReadable(file) ? new FirmwareSource.LocalFile(file) : null;
            });
            if (!images.isEmpty()) {
                listener.log("Loaded " + images.size() + " firmware builds from " + directory);
            }
            return images;
        } catch (IOException e) {
            listener.log("Could not read " + manifest + ": " + e.getMessage());
            return List.of();
        }
    }

    private List<FirmwareImage> parseRemote(String json, FlashListener listener) {
        return parse(json, listener, entry -> {
            URI uri = RemoteCatalog.firmwareUri(entry.file());
            return new FirmwareSource.Remote(uri, entry.file(), entry.sha256(), cache);
        });
    }

    // ---------------------------------------------------------------- parsing

    /** Turns a manifest into images, skipping anything malformed rather than failing. */
    private List<FirmwareImage> parse(String json, FlashListener listener,
                                      SourceFactory sourceFactory) {
        List<FirmwareImage> images = new ArrayList<>();
        FirmwareManifest manifest;
        try {
            manifest = GSON.fromJson(json, FirmwareManifest.class);
        } catch (JsonSyntaxException e) {
            listener.log("Ignoring a firmware list that is not valid JSON: " + e.getMessage());
            return images;
        }
        if (manifest == null) {
            return images;
        }
        if (manifest.schemaVersion() > FirmwareManifest.SUPPORTED_SCHEMA_VERSION) {
            listener.log("The firmware repository uses a newer format (schemaVersion "
                    + manifest.schemaVersion() + ") than this updater understands. "
                    + "Some builds may not be listed - please update the application.");
        }

        for (FirmwareManifest.Entry entry : manifest.firmware()) {
            if (entry == null || !entry.isUsable()) {
                listener.log("Skipping an incomplete entry in the firmware list.");
                continue;
            }
            BoardRevision revision = BoardRevision.fromName(entry.revision());
            if (revision == null) {
                listener.log("Skipping firmware for unknown hardware revision '"
                        + entry.revision() + "'.");
                continue;
            }
            FirmwareSource source = sourceFactory.create(entry);
            if (source == null) {
                listener.log("Firmware list mentions " + entry.file()
                        + " but the file is missing.");
                continue;
            }
            images.add(new FirmwareImage(revision, entry.version(), entry.file(),
                    entry.notes(), entry.released(), entry.recommended(), source));
        }
        return images;
    }

    private interface SourceFactory {
        /** @return the source, or null if the file it needs is not there. */
        FirmwareSource create(FirmwareManifest.Entry entry);
    }

    // ----------------------------------------------------------------- merging

    private static void addAll(Map<String, FirmwareImage> target, List<FirmwareImage> images) {
        images.forEach(image -> add(target, image));
    }

    /** @return true if this image was added rather than losing to a better copy. */
    private static boolean add(Map<String, FirmwareImage> target, FirmwareImage image) {
        FirmwareImage existing = target.get(image.key());
        if (existing != null && existing.availableOffline()) {
            return false; // already have it locally; no reason to download
        }
        target.put(image.key(), image);
        return existing == null;
    }

    private static List<FirmwareImage> sorted(Map<String, FirmwareImage> byKey) {
        List<FirmwareImage> images = new ArrayList<>(byKey.values());
        images.sort(FirmwareImage.DISPLAY_ORDER);
        return images;
    }

    static Path externalDirectory() {
        String override = System.getProperty("soundnet.firmware.dir");
        if (override != null) {
            return Paths.get(override);
        }
        return Paths.get("").toAbsolutePath().resolve("firmware");
    }
}
