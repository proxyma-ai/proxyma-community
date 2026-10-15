package ai.proxyma.core.system.parts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The desktop's downloaded parts: the manifest format, the store's switch-and-roll-back rules, the
 * download (resume, digest, a server that ignores Range) and the release verification that decides
 * whether any of it is trusted. All against a fake transport - no network.
 */
class PartsTest {

    private static final String BASE = "https://example.test/parts/";

    // -- Manifest --------------------------------------------------------------

    @Test
    @DisplayName("a manifest reads back exactly as it was written")
    void manifestRoundTrips() {
        PartsManifest m = new PartsManifest("0.17.0", "windows-x64", BASE, List.of(
                part("app", "aaaaaaaaaaaaaaaa", 10), part("python", "bbbbbbbbbbbbbbbb", 20)));
        assertEquals(m, PartsManifest.parse(m.format()));
        assertEquals(30, m.totalSize());
    }

    @Test
    @DisplayName("names that would leave the parts directory, and malformed lines, are refused")
    void manifestRefusesHostileValues() {
        String good = "proxyma-parts 1\nversion 1\nplatform windows-x64\nbase " + BASE + "\n";
        String sha = "a".repeat(64);
        for (String bad : List.of(
                "part .. aaaaaaaa x.zip " + sha + " 1",
                "part app ../../etc x.zip " + sha + " 1",
                "part app aaaaaaaa ../x.zip " + sha + " 1",
                "part app aaaaaaaa x.zip nothex 1",
                "part app aaaaaaaa x.zip " + sha + " -5",
                "part app aaaaaaaa x.zip " + sha)) {
            assertThrows(IllegalArgumentException.class, () -> PartsManifest.parse(good + bad), bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> PartsManifest.parse("proxyma-parts 1\nversion 1\nplatform w\nbase http://plain/\n"));
        assertThrows(IllegalArgumentException.class, () -> PartsManifest.parse("hello\n"));
    }

    // -- Store -----------------------------------------------------------------

    @Test
    @DisplayName("activating keeps the previous release, and a rollback returns to it")
    void activateAndRollBack(@TempDir Path tmp) throws Exception {
        PartsStore store = new PartsStore(tmp);
        PartsManifest v1 = manifest("1", part("app", "1111111111111111", 1));
        PartsManifest v2 = manifest("2", part("app", "2222222222222222", 1));
        install(store, v1);
        install(store, v2);
        store.activate(v1);
        store.activate(v2);
        assertEquals("2", store.active().orElseThrow().version());
        assertEquals("1", store.previous().orElseThrow().version());
        assertEquals("1", store.rollback().orElseThrow().version());
        assertEquals("2", store.rejectedVersion().orElseThrow(), "the failed release was not remembered");
        assertEquals("1", store.active().orElseThrow().version());
        assertTrue(store.previous().isEmpty());
    }

    @Test
    @DisplayName("a staged release is switched to only when every part of it is on disk")
    void stagingNeedsEveryPart(@TempDir Path tmp) throws Exception {
        PartsStore store = new PartsStore(tmp);
        PartsManifest v1 = manifest("1", part("app", "1111111111111111", 1));
        PartsManifest v2 = manifest("2", part("app", "2222222222222222", 1), part("python", "3333333333333333", 1));
        install(store, v1);
        store.activate(v1);
        assertThrows(IOException.class, () -> store.stage(v2));
        install(store, v2);
        store.stage(v2);
        // A part deleted between staging and the restart: stay on what works.
        PartsStore.deleteRecursively(store.dir(v2.parts().get(1)));
        assertTrue(store.promoteStaged().isEmpty());
        assertEquals("1", store.active().orElseThrow().version());
        install(store, v2);
        store.stage(v2);
        assertEquals("2", store.promoteStaged().orElseThrow().version());
        assertTrue(store.staged().isEmpty());
    }

    @Test
    @DisplayName("a staged release no newer than the active one is not switched to - only a rollback goes back")
    void stagingNeverDowngrades(@TempDir Path tmp) throws Exception {
        PartsStore store = new PartsStore(tmp);
        PartsManifest v1 = manifest("0.30.0", part("app", "1111111111111111", 1));
        PartsManifest v2 = manifest("0.31.0", part("app", "2222222222222222", 1));
        install(store, v1);
        install(store, v2);
        store.activate(v2);
        // platform-02: an old release's genuine files, staged under a newer tag.
        store.stage(v1);
        assertTrue(store.promoteStaged().isEmpty(), "an older release was promoted over a newer one");
        assertEquals("0.31.0", store.active().orElseThrow().version());
        assertTrue(store.staged().isEmpty(), "the refused release stayed staged");
        // The same version again is not an update either.
        store.stage(v2);
        assertTrue(store.promoteStaged().isEmpty());
    }

    @Test
    @DisplayName("cleaning up removes only parts no manifest names")
    void garbageCollection(@TempDir Path tmp) throws Exception {
        PartsStore store = new PartsStore(tmp);
        PartsManifest v1 = manifest("1", part("app", "1111111111111111", 1));
        PartsManifest v2 = manifest("2", part("app", "2222222222222222", 1));
        PartsManifest v3 = manifest("3", part("app", "3333333333333333", 1));
        install(store, v1);
        install(store, v2);
        install(store, v3);
        store.activate(v1);
        store.activate(v2);
        store.collectGarbage();
        assertTrue(store.installed(v1.parts().get(0)), "the rollback target was removed");
        assertTrue(store.installed(v2.parts().get(0)));
        assertFalse(store.installed(v3.parts().get(0)));
    }

    // -- Fetcher ---------------------------------------------------------------

    @Test
    @DisplayName("a part is downloaded, checked and extracted into its own directory")
    void fetchesAndInstalls(@TempDir Path tmp) throws Exception {
        byte[] zip = zip(Map.of("app.jar", "jar bytes", "window-app/main.js", "js"));
        FakeTransport web = new FakeTransport().serve(BASE + "app.zip", zip);
        PartsStore store = new PartsStore(tmp);
        PartsManifest m = manifest("1", new PartsManifest.Part("app", "abcdef0123456789", "app.zip", PartsFetcher.sha256(zip), zip.length));
        List<Long> progress = new ArrayList<>();
        new PartsFetcher(store, web).installMissing(m, (p, done, total) -> progress.add(done), () -> false);
        Path dir = store.dir(m.parts().get(0));
        assertEquals("jar bytes", Files.readString(dir.resolve("app.jar")));
        assertEquals("js", Files.readString(dir.resolve("window-app/main.js")));
        assertEquals(zip.length, progress.get(progress.size() - 1));
        assertFalse(Files.exists(tmp.resolve(PartsStore.DOWNLOADS).resolve("app.zip.part")));
    }

    @Test
    @DisplayName("a partial download is resumed with a Range request, not started again")
    void resumes(@TempDir Path tmp) throws Exception {
        byte[] zip = zip(Map.of("a.txt", "x".repeat(5000)));
        FakeTransport web = new FakeTransport().serve(BASE + "app.zip", zip);
        PartsStore store = new PartsStore(tmp);
        Files.createDirectories(tmp.resolve(PartsStore.DOWNLOADS));
        Files.write(tmp.resolve(PartsStore.DOWNLOADS).resolve("app.zip.part"), java.util.Arrays.copyOf(zip, 100));
        PartsManifest m = manifest("1", new PartsManifest.Part("app", "abcdef0123456789", "app.zip", PartsFetcher.sha256(zip), zip.length));
        new PartsFetcher(store, web).installMissing(m, (p, d, t) -> {}, () -> false);
        assertEquals(List.of(100L), web.offsets);
        assertTrue(store.installed(m.parts().get(0)));
    }

    @Test
    @DisplayName("a server that ignores Range gets the file from the start, and it still verifies")
    void rangeIgnored(@TempDir Path tmp) throws Exception {
        byte[] zip = zip(Map.of("a.txt", "hello"));
        FakeTransport web = new FakeTransport().serve(BASE + "app.zip", zip);
        web.honorRange = false;
        PartsStore store = new PartsStore(tmp);
        Files.createDirectories(tmp.resolve(PartsStore.DOWNLOADS));
        Files.write(tmp.resolve(PartsStore.DOWNLOADS).resolve("app.zip.part"), java.util.Arrays.copyOf(zip, 10));
        PartsManifest m = manifest("1", new PartsManifest.Part("app", "abcdef0123456789", "app.zip", PartsFetcher.sha256(zip), zip.length));
        new PartsFetcher(store, web).installMissing(m, (p, d, t) -> {}, () -> false);
        assertEquals("hello", Files.readString(store.dir(m.parts().get(0)).resolve("a.txt")));
    }

    @Test
    @DisplayName("bytes that fail the digest are never installed")
    void digestMismatchRefused(@TempDir Path tmp) throws Exception {
        byte[] zip = zip(Map.of("a.txt", "hello"));
        FakeTransport web = new FakeTransport().serve(BASE + "app.zip", zip);
        PartsStore store = new PartsStore(tmp);
        PartsManifest m = manifest("1", new PartsManifest.Part("app", "abcdef0123456789", "app.zip", "0".repeat(64), zip.length));
        PartsFetcher fetcher = new PartsFetcher(store, web);
        fetcher.retryPauseMillis = 1;
        assertThrows(IOException.class, () -> fetcher.installMissing(m, (p, d, t) -> {}, () -> false));
        assertFalse(store.installed(m.parts().get(0)));
    }

    @Test
    @DisplayName("an archive entry that climbs out of its directory is refused")
    void zipSlipRefused(@TempDir Path tmp) throws Exception {
        byte[] zip = zip(Map.of("../evil.txt", "x"));
        Path archive = tmp.resolve("a.zip");
        Files.write(archive, zip);
        assertThrows(IOException.class, () -> PartsFetcher.unzip(archive, tmp.resolve("out")));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    // -- Release verification --------------------------------------------------

    @Test
    @DisplayName("a manifest is trusted only through a signed checksums file that lists it")
    void releaseVerification() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair release = gen.generateKeyPair(), dev = gen.generateKeyPair(), stranger = gen.generateKeyPair();
        ReleaseSignatures keys = new ReleaseSignatures(List.of(release.getPublic()), List.of(dev.getPublic()));
        byte[] manifest = manifest("1", part("app", "1111111111111111", 1)).format().getBytes(StandardCharsets.UTF_8);
        String name = PartsManifest.assetName("windows-x64");
        byte[] sums = (PartsFetcher.sha256(manifest) + "  " + name + "\n").getBytes(StandardCharsets.UTF_8);

        assertEquals("1", PartsRelease.verify(name, manifest, sums, sign(release, sums), "windows-x64", keys, false).version());
        // A dev key counts only when asked for.
        assertThrows(IOException.class, () -> PartsRelease.verify(name, manifest, sums, sign(dev, sums), "windows-x64", keys, false));
        PartsRelease.verify(name, manifest, sums, sign(dev, sums), "windows-x64", keys, true);
        // Nobody else's key, ever.
        assertThrows(IOException.class, () -> PartsRelease.verify(name, manifest, sums, sign(stranger, sums), "windows-x64", keys, true));
        // A manifest that is not the one the checksums vouch for.
        byte[] altered = manifest.clone();
        altered[altered.length - 2] = '9';
        assertThrows(IOException.class, () -> PartsRelease.verify(name, altered, sums, sign(release, sums), "windows-x64", keys, false));
        // Another platform's manifest.
        assertThrows(IOException.class, () -> PartsRelease.verify(name, manifest, sums, sign(release, sums), "linux-x64", keys, false));
    }

    @Test
    @DisplayName("a genuine release published under another version's tag is refused (platform-02)")
    void aReleaseIsTheVersionItIsFetchedAs() throws Exception {
        KeyPair release = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ReleaseSignatures keys = new ReleaseSignatures(List.of(release.getPublic()), List.of());
        // A real, signed 0.30.0, copied whole under the tag v0.99.0.
        String rel = "https://example.test/v0.99.0/";
        byte[] old = manifest("0.30.0", part("app", "1111111111111111", 1)).format().getBytes(StandardCharsets.UTF_8);
        byte[] sums = (PartsFetcher.sha256(old) + "  parts-windows-x64.txt\n").getBytes(StandardCharsets.UTF_8);
        FakeTransport replayed = new FakeTransport()
                .serve(rel + "parts-windows-x64.txt", old)
                .serve(rel + "SHA256SUMS.txt", sums)
                .serve(rel + ReleaseSignatures.SIGNATURE_ASSET, sign(release, sums));

        IOException e = assertThrows(IOException.class,
                () -> PartsRelease.fetch(replayed, rel, "windows-x64", keys, false, "v0.99.0"));
        assertTrue(e.getMessage().contains("0.30.0"), e.getMessage());
        // Asked for as what it is, it is fine - and a leading v on either side does not matter.
        assertEquals("0.30.0", PartsRelease.fetch(replayed, rel, "windows-x64", keys, false, "v0.30.0").version());
        // "The newest release" names no version, and is checked by the caller instead.
        assertEquals("0.30.0", PartsRelease.fetch(replayed, rel, "windows-x64", keys, false, null).version());
    }

    @Test
    @DisplayName("a release with no manifest is not published; one with no signature is refused as unsigned")
    void unpublishedAndUnsignedDiffer() throws Exception {
        ReleaseSignatures keys = new ReleaseSignatures(List.of(), List.of());
        String rel = "https://example.test/v1/";
        FakeTransport nothing = new FakeTransport();
        assertThrows(PartsRelease.NotPublished.class,
                () -> PartsRelease.fetch(nothing, rel, "windows-x64", keys, false, null));
        FakeTransport unsigned = new FakeTransport()
                .serve(rel + "parts-windows-x64.txt", manifest("1").format().getBytes(StandardCharsets.UTF_8))
                .serve(rel + "SHA256SUMS.txt", "x".getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class,
                () -> PartsRelease.fetch(unsigned, rel, "windows-x64", keys, false, null));
        assertFalse(e instanceof PartsRelease.NotPublished, "an unsigned release read as an unpublished one");
    }

    // -- Helpers ---------------------------------------------------------------

    private static PartsManifest.Part part(String name, String id, long size) {
        return new PartsManifest.Part(name, id, "proxyma-" + name + "-" + id + ".zip", "a".repeat(64), size);
    }

    private static PartsManifest manifest(String version, PartsManifest.Part... parts) {
        return new PartsManifest(version, "windows-x64", BASE, List.of(parts));
    }

    private static void install(PartsStore store, PartsManifest m) throws IOException {
        for (PartsManifest.Part p : m.parts()) Files.createDirectories(store.dir(p));
    }

    private static byte[] sign(KeyPair key, byte[] data) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key.getPrivate());
        s.update(data);
        return s.sign();
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static final class FakeTransport implements PartsTransport {
        final Map<String, byte[]> files = new HashMap<>();
        final List<Long> offsets = new ArrayList<>();
        boolean honorRange = true;

        FakeTransport serve(String url, byte[] body) {
            files.put(url, body);
            return this;
        }

        @Override
        public <T> T get(URI url, long offset, ResponseHandler<T> handler) throws IOException {
            byte[] body = files.get(url.toString());
            if (body == null) return handler.handle(404, 0, InputStream.nullInputStream());
            if (offset > 0) offsets.add(offset);
            if (offset > 0 && honorRange) {
                byte[] rest = java.util.Arrays.copyOfRange(body, (int) offset, body.length);
                return handler.handle(206, rest.length, new ByteArrayInputStream(rest));
            }
            return handler.handle(200, body.length, new ByteArrayInputStream(body));
        }
    }
}
