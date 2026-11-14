package com.drehverschluss.packscope.core;

import static com.drehverschluss.packscope.core.TestPacks.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drehverschluss.packscope.core.ScanResult.MarkingStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Intended markings (Konzept 4.1): file, matching, statuses, store API. */
class IntendedTest {
    @TempDir
    Path tmp;

    private static ScanResult eval(PackOrder order, IntendedSet set) {
        return PackEvaluator.evaluate(order, new IndexCache(null), PackscopeSettings.DEFAULTS, set);
    }

    private static IntendedMarking mark(String winner, String loser) {
        return new IntendedMarking(id(winner), id(loser), null);
    }

    @Test
    void allConflictsMarkedIsIntendedOnly() {
        ScanResult r = eval(TestPacks.readmeOrder(), IntendedSet.of(mark("PS_Anim_High", "PS_Anim_Low.zip"),
            mark("PS_Sound_Replace", "PS_Sound_Add.zip")));
        assertEquals(PackStatus.INTENDED_ONLY, r.summary(id("PS_Anim_High")).status());
        assertEquals(PackStatus.INTENDED_ONLY, r.summary(id("PS_Anim_Low.zip")).status());
        assertEquals(PackStatus.INTENDED_ONLY, r.summary(id("PS_Sound_Replace")).status());
        assertEquals(PackStatus.INTENDED_ONLY, r.summary(id("PS_Sound_Add.zip")).status());
        assertEquals(2, r.summary(id("PS_Anim_High")).intendedWins());
        assertEquals(2, r.summary(id("PS_Anim_Low.zip")).intendedLosses());
        assertEquals(0, r.summary(id("PS_Anim_Low.zip")).losses());
        // conflicts stay in the result, flagged
        assertTrue(r.fileConflicts().stream().filter(c -> c.location().contains("anim_")).allMatch(ScanResult.FileConflict::intended));
        assertTrue(r.soundReplacements().get(0).intended());
        assertEquals(4, r.intendedCount(), "anim_a.png, anim_a .mcmeta (dead), anim_b .mcmeta, test.event");
        ScanResult.PairConflict p = r.pair(id("PS_Anim_High"), id("PS_Anim_Low.zip"));
        assertNotNull(p);
        assertTrue(p.intended());
        assertEquals(MarkingStatus.WORKS, r.markings().get(0).status());
        assertEquals(2, r.markings().get(0).items());
    }

    @Test
    void partlyMarkedKeepsTheNormalStatus() {
        // A over D is marked, A still loses to B and the overlay -> Loses (its only win is intended)
        ScanResult r = eval(TestPacks.readmeOrder(), IntendedSet.of(mark("PS_A_Base.zip", "PS_D_Hidden.zip")));
        assertEquals(PackStatus.LOSES, r.summary(id("PS_A_Base.zip")).status());
        assertEquals(1, r.summary(id("PS_A_Base.zip")).intendedWins());
        // D loses the diamond to B and A: not all uppers are marked -> still a normal loss
        assertEquals(PackStatus.COMPLETELY_COVERED, r.summary(id("PS_D_Hidden.zip")).status());
    }

    @Test
    void completelyCoveredStaysEvenIfMarked() {
        ScanResult r = eval(TestPacks.readmeOrder(), IntendedSet.of(mark("PS_B_Top", "PS_D_Hidden.zip"),
            mark("PS_A_Base.zip", "PS_D_Hidden.zip")));
        assertEquals(PackStatus.COMPLETELY_COVERED, r.summary(id("PS_D_Hidden.zip")).status());
        assertEquals(2, r.summary(id("PS_D_Hidden.zip")).intendedLosses());
        assertEquals(PackStatus.COMPLETELY_COVERED, r.summary(id("PS_E_CopyOnly.zip")).status());
    }

    @Test
    void reversedPairIsAgainstMarking() {
        ScanResult r = eval(TestPacks.readmeOrder(), IntendedSet.of(mark("PS_Lang_Low.zip", "PS_Lang_High")));
        assertEquals(PackStatus.AGAINST_MARKING, r.summary(id("PS_Lang_High")).status());
        assertEquals(PackStatus.AGAINST_MARKING, r.summary(id("PS_Lang_Low.zip")).status());
        assertEquals(MarkingStatus.REVERSED, r.markings().get(0).status());
        assertTrue(r.pair(id("PS_Lang_High"), id("PS_Lang_Low.zip")).againstMarking());
        assertFalse(r.langConflicts().stream().filter(c -> c.key().equals("packscope.test.shared")).findFirst().orElseThrow().intended());
    }

    @Test
    void swappedMarkedPairCoveredWins() {
        // swap PS_Anim_High and PS_Anim_Low.zip: Low now overrides both files of High
        List<String> names = new ArrayList<>(List.of(TestPacks.ORDER));
        int a = names.indexOf("PS_Anim_High");
        int b = names.indexOf("PS_Anim_Low.zip");
        Collections.swap(names, a, b);
        ScanResult r = eval(TestPacks.order(names.toArray(String[]::new)), IntendedSet.of(mark("PS_Anim_High", "PS_Anim_Low.zip")));
        assertEquals(PackStatus.AGAINST_MARKING, r.summary(id("PS_Anim_Low.zip")).status());
        assertEquals(PackStatus.COMPLETELY_COVERED, r.summary(id("PS_Anim_High")).status(), "completely covered comes first");
        assertTrue(r.summary(id("PS_Anim_High")).againstMarking(), "flag is still set");
        assertEquals(MarkingStatus.REVERSED, r.markings().get(0).status());
    }

    @Test
    void markingsWithoutEffect() {
        ScanResult r = eval(TestPacks.readmeOrder(), IntendedSet.of(mark("PS_Filter.zip", "PS_ModTex.zip"),
            new IntendedMarking("file/Not_Here-1.0.zip", id("PS_B_Top"), "gone")));
        assertEquals(MarkingStatus.NO_CONFLICT, r.markings().get(0).status());
        assertEquals(MarkingStatus.PACK_NOT_ACTIVE, r.markings().get(1).status());
        assertEquals(PackStatus.NO_CONFLICT, r.summary(id("PS_Filter.zip")).status());
        assertEquals(PackStatus.WINS, r.summary(id("PS_B_Top")).status());
    }

    @Test
    void filterAndFolderPairs() {
        ScanResult r = eval(TestPacks.readmeOrder(), IntendedSet.of(mark("PS_Filter.zip", "PS_A_Base.zip"),
            mark("PS_Folder/PS_F_Top.zip", "PS_Folder/PS_F_Low")));
        assertTrue(r.filtered().get(0).intended());
        assertEquals(1, r.pair(id("PS_Filter.zip"), id("PS_A_Base.zip")).filtered());
        assertEquals(PackStatus.INTENDED_ONLY, r.summary(id("PS_Folder/PS_F_Top.zip")).status());
    }

    @Test
    void markingMatchesANewVersion() {
        // the marking was made for an old file name; the active pack is a newer version with the same base name
        IntendedSet set = IntendedSet.of(new IntendedMarking("file/PS_Anim_High v.1.0", "file/PS_Anim_Low v.2.3.zip", null));
        ScanResult r = eval(TestPacks.readmeOrder(), set);
        assertEquals(PackStatus.INTENDED_ONLY, r.summary(id("PS_Anim_High")).status());
    }

    // ------------------------------------------------------------------------------------------------ store

    @Test
    void storeMarksThroughTheApiWithoutRescan() throws IOException {
        IntendedStore store = new IntendedStore(tmp.resolve("packscope-intended.json"));
        store.load();
        IndexCache cache = new IndexCache(null);
        PackOrder order = TestPacks.readmeOrder();
        ScanResult before = PackEvaluator.evaluate(order, cache, PackscopeSettings.DEFAULTS, store.snapshot());
        assertEquals(PackStatus.WINS, before.summary(id("PS_Overlay.zip")).status());

        assertTrue(store.mark(id("PS_Overlay.zip"), id("PS_A_Base.zip"), "overlay text is newer"));
        assertFalse(store.mark(id("PS_Overlay.zip"), id("PS_A_Base.zip"), null), "already marked");
        ScanResult after = PackEvaluator.evaluate(order, cache, PackscopeSettings.DEFAULTS, store.snapshot());
        assertEquals(PackStatus.INTENDED_ONLY, after.summary(id("PS_Overlay.zip")).status());
        assertTrue(after.indexes().get(id("PS_A_Base.zip")) == before.indexes().get(id("PS_A_Base.zip")), "same index, no rescan");

        // all pairs of B in the current direction
        int added = store.markAllOf(id("PS_B_Top"), after);
        assertEquals(2, added, "B over A and B over D");
        ScanResult all = PackEvaluator.evaluate(order, cache, PackscopeSettings.DEFAULTS, store.snapshot());
        assertEquals(PackStatus.INTENDED_ONLY, all.summary(id("PS_B_Top")).status());

        assertEquals(2, store.unmarkAllOf(id("PS_B_Top")));
        assertTrue(store.unmark(id("PS_Overlay.zip"), id("PS_A_Base.zip")));
        assertEquals(0, store.markings().size());
    }

    @Test
    void storeReadsAndWritesTheFile() throws IOException {
        Path file = tmp.resolve("packscope-intended.json");
        Files.writeString(file, """
            {
              "version": 1,
              "comment": "made by hand",
              "intended": [
                { "winner": "file/Zeta.zip", "loser": "file/Alpha.zip", "note": "keep me", "since": "0.8" },
                { "winner": "file/Alpha.zip", "loser": "file/Beta.zip" },
                { "note": "entry without ids" }
              ]
            }
            """);
        IntendedStore store = new IntendedStore(file);
        store.load();
        assertEquals(2, store.markings().size());
        assertEquals(1, store.snapshot().warnings().size(), "entry without ids");
        assertTrue(store.mark("file/Gamma.zip", "file/Alpha.zip", null));
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt());
        assertEquals("made by hand", root.get("comment").getAsString());
        JsonArray list = root.getAsJsonArray("intended");
        assertEquals(4, list.size());
        List<String> winners = new ArrayList<>();
        for (int i = 0; i < 3; i++) winners.add(list.get(i).getAsJsonObject().get("winner").getAsString());
        List<String> sorted = new ArrayList<>(winners);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        assertEquals(sorted, winners, "sorted by winner");
        JsonObject zeta = list.get(2).getAsJsonObject();
        assertEquals("keep me", zeta.get("note").getAsString());
        assertEquals("0.8", zeta.get("since").getAsString());
        assertEquals("entry without ids", list.get(3).getAsJsonObject().get("note").getAsString(), "invalid entry kept");
        assertTrue(Files.readString(file).contains("\n  \"intended\""), "indented");
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
    }

    @Test
    void reloadOnlyWhenTheFileChanged() throws IOException {
        Path file = tmp.resolve("packscope-intended.json");
        IntendedStore store = new IntendedStore(file);
        store.load();
        assertFalse(store.reloadIfChanged());
        Files.writeString(file, "{\"version\":1,\"intended\":[{\"winner\":\"file/A.zip\",\"loser\":\"file/B.zip\"}]}");
        assertTrue(store.reloadIfChanged());
        assertEquals(1, store.markings().size());
        assertFalse(store.reloadIfChanged());
    }

    @Test
    void brokenFileIsAWarningAndKeptAsBackup() throws IOException {
        Path file = tmp.resolve("packscope-intended.json");
        Files.writeString(file, "{ \"version\": 1, \"intended\": [ { \"winner\": ");
        IntendedStore store = new IntendedStore(file);
        store.load();
        assertEquals(0, store.markings().size());
        assertEquals(1, store.snapshot().warnings().size());
        ScanResult r = eval(TestPacks.readmeOrder(), store.snapshot());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("is broken")));
        Path backup = file.resolveSibling("packscope-intended.json.broken");
        assertFalse(Files.exists(backup));
        store.mark("file/A.zip", "file/B.zip", null);
        assertTrue(Files.exists(backup), "copy before the first overwrite");
        assertTrue(Files.readString(backup).startsWith("{ \"version\": 1, \"intended\": [ { \"winner\": "));
        assertEquals(1, JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("intended").size());
    }

    @Test
    void reportShowsIntendedPairsCompactly() {
        ScanResult r = eval(TestPacks.readmeOrder(), IntendedSet.of(mark("PS_Anim_High", "PS_Anim_Low.zip")));
        String md = ReportWriter.markdown(r, PackscopeSettings.DEFAULTS, "test");
        assertTrue(md.contains("(intended: 3)"), md.lines().filter(l -> l.startsWith("- Conflicts")).findFirst().orElse(""));
        assertTrue(md.contains("**PS_Anim_High** over **PS_Anim_Low.zip**: 2 files"));
        assertFalse(md.contains("`packscope_test:textures/block/anim_a.png` –"), "intended file conflicts not listed one by one");
        assertTrue(md.contains("| works |"));
        JsonObject json = ReportWriter.json(r, PackscopeSettings.DEFAULTS, "test");
        assertEquals("intended_only", json.getAsJsonArray("order").asList().stream().map(e -> e.getAsJsonObject())
            .filter(o -> o.get("id").getAsString().equals(id("PS_Anim_High"))).findFirst().orElseThrow().get("status").getAsString());
        assertTrue(json.getAsJsonArray("fileConflicts").asList().stream().map(e -> e.getAsJsonObject())
            .filter(o -> o.get("location").getAsString().endsWith("anim_a.png")).findFirst().orElseThrow().get("intended").getAsBoolean());
    }
}
