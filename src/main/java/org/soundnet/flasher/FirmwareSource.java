package org.soundnet.flasher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the bytes of a firmware image actually come from.
 *
 * <p>Three kinds, in order of how much can go wrong: shipped inside the
 * application, sitting in a folder on disk, or waiting on the firmware
 * repository and not yet downloaded.
 */
public interface FirmwareSource {

    /** Reads the image, downloading it first if necessary. */
    byte[] fetch(FlashListener listener) throws FlashException;

    /** True when this image can be installed with no network connection. */
    boolean availableOffline();

    /** Short phrase for the UI, e.g. "included with this app". */
    String describe();

    /** An image compiled into the application - the offline fallback. */
    final class Bundled implements FirmwareSource {
        private final String resource;

        Bundled(String resource) {
            this.resource = resource;
        }

        @Override
        public byte[] fetch(FlashListener listener) throws FlashException {
            try (InputStream in = Bundled.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new FlashException("The firmware file " + resource
                            + " is missing from this copy of the updater. Please reinstall it.");
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new FlashException("Could not read the built-in firmware: "
                        + e.getMessage(), e);
            }
        }

        @Override
        public boolean availableOffline() {
            return true;
        }

        @Override
        public String describe() {
            return "included with this app";
        }
    }

    /** An image in the optional firmware folder beside the application. */
    final class LocalFile implements FirmwareSource {
        private final Path path;

        LocalFile(Path path) {
            this.path = path;
        }

        @Override
        public byte[] fetch(FlashListener listener) throws FlashException {
            try {
                return Files.readAllBytes(path);
            } catch (IOException e) {
                throw new FlashException("Could not read " + path + ": " + e.getMessage(), e);
            }
        }

        @Override
        public boolean availableOffline() {
            return true;
        }

        @Override
        public String describe() {
            return "from " + path.getFileName();
        }
    }

    /**
     * An image published in the firmware repository. Downloaded on demand,
     * checked against the SHA-256 in the manifest, and kept in a local cache so
     * that the next trip to the field does not need a connection.
     */
    final class Remote implements FirmwareSource {
        private final URI uri;
        private final String fileName;
        private final String sha256;
        private final FirmwareCache cache;

        Remote(URI uri, String fileName, String sha256, FirmwareCache cache) {
            this.uri = uri;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.cache = cache;
        }

        @Override
        public byte[] fetch(FlashListener listener) throws FlashException {
            byte[] cached = cache.read(fileName, sha256);
            if (cached != null) {
                listener.log("Using the copy of " + fileName + " already downloaded.");
                return cached;
            }
            listener.stage("Downloading firmware...");
            byte[] downloaded = RemoteCatalog.download(uri, sha256, listener);
            cache.write(fileName, downloaded);
            return downloaded;
        }

        @Override
        public boolean availableOffline() {
            return cache.read(fileName, sha256) != null;
        }

        @Override
        public String describe() {
            return availableOffline() ? "downloaded" : "downloads when you press Update";
        }
    }
}
