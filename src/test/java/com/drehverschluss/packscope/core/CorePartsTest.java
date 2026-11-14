package com.drehverschluss.packscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Smaller parts of the core: file kinds, locations, filter, parsers, broken input, caches, report. */
class CorePartsTest {
    @TempDir
    Path tmp;

    @Test
    void fileKinds() {
        assertEquals(FileKind.WHOLE, FileKind.of("minecraft:textures/item/diamond.png"));
        assertEquals(FileKind.MCMETA, FileKind.of("minecraft:textures/block/water_still.png.mcmeta"));
        assertEquals(FileKind.LANG, FileKind.of("minecraft:lang/de_de.json"));
        assertEquals(FileKind.WHOLE, FileKind.of("minecraft:lang/sub/de_de.json"));
        assertEquals(FileKind.SOUNDS, FileKind.of("minecraft:sounds.json"));
        assertEquals(FileKind.WHOLE, FileKind.of("minecraft:sounds/ambient/cave1.ogg"));
        assertEquals(FileKind.BLOCKSTATE, FileKind.of("minecraft:blockstates/stone.json"));
        assertEquals(FileKind.MERGED, FileKind.of("minecraft:atlases/blocks.json"));
        assertEquals(FileKind.MERGED, FileKind.of("minecraft:font/default.json"));
        assertEquals(FileKind.WHOLE, FileKind.of("minecraft:font/ascii.png"));
        assertEquals("de_de", FileKind.language("minecraft:lang/de_de.json"));
    }

    @Test
    void invalidPathsAreSkippedLikeVanilla() {
        assertEquals("minecraft:textures/a.png", Locations.fromAssetPath("minecraft/textures/a.png"));
        assertNull(Locations.fromAssetPath("neoforge/lang/README.txt"), "upper case");
        assertNull(Locations.fromAssetPath("Bad/textures/a.png"));
        assertNull(Locations.fromAssetPath("file_in_assets.txt"));
    }

    @Test
    void ignoredGlobs() {
        Locations.Ignored ig = new Locations.Ignored(List.of("minecraft:textures/gui/title/*", "*.txt", " "));
        assertTrue(ig.matches("minecraft:textures/gui/title/minecraft.png"));
        assertTrue(ig.matches("minecraft:textures/gui/title/background/panorama_0.png"));
        assertFalse(ig.matches("other:textures/gui/title/minecraft.png"));
        assertTrue(ig.matches("packscope_test:texts/a.txt"));
        assertFalse(ig.matches("minecraft:textures/item/diamond.png"));
    }

    @Test
    void filterLikeVanilla() {
        PackFilter f = PackFilter.parse(meta("{\"block\":[{\"namespace\":\"minecraft\",\"path\":\"textures/item/\"},{\"path\":\"\\\\.ogg$\"}]}"));
        assertNotNull(f);
        assertTrue(f.hides("minecraft:textures/item/diamond.png"), "regex find, not full match");
        assertTrue(f.hides("minecraft:sounds/a.ogg"));
        // vanilla checks namespace and path against any entry separately: entry 2 (no namespace) matches every namespace
        assertTrue(f.hides("other:textures/item/x.png"));
        assertFalse(f.hides("minecraft:textures/block/stone.png"));
        assertNull(PackFilter.parse(meta(null)));
    }

    private static byte[] meta(String filter) {
        String s = "{\"pack\":{\"pack_format\":34,\"description\":\"x\"}" + (filter == null ? "" : ",\"filter\":" + filter) + "}";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsers() {
        Map<String, String> lang = JsonMergers.lang("{\"a\":\"A\",\"b\":[{\"text\":\"B\"}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("A", lang.get("a"));
        assertTrue(lang.get("b").contains("text"));
        Map<String, JsonMergers.SoundEvent> s = JsonMergers.sounds(
            "{\"e\":{\"replace\":true,\"sounds\":[\"x:a\",{\"name\":\"x:b\",\"volume\":0.5}]}}".getBytes(StandardCharsets.UTF_8));
        assertTrue(s.get("e").replace());
        assertEquals(List.of("x:a", "x:b"), s.get("e").sounds());
    }

    @Test
    void brokenJsonIsAWarningNotACrash() throws IOException {
        Path low = zip("low.zip", Map.of(
            "assets/t/lang/en_us.json", "{\"k\":\"low\"}",
            "assets/t/sounds.json", "{\"e\":{\"sounds\":[\"t:a\"]}}"));
        Path high = zip("high.zip", Map.of(
            "pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"x\"},\"filter\":{\"block\":[{\"path\":\"[\"}]}}",
            "assets/t/lang/en_us.json", "{\"k\": broken",
            "assets/t/sounds.json", "[not an object"));
        PackOrder order = new PackOrder(List.of(ref("high", high), ref("low", low)));
        ScanResult r = PackEvaluator.evaluate(order, new IndexCache(null), PackscopeSettings.DEFAULTS);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("broken language file")), r.warnings().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("broken sounds.json")), r.warnings().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("broken filter")), r.warnings().toString());
        // broken language file -> whole file
        assertEquals("high", r.fileConflicts().stream().filter(c -> c.location().equals("t:lang/en_us.json")).findFirst().orElseThrow().winner());
        assertEquals(PackStatus.LOSES, r.summary("low").status());
    }

    @Test
    void languageKeysAgainstVanillaAreCountedPerFile() throws IOException {
        Path rp = zip("lang.zip", Map.of("assets/minecraft/lang/de_de.json", "{\"a\":\"1\",\"b\":\"2\",\"own\":\"3\"}"));
        PackRef vanilla = new PackRef("vanilla", "Default", PackRole.VANILLA, ScanKind.LOOKUP, null, List.of(), null, null, null,
            () -> new TestPacks.MapContent(Map.of("minecraft:lang/de_de.json", "{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}")));
        IndexCache cache = new IndexCache(null);
        PackOrder order = new PackOrder(List.of(ref("lang", rp), vanilla));
        ScanResult r = PackEvaluator.evaluate(order, cache, PackscopeSettings.DEFAULTS);
        assertEquals(1, r.modOverrides().size());
        assertEquals(ScanResult.OverrideType.LANG_KEY, r.modOverrides().get(0).type());
        assertEquals(2, r.modOverrides().get(0).count());
        assertEquals(Map.of("minecraft", 2), r.summary("lang").modOverridesByOwner());
        assertEquals(PackStatus.NO_CONFLICT, r.summary("lang").status());
        // second evaluation takes the count from the cache
        assertEquals(1, cache.langOverlap.size());
        assertEquals(2, PackEvaluator.evaluate(order, cache, PackscopeSettings.DEFAULTS).summary("lang").modOverrides());
    }

    @Test
    void unknownPackTypeUsesTheFallbackScan() throws IOException {
        PackRef unknown = new PackRef("generated/x", "x", PackRole.RESOURCE_PACK, ScanKind.FALLBACK, null, List.of(), null, null, null,
            () -> new TestPacks.MapContent(Map.of("t:textures/a.png", "A", "t:sounds.json", "{}")));
        Path low = zip("low.zip", Map.of("assets/t/textures/a.png", "other"));
        ScanResult r = PackEvaluator.evaluate(new PackOrder(List.of(unknown, ref("low", low))), new IndexCache(null), PackscopeSettings.DEFAULTS);
        PackIndex idx = r.indexes().get("generated/x");
        assertTrue(idx.approximate());
        assertEquals(2, idx.files().size());
        assertTrue(r.summary("generated/x").approximate());
        assertEquals(PackStatus.WINS, r.summary("generated/x").status());
        assertEquals(PackStatus.COMPLETELY_COVERED, r.summary("low").status());
    }

    @Test
    void missingZipIsAWarning() {
        PackOrder order = new PackOrder(List.of(ref("gone", tmp.resolve("gone.zip"))));
        ScanResult r = PackEvaluator.evaluate(order, new IndexCache(null), PackscopeSettings.DEFAULTS);
        assertEquals(1, r.warnings().size());
        assertEquals(PackStatus.NO_CONFLICT, r.summary("gone").status());
    }

    @Test
    void laterOverlayWins() throws IOException {
        Path p = zip("ov.zip", Map.of(
            "assets/t/a.txt", "base",
            "o1/assets/t/a.txt", "one",
            "o2/assets/t/a.txt", "two",
            "o1/assets/t/b.txt", "one"));
        PackRef ref = new PackRef("ov", "ov", PackRole.RESOURCE_PACK, ScanKind.ZIP, p, List.of("o1", "o2"), null, null, null, null);
        PackIndex idx = PackScanner.scan(ref, new IndexCache(null));
        assertEquals("o2", idx.files().get("t:a.txt").prefix());
        assertEquals("o1", idx.files().get("t:b.txt").prefix());
    }

    @Test
    void folderChecksumsAreCachedOnDisk() throws IOException {
        Path pack = tmp.resolve("folderpack");
        Files.createDirectories(pack.resolve("assets/t"));
        Files.writeString(pack.resolve("assets/t/a.txt"), "hello");
        Path cacheDir = tmp.resolve("cache");
        PackRef ref = new PackRef("f", "f", PackRole.RESOURCE_PACK, ScanKind.FOLDER, pack.toAbsolutePath(), List.of(), null, null, null, null);
        PackIndex first = PackScanner.scan(ref, new IndexCache(cacheDir));
        try (Stream<Path> s = Files.list(cacheDir)) {
            assertEquals(1, s.count());
        }
        assertEquals(PackScanner.crc("hello".getBytes(StandardCharsets.UTF_8)), first.files().get("t:a.txt").crc());
        // a fresh cache (new game session) reads the CRC from disk
        IndexCache second = new IndexCache(cacheDir);
        long cached = second.folder(pack.toAbsolutePath()).crc("assets/t/a.txt", 5,
            Files.getLastModifiedTime(pack.resolve("assets/t/a.txt")).toMillis());
        assertEquals(first.files().get("t:a.txt").crc(), cached);
        second.clear();
        try (Stream<Path> s = Files.list(cacheDir)) {
            assertEquals(0, s.count());
        }
    }

    @Test
    void reportFiles() throws IOException {
        ScanResult r = PackEvaluator.evaluate(TestPacks.readmeOrder(), new IndexCache(null), PackscopeSettings.DEFAULTS);
        Path md = ReportWriter.write(r, PackscopeSettings.DEFAULTS, "test", tmp.resolve("reports"));
        String text = Files.readString(md);
        assertTrue(text.contains("# Packscope report"));
        assertTrue(text.contains("## Load order"));
        assertTrue(text.contains("Completely covered packs: PS_D_Hidden.zip"));
        assertTrue(text.contains("`packscope_test:test.event`"));
        JsonObject json = JsonParser.parseString(Files.readString(md.resolveSibling(ReportWriter.JSON_NAME))).getAsJsonObject();
        assertEquals(r.packs().size(), json.getAsJsonArray("order").size());
        assertEquals("completely_covered", json.getAsJsonArray("order").asList().stream()
            .map(e -> e.getAsJsonObject()).filter(o -> o.get("id").getAsString().equals("file/PS_D_Hidden.zip"))
            .findFirst().orElseThrow().get("status").getAsString());
    }

    private Path zip(String name, Map<String, String> files) throws IOException {
        Path p = tmp.resolve(name);
        try (OutputStream out = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return p;
    }

    private static PackRef ref(String id, Path zip) {
        return new PackRef(id, id, PackRole.RESOURCE_PACK, ScanKind.ZIP, zip, List.of(), null, null, null, null);
    }
}
