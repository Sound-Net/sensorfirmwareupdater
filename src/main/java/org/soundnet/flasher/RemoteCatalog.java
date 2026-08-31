package org.soundnet.flasher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the firmware repository.
 *
 * <p>The repository is an ordinary git repository served over raw
 * githubusercontent - no releases API, no authentication, no rate limit worth
 * worrying about. Publishing new firmware is a commit.
 *
 * <p>Point it somewhere else with {@code -Dsoundnet.firmware.repository=...} if
 * the repository ever moves or an institution mirrors it internally.
 */
public final class RemoteCatalog {

    /** Where firmware is published, ending in a slash. */
    public static final String DEFAULT_BASE_URL =
            "https://raw.githubusercontent.com/Sound-Net/sensor_firmware_binary/main/";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private RemoteCatalog() {
    }

    public static URI baseUri() {
        String configured = System.getProperty("soundnet.firmware.repository", DEFAULT_BASE_URL);
        return URI.create(configured.endsWith("/") ? configured : configured + "/");
    }

    public static URI manifestUri() {
        return baseUri().resolve("manifest.json");
    }

    public static URI firmwareUri(String fileName) {
        return baseUri().resolve("firmware/" + fileName);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** @return the manifest as text, or null if the repository is unreachable. */
    public static String fetchManifest(FlashListener listener) {
        URI uri = manifestUri();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                listener.log("Firmware repository returned HTTP " + response.statusCode()
                        + " for " + uri);
                return null;
            }
            return response.body();
        } catch (IOException e) {
            listener.log("Could not reach the firmware repository: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Downloads one firmware file and checks it against the manifest's hash. */
    public static byte[] download(URI uri, String expectedSha256, FlashListener listener)
            throws FlashException {
        listener.log("Downloading " + uri);
        HttpResponse<byte[]> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            response = client().send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new FlashException(
                    "This firmware has not been downloaded yet, and the firmware repository "
                            + "could not be reached.\n\n"
                            + "Connect to the internet and try again, or choose one of the "
                            + "firmware versions marked as included with this app.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlashException("The download was interrupted.", e);
        }

        if (response.statusCode() != 200) {
            throw new FlashException("The firmware could not be downloaded (HTTP "
                    + response.statusCode() + "). It may have been removed from the repository.");
        }

        byte[] bytes = response.body();
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String actual = FirmwareCache.sha256(bytes);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new FlashException(
                        "The downloaded firmware did not match its published checksum, so it has "
                                + "been discarded rather than written to the sensor.\n\n"
                                + "Expected " + expectedSha256 + "\nbut got  " + actual);
            }
            listener.log("Checksum verified.");
        } else {
            listener.log("Warning: the manifest lists no checksum for this file.");
        }
        return bytes;
    }
}
