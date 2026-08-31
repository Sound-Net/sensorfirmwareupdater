package org.soundnet.flasher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serves a stand-in firmware repository over real HTTP, so the fetch, download,
 * checksum and cache path is exercised the way it will run in the field rather
 * than against a mock.
 */
class RemoteCatalogTest {

    private static final byte[] HEX = ":00000001FF\n".getBytes(StandardCharsets.US_ASCII);

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Two entries on purpose: one that duplicates a build already bundled with
        // the application, and one that only exists in the repository.
        server.createContext("/manifest.json", exchange -> respond(exchange, ("""
                {"schemaVersion": 1, "firmware": [
                  {"revision": "SOUNDNET_V1_R6", "version": "2.0.7",
                   "file": "build.hex", "sha256": "%1$s"},
                  {"revision": "SOUNDNET_V1_R6", "version": "2.0.99",
                   "file": "build.hex", "sha256": "%1$s"}]}
                """.formatted(FirmwareCache.sha256(HEX))).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/firmware/build.hex", exchange -> respond(exchange, HEX));
        server.start();

        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        System.setProperty("soundnet.firmware.repository", baseUrl);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @AfterEach
    void stopServer() {
        System.clearProperty("soundnet.firmware.repository");
        server.stop(0);
    }

    @Test
    void buildsUrlsFromTheConfiguredBase() {
        assertEquals(baseUrl + "manifest.json", RemoteCatalog.manifestUri().toString());
        assertEquals(baseUrl + "firmware/build.hex",
                RemoteCatalog.firmwareUri("build.hex").toString());
    }

    /** A base URL without its trailing slash must not swallow the last segment. */
    @Test
    void toleratesABaseUrlWithoutATrailingSlash() {
        System.setProperty("soundnet.firmware.repository", "https://example.org/fw");
        assertEquals("https://example.org/fw/manifest.json",
                RemoteCatalog.manifestUri().toString());
    }

    @Test
    void fetchesTheManifest() {
        String json = RemoteCatalog.fetchManifest(FlashListener.NULL);
        assertNotNull(json);
        assertTrue(json.contains("SOUNDNET_V1_R6"));
    }

    @Test
    void returnsNullWhenTheRepositoryIsUnreachable() {
        System.setProperty("soundnet.firmware.repository", "http://127.0.0.1:1/");
        assertNull(RemoteCatalog.fetchManifest(FlashListener.NULL));
    }

    @Test
    void downloadsAndVerifiesTheChecksum() throws Exception {
        byte[] bytes = RemoteCatalog.download(RemoteCatalog.firmwareUri("build.hex"),
                FirmwareCache.sha256(HEX), FlashListener.NULL);
        assertArrayEquals(HEX, bytes);
    }

    @Test
    void refusesADownloadWhoseChecksumIsWrong() {
        FlashException e = assertThrows(FlashException.class,
                () -> RemoteCatalog.download(RemoteCatalog.firmwareUri("build.hex"),
                        "0".repeat(64), FlashListener.NULL));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
    }

    @Test
    void reportsAMissingFileClearly() {
        FlashException e = assertThrows(FlashException.class,
                () -> RemoteCatalog.download(RemoteCatalog.firmwareUri("absent.hex"),
                        null, FlashListener.NULL));
        assertTrue(e.getMessage().contains("could not be downloaded"), e.getMessage());
    }

    /** The whole point of the cache: online once, installable thereafter. */
    @Test
    void aDownloadedImageBecomesAvailableOffline(@TempDir Path cacheDir) throws Exception {
        FirmwareCache cache = new FirmwareCache(cacheDir);
        FirmwareSource.Remote source = new FirmwareSource.Remote(
                RemoteCatalog.firmwareUri("build.hex"), "build.hex",
                FirmwareCache.sha256(HEX), cache);

        assertFalse(source.availableOffline(), "nothing is cached yet");
        assertArrayEquals(HEX, source.fetch(FlashListener.NULL));
        assertTrue(source.availableOffline(), "the download should have been cached");

        // With the repository gone, the cached copy must still serve.
        server.stop(0);
        assertArrayEquals(HEX, source.fetch(FlashListener.NULL));
    }

    @Test
    void onlineCatalogListsWhatTheRepositoryPublishes(@TempDir Path cacheDir) {
        FirmwareCatalog.Result result = new FirmwareCatalog(new FirmwareCache(cacheDir))
                .fetchOnline(FlashListener.NULL);

        assertTrue(result.online());
        FirmwareImage published = result.images().stream()
                .filter(i -> "2.0.99".equals(i.version()))
                .findFirst().orElse(null);
        assertNotNull(published, "the repository-only build should appear in the list");
        assertEquals("build.hex", published.fileName());
        assertFalse(published.availableOffline(), "it has not been downloaded yet");
    }

    /**
     * When the repository publishes a build the application already ships, the
     * local copy wins - there is no reason to make someone download bytes they
     * already have, least of all on a marginal connection.
     */
    @Test
    void prefersTheBundledCopyOverAnIdenticalPublishedBuild(@TempDir Path cacheDir) {
        FirmwareCatalog.Result result = new FirmwareCatalog(new FirmwareCache(cacheDir))
                .fetchOnline(FlashListener.NULL);

        FirmwareImage duplicated = result.images().stream()
                .filter(i -> i.revision() == BoardRevision.SOUNDNET_V1_R6
                        && "2.0.7".equals(i.version()))
                .findFirst().orElse(null);
        assertNotNull(duplicated);
        assertTrue(duplicated.availableOffline());
        assertEquals("soundnet_firmware-2.0.7-SOUNDNET_V1_R6.hex", duplicated.fileName(),
                "the bundled file should have been kept, not the published one");
    }
}
