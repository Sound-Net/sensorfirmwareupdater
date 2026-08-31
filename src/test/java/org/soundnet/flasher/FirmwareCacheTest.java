package org.soundnet.flasher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FirmwareCacheTest {

    private static final byte[] PAYLOAD = "firmware bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsAFileThatMatchesItsChecksum(@TempDir Path dir) {
        FirmwareCache cache = new FirmwareCache(dir);
        cache.write("build.hex", PAYLOAD);
        assertArrayEquals(PAYLOAD, cache.read("build.hex", FirmwareCache.sha256(PAYLOAD)));
    }

    @Test
    void refusesAndDeletesAFileThatFailsItsChecksum(@TempDir Path dir) {
        FirmwareCache cache = new FirmwareCache(dir);
        cache.write("build.hex", PAYLOAD);

        String wrongHash = FirmwareCache.sha256("something else".getBytes(StandardCharsets.UTF_8));
        assertNull(cache.read("build.hex", wrongHash),
                "a file that fails its checksum must never be handed back");
        assertFalse(Files.exists(dir.resolve("build.hex")),
                "the corrupt file should have been removed so it is fetched again");
    }

    @Test
    void missingFileReadsAsNull(@TempDir Path dir) {
        assertNull(new FirmwareCache(dir).read("absent.hex", null));
    }

    /** Manifests come off the network, so a file name must not escape the cache. */
    @Test
    void pathTraversalInAFileNameIsContained(@TempDir Path dir) {
        FirmwareCache cache = new FirmwareCache(dir);
        cache.write("../../escaped.hex", PAYLOAD);

        assertFalse(Files.exists(dir.resolve("../../escaped.hex").normalize()),
                "writing must not escape the cache directory");
        assertNotNull(cache.read("../../escaped.hex", FirmwareCache.sha256(PAYLOAD)));
    }

    @Test
    void storesAndReturnsTheManifest(@TempDir Path dir) {
        FirmwareCache cache = new FirmwareCache(dir);
        assertNull(cache.readManifest());
        cache.writeManifest("{\"schemaVersion\":1}");
        assertEquals("{\"schemaVersion\":1}", cache.readManifest());
    }

    @Test
    void sha256MatchesTheKnownDigestOfTheEmptyInput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                FirmwareCache.sha256(new byte[0]));
    }
}
