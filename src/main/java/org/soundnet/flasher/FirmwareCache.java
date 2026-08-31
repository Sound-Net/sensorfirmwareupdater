package org.soundnet.flasher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Keeps downloaded firmware, and the last manifest we saw, on disk.
 *
 * <p>The point is the second trip. A researcher downloads firmware once while
 * they still have a connection; on the boat, with no signal, the same builds are
 * still in the drop-down and still installable.
 *
 * <p>Everything read back out is checked against the SHA-256 from the manifest,
 * so a truncated download or a tampered cache file is never written to a sensor.
 */
public final class FirmwareCache {

    private final Path directory;

    public FirmwareCache() {
        this(defaultDirectory());
    }

    FirmwareCache(Path directory) {
        this.directory = directory;
    }

    private static Path defaultDirectory() {
        String override = System.getProperty("soundnet.cache.dir");
        if (override != null) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"))
                .resolve(".soundnet-firmware-updater").resolve("cache");
    }

    /**
     * @return the cached file's bytes, or null if it is absent or fails its
     *         checksum.
     */
    public byte[] read(String fileName, String expectedSha256) {
        Path path = directory.resolve(sanitise(fileName));
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (expectedSha256 != null && !sha256(bytes).equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(path); // corrupt; fetch it again
                return null;
            }
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }

    public void write(String fileName, byte[] bytes) {
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(sanitise(fileName)), bytes);
        } catch (IOException e) {
            // A cache miss next time is not worth failing an update over.
        }
    }

    /** Stores the manifest so the online build list survives going offline. */
    public void writeManifest(String json) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("manifest.json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Not fatal.
        }
    }

    /** @return the last manifest we successfully fetched, or null. */
    public String readManifest() {
        Path path = directory.resolve("manifest.json");
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /** Manifests come off the network, so never let a name escape the cache dir. */
    private static String sanitise(String fileName) {
        return Paths.get(fileName).getFileName().toString();
    }
}
