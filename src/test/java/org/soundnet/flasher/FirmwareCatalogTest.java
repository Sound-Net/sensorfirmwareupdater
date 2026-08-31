package org.soundnet.flasher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the manifest parsing that the firmware repository and the built-in
 * fallback share, using the local-folder source so no network is involved.
 *
 * <p>The application also bundles real firmware, which {@code loadOffline} quite
 * correctly includes. These tests therefore assert on the entries they created
 * rather than on raw totals, so that publishing more bundled firmware never
 * breaks them. Fixture versions are deliberately unlike any real release, so a
 * fixture can never collide with a bundled build and be de-duplicated away.
 */
class FirmwareCatalogTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty("soundnet.firmware.dir");
    }

    /** Loads the catalog, returning only images that came from {@code folder}. */
    private static List<FirmwareImage> loadFixtures(Path folder, Path cacheDir, String manifest)
            throws IOException {
        Files.writeString(folder.resolve("manifest.json"), manifest);
        System.setProperty("soundnet.firmware.dir", folder.toString());

        Set<String> ours;
        try (var entries = Files.list(folder)) {
            ours = entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".hex"))
                    .collect(Collectors.toSet());
        }
        return new FirmwareCatalog(new FirmwareCache(cacheDir))
                .loadOffline(FlashListener.NULL)
                .images().stream()
                .filter(image -> ours.contains(image.fileName()))
                .collect(Collectors.toList());
    }

    @Test
    void readsAWellFormedManifest(@TempDir Path folder, @TempDir Path cache) throws Exception {
        Files.writeString(folder.resolve("a.hex"), ":00000001FF\n");
        List<FirmwareImage> images = loadFixtures(folder, cache, """
                {
                  "schemaVersion": 1,
                  "firmware": [
                    {"revision": "SOUNDNET_V1_R6", "version": "99.1.0", "file": "a.hex",
                     "notes": "Field lockup fixes", "released": "2026-08-31",
                     "recommended": true}
                  ]
                }
                """);

        assertEquals(1, images.size());
        FirmwareImage image = images.get(0);
        assertEquals(BoardRevision.SOUNDNET_V1_R6, image.revision());
        assertEquals("99.1.0", image.version());
        assertTrue(image.recommended());
        assertTrue(image.availableOffline());
        assertTrue(image.detail().contains("Field lockup fixes"));
    }

    @Test
    void skipsEntriesWhoseFileIsMissing(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        assertEquals(0, loadFixtures(folder, cache, """
                {"schemaVersion": 1, "firmware": [
                  {"revision": "SOUNDNET_V1_R6", "version": "99.1.0", "file": "absent.hex"}]}
                """).size());
    }

    @Test
    void skipsUnknownHardwareRevisions(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        Files.writeString(folder.resolve("a.hex"), ":00000001FF\n");
        assertEquals(0, loadFixtures(folder, cache, """
                {"schemaVersion": 1, "firmware": [
                  {"revision": "SOUNDNET_V9_R9", "version": "99.1.0", "file": "a.hex"}]}
                """).size());
    }

    @Test
    void survivesAManifestThatIsNotValidJson(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        Files.writeString(folder.resolve("a.hex"), ":00000001FF\n");
        assertEquals(0, loadFixtures(folder, cache, "this is not json").size());
    }

    @Test
    void ordersRecommendedFirstThenNewest(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        for (String name : List.of("old.hex", "new.hex", "rec.hex")) {
            Files.writeString(folder.resolve(name), ":00000001FF\n");
        }
        List<FirmwareImage> images = loadFixtures(folder, cache, """
                {"schemaVersion": 1, "firmware": [
                  {"revision": "SOUNDNET_V1_R6", "version": "99.0.6",  "file": "old.hex"},
                  {"revision": "SOUNDNET_V1_R6", "version": "99.0.10", "file": "new.hex"},
                  {"revision": "SOUNDNET_V1_R5", "version": "99.0.1",  "file": "rec.hex",
                   "recommended": true}]}
                """);

        assertEquals(3, images.size());
        assertTrue(images.get(0).recommended(), "recommended build should come first");
        assertEquals("99.0.10", images.get(1).version(), "then the newest version");
        assertEquals("99.0.6", images.get(2).version());
    }

    /** A newer schema should warn, not throw away everything it can still read. */
    @Test
    void stillReadsEntriesFromANewerSchema(@TempDir Path folder, @TempDir Path cache)
            throws Exception {
        Files.writeString(folder.resolve("a.hex"), ":00000001FF\n");
        assertEquals(1, loadFixtures(folder, cache, """
                {"schemaVersion": 99, "firmware": [
                  {"revision": "SOUNDNET_V1_R6", "version": "99.1.0", "file": "a.hex"}]}
                """).size());
    }

    /** The real bundled firmware must parse, or the offline fallback is useless. */
    @Test
    void bundledFirmwareIsUsable(@TempDir Path cache) {
        List<FirmwareImage> images = new FirmwareCatalog(new FirmwareCache(cache))
                .loadOffline(FlashListener.NULL).images();

        assertTrue(images.size() >= 2,
                "the application should ship firmware for the deployed revisions");
        assertTrue(images.stream().allMatch(FirmwareImage::availableOffline));
        assertTrue(images.stream().anyMatch(i -> i.revision() == BoardRevision.SOUNDNET_V1_R5));
        assertTrue(images.stream().anyMatch(i -> i.revision() == BoardRevision.SOUNDNET_V1_R6));
    }

    /**
     * Every bundled image must parse and fit below the bootloader. Catching a bad
     * image here is worth a great deal more than catching it on a boat.
     */
    @Test
    void bundledFirmwareParsesAndFitsInFlash(@TempDir Path cache) throws Exception {
        List<FirmwareImage> images = new FirmwareCatalog(new FirmwareCache(cache))
                .loadOffline(FlashListener.NULL).images();

        for (FirmwareImage image : images) {
            IntelHex hex = image.load(FlashListener.NULL);
            assertTrue(hex.size() > 0, image + " is empty");
            assertTrue(hex.size() <= Avr109Programmer.APPLICATION_MAX_BYTES,
                    image + " is " + hex.size() + " bytes, which would overwrite the bootloader");
        }
    }
}
