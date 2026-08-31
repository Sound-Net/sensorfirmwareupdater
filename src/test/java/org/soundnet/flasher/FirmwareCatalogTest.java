package org.soundnet.flasher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the manifest parsing that the firmware repository and the built-in
 * fallback share, using the local-folder source so no network is involved.
 */
class FirmwareCatalogTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty("soundnet.firmware.dir");
    }

    private static FirmwareCatalog.Result load(Path folder, Path cacheDir, String manifest)
            throws IOException {
        Files.writeString(folder.resolve("manifest.json"), manifest);
        System.setProperty("soundnet.firmware.dir", folder.toString());
        return new FirmwareCatalog(new FirmwareCache(cacheDir))
                .loadOffline(FlashListener.NULL);
    }

    @Test
    void readsAWellFormedManifest(@TempDir Path folder, @TempDir Path cache) throws Exception {
        Files.writeString(folder.resolve("a.hex"), ":00000001FF\n");
        FirmwareCatalog.Result result = load(folder, cache, """
                {
                  "schemaVersion": 1,
                  "firmware": [
                    {"revision": "SOUNDNET_V1_R6", "version": "2.0.7", "file": "a.hex",
                     "notes": "Field lockup fixes", "released": "2026-08-31",
                     "recommended": true}
                  ]
                }
                """);

        assertEquals(1, result.images().size());
        FirmwareImage image = result.images().get(0);
        assertEquals(BoardRevision.SOUNDNET_V1_R6, image.revision());
        assertEquals("2.0.7", image.version());
        assertTrue(image.recommended());
        assertTrue(image.availableOffline());
        assertTrue(image.detail().contains("Field lockup fixes"));
    }

    @Test
    void skipsEntriesWhoseFileIsMissing(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        FirmwareCatalog.Result result = load(folder, cache, """
                {"schemaVersion": 1, "firmware": [
                  {"revision": "SOUNDNET_V1_R6", "version": "2.0.7", "file": "absent.hex"}]}
                """);
        assertEquals(0, result.images().size());
    }

    @Test
    void skipsUnknownHardwareRevisions(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        Files.writeString(folder.resolve("a.hex"), ":00000001FF\n");
        FirmwareCatalog.Result result = load(folder, cache, """
                {"schemaVersion": 1, "firmware": [
                  {"revision": "SOUNDNET_V9_R9", "version": "2.0.7", "file": "a.hex"}]}
                """);
        assertEquals(0, result.images().size());
    }

    @Test
    void survivesAManifestThatIsNotValidJson(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        assertEquals(0, load(folder, cache, "this is not json").images().size());
    }

    @Test
    void ordersRecommendedFirstThenNewest(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        for (String name : List.of("old.hex", "new.hex", "rec.hex")) {
            Files.writeString(folder.resolve(name), ":00000001FF\n");
        }
        FirmwareCatalog.Result result = load(folder, cache, """
                {"schemaVersion": 1, "firmware": [
                  {"revision": "SOUNDNET_V1_R6", "version": "2.0.6",  "file": "old.hex"},
                  {"revision": "SOUNDNET_V1_R6", "version": "2.0.10", "file": "new.hex"},
                  {"revision": "SOUNDNET_V1_R5", "version": "2.0.1",  "file": "rec.hex",
                   "recommended": true}]}
                """);

        List<FirmwareImage> images = result.images();
        assertEquals(3, images.size());
        assertTrue(images.get(0).recommended(), "recommended build should come first");
        assertEquals("2.0.10", images.get(1).version(), "then the newest version");
        assertEquals("2.0.6", images.get(2).version());
    }

    /** A newer schema should warn, not throw away everything it can still read. */
    @Test
    void stillReadsEntriesFromANewerSchema(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        Files.writeString(folder.resolve("a.hex"), ":00000001FF\n");
        FirmwareCatalog.Result result = load(folder, cache, """
                {"schemaVersion": 99, "firmware": [
                  {"revision": "SOUNDNET_V1_R6", "version": "2.0.7", "file": "a.hex"}]}
                """);
        assertEquals(1, result.images().size());
    }
}
