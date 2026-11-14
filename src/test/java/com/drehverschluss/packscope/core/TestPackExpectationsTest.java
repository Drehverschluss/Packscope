package com.drehverschluss.packscope.core;

import static com.drehverschluss.packscope.core.TestPacks.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drehverschluss.packscope.core.ScanResult.FileConflict;
import com.drehverschluss.packscope.core.ScanResult.FilteredFile;
import com.drehverschluss.packscope.core.ScanResult.LangConflict;
import com.drehverschluss.packscope.core.ScanResult.ModOverride;
import com.drehverschluss.packscope.core.ScanResult.PackSummary;
import com.drehverschluss.packscope.core.ScanResult.SoundReplacement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The expectation table of Konzept/Test-Packs/README.md as tests. */
class TestPackExpectationsTest {
    private static ScanResult result;

    @BeforeAll
    static void evaluate() {
        result = PackEvaluator.evaluate(TestPacks.readmeOrder(), new IndexCache(null), PackscopeSettings.DEFAULTS);
    }

    private static PackSummary pack(String name) {
        PackSummary s = result.summary(id(name));
        assertNotNull(s, name);
        return s;
    }

    private static FileConflict file(String location) {
        return result.fileConflicts().stream().filter(c -> c.location().equals(location)).findFirst().orElse(null);
    }

    @Test
    void statusPerPack() {
        Map<String, PackStatus> expected = Map.ofEntries(
            Map.entry("PS_Folder/PS_F_Top.zip", PackStatus.WINS),
            Map.entry("PS_Folder/PS_F_Low", PackStatus.LOSES),
            Map.entry("PS_AtlasFont_2", PackStatus.NO_CONFLICT),
            Map.entry("PS_AtlasFont_1.zip", PackStatus.NO_CONFLICT),
            Map.entry("PS_ModTex.zip", PackStatus.NO_CONFLICT),
            Map.entry("PS_Anim_High", PackStatus.WINS),
            Map.entry("PS_Anim_Low.zip", PackStatus.LOSES),
            Map.entry("PS_Overlay.zip", PackStatus.WINS),
            Map.entry("PS_Filter.zip", PackStatus.NO_CONFLICT),
            Map.entry("PS_Sound_Replace", PackStatus.REPLACES_SOUNDS),
            Map.entry("PS_Sound_Add.zip", PackStatus.LOSES),
            Map.entry("PS_Lang_High", PackStatus.WINS),
            Map.entry("PS_Lang_Low.zip", PackStatus.LOSES),
            Map.entry("PS_B_Top", PackStatus.WINS),
            Map.entry("PS_Lang3_High", PackStatus.WINS),
            Map.entry("PS_Lang3_Low.zip", PackStatus.LOSES),
            Map.entry("PS_E_CopyOnly.zip", PackStatus.COMPLETELY_COVERED),
            Map.entry("PS_C_Copy.zip", PackStatus.NO_CONFLICT),
            Map.entry("PS_A_Base.zip", PackStatus.WINS_AND_LOSES),
            Map.entry("PS_D_Hidden.zip", PackStatus.COMPLETELY_COVERED));
        expected.forEach((name, status) -> assertEquals(status, pack(name).status(), name));
        assertEquals(List.of(id("PS_E_CopyOnly.zip"), id("PS_D_Hidden.zip")), result.coveredPacks().stream().map(PackSummary::id).toList());
    }

    @Test
    void wholeFileWinnerAndLosers() {
        FileConflict diamond = file("minecraft:textures/item/diamond.png");
        assertEquals(id("PS_B_Top"), diamond.winner());
        assertEquals(List.of(id("PS_A_Base.zip"), id("PS_D_Hidden.zip")), diamond.losers());
        FileConflict covered = file("packscope_test:texts/d_covered.txt");
        assertEquals(id("PS_B_Top"), covered.winner());
        assertEquals(List.of(id("PS_D_Hidden.zip")), covered.losers());
        assertEquals(List.of(id("PS_E_CopyOnly.zip")), covered.harmless());
        // A wins the diamond against D, loses it to B and ov_vs_a to the overlay
        PackSummary a = pack("PS_A_Base.zip");
        assertEquals(1, a.wins());
        assertEquals(2, a.losses());
        assertEquals(Map.of(id("PS_B_Top"), 1, id("PS_Overlay.zip"), 1), a.losesTo());
        assertNull(file("packscope_test:textures/item/only_a.png"), "file only in A is no conflict");
    }

    @Test
    void identicalFileIsHarmless() {
        FileConflict copy = file("packscope_test:texts/shared_copy.txt");
        assertEquals(id("PS_C_Copy.zip"), copy.winner());
        assertTrue(copy.losers().isEmpty());
        assertEquals(List.of(id("PS_A_Base.zip")), copy.harmless());
        assertEquals(1, pack("PS_A_Base.zip").harmless());
    }

    @Test
    void filterHidesLowerPacksOnly() {
        List<FilteredFile> f = result.filtered();
        assertEquals(1, f.size());
        assertEquals("packscope_test:texts/filtered/hidden.txt", f.get(0).location());
        assertEquals(id("PS_Filter.zip"), f.get(0).filterPack());
        assertEquals(List.of(id("PS_A_Base.zip")), f.get(0).hiddenPacks());
        assertEquals(1, pack("PS_Filter.zip").hidesOthers());
        assertEquals(1, pack("PS_A_Base.zip").hiddenByFilter());
        assertTrue(result.indexes().get(id("PS_Filter.zip")).files().containsKey("packscope_test:texts/filtered/own.txt"));
    }

    @Test
    void activeOverlayBelongsToThePack() {
        PackIndex ov = result.indexes().get(id("PS_Overlay.zip"));
        assertEquals("ov_active", ov.files().get("packscope_test:texts/overlay_target.txt").prefix());
        assertTrue(ov.files().containsKey("packscope_test:texts/overlay_only.txt"));
        assertFalse(ov.files().containsKey("packscope_test:texts/overlay_inactive.txt"));
        FileConflict vsA = file("packscope_test:texts/ov_vs_a.txt");
        assertEquals(id("PS_Overlay.zip"), vsA.winner());
        assertEquals(List.of(id("PS_A_Base.zip")), vsA.losers());
    }

    @Test
    void languageKeys() {
        // readmeOrder() counts all languages: lang3 has its shared key in en_us, de_de and fr_fr
        assertEquals(4, result.langConflicts().size());
        LangConflict c = result.langConflicts().stream().filter(x -> x.key().equals("packscope.test.shared")).findFirst().orElseThrow();
        assertEquals("packscope.test.shared", c.key());
        assertEquals("en_us", c.language());
        assertEquals(id("PS_Lang_High"), c.winner());
        assertEquals("HIGH", c.winnerText());
        assertEquals(id("PS_Lang_Low.zip"), c.losers().get(0).pack());
        assertEquals("LOW", c.losers().get(0).text());
        assertNull(file("packscope_test:lang/en_us.json"), "language files are not whole-file conflicts");
        assertEquals(1, pack("PS_Lang_High").wins());
        assertEquals(1, pack("PS_Lang_Low.zip").losses());
        assertEquals(3, pack("PS_Lang3_Low.zip").losses());
    }

    @Test
    void soundsReplaceAndMerge() {
        assertEquals(1, result.soundReplacements().size());
        SoundReplacement s = result.soundReplacements().get(0);
        assertEquals("packscope_test:test.event", s.event());
        assertEquals(id("PS_Sound_Replace"), s.pack());
        assertEquals(List.of(id("PS_Sound_Add.zip")), s.replacedPacks());
        assertEquals(List.of("packscope_test:low_a"), s.lostSounds());
        assertEquals(1, pack("PS_Sound_Replace").replacedSoundEvents());
        assertEquals(1, pack("PS_Sound_Add.zip").lostSoundEvents());
        assertNull(file("packscope_test:sounds.json"));
    }

    @Test
    void mcmetaFollowsTheResource() {
        FileConflict a = file("packscope_test:textures/block/anim_a.png");
        assertEquals(id("PS_Anim_High"), a.winner());
        FileConflict metaA = file("packscope_test:textures/block/anim_a.png.mcmeta");
        assertEquals(List.of(id("PS_Anim_Low.zip")), metaA.dead(), "lower .mcmeta under a replaced texture is dead");
        FileConflict metaB = file("packscope_test:textures/block/anim_b.png.mcmeta");
        assertEquals(id("PS_Anim_High"), metaB.winner());
        assertEquals(List.of(id("PS_Anim_Low.zip")), metaB.losers());
        PackSummary low = pack("PS_Anim_Low.zip");
        assertEquals(2, low.losses());
        assertEquals(1, low.dead());
    }

    @Test
    void mergedFilesAreNoConflict() {
        assertNull(file("minecraft:atlases/blocks.json"));
        assertNull(file("minecraft:font/default.json"));
    }

    @Test
    void modAndVanillaInfo() {
        List<ModOverride> modTex = result.modOverrides().stream().filter(m -> m.pack().equals(id("PS_ModTex.zip"))).toList();
        assertEquals(1, modTex.size());
        assertEquals("packed_packs", modTex.get(0).ownerKey());
        assertEquals("Packed Packs", modTex.get(0).ownerName());
        assertEquals(Map.of("packed_packs", 1), pack("PS_ModTex.zip").modOverridesByOwner());
        assertEquals(Map.of("minecraft", 1), pack("PS_B_Top").modOverridesByOwner());
        assertEquals(Map.of("minecraft", 1), pack("PS_Folder/PS_F_Top.zip").modOverridesByOwner(), "emerald");
        assertTrue(result.modOverrides().stream().noneMatch(ModOverride::reversed));
        // atlases/fonts merge with vanilla: no info
        assertTrue(result.modOverrides().stream().noneMatch(m -> m.location().equals("minecraft:atlases/blocks.json")));
    }

    @Test
    void folderPackChildren() {
        FileConflict f = file("packscope_test:texts/folder_shared.txt");
        assertEquals(id("PS_Folder/PS_F_Top.zip"), f.winner());
        assertEquals(List.of(id("PS_Folder/PS_F_Low")), f.losers());
    }

    @Test
    void onlyCopiesIsCompletelyCovered() {
        PackSummary e = pack("PS_E_CopyOnly.zip");
        assertTrue(e.onlyCopies());
        assertEquals(Map.of(id("PS_B_Top"), 1), e.copiesOf());
        assertFalse(pack("PS_D_Hidden.zip").onlyCopies());
        // a copy of the winning file counts as that file: no pair "E over D", no win of its own
        assertNull(result.pair(id("PS_E_CopyOnly.zip"), id("PS_D_Hidden.zip")));
        assertEquals(0, e.wins());
        assertEquals(Map.of(id("PS_B_Top"), 2), pack("PS_D_Hidden.zip").losesTo());
        // C is the winning copy: its file reaches the game, A keeps other own files
        assertEquals(PackStatus.NO_CONFLICT, pack("PS_C_Copy.zip").status());
    }

    @Test
    void identicalCountsWhenOptionOff() {
        ScanResult r = PackEvaluator.evaluate(TestPacks.readmeOrder(), new IndexCache(null), PackscopeSettings.DEFAULTS.withIgnoreIdentical(false));
        assertEquals(PackStatus.WINS, r.summary(id("PS_C_Copy.zip")).status());
        assertEquals(3, r.summary(id("PS_A_Base.zip")).losses());
        // without the copy rule E simply loses its only file: still completely covered, but not "only copies"
        assertEquals(PackStatus.COMPLETELY_COVERED, r.summary(id("PS_E_CopyOnly.zip")).status());
        assertFalse(r.summary(id("PS_E_CopyOnly.zip")).onlyCopies());
    }

    @Test
    void ignoredPaths() {
        ScanResult r = PackEvaluator.evaluate(TestPacks.readmeOrder(), new IndexCache(null),
            PackscopeSettings.DEFAULTS.withIgnoredPaths(List.of("minecraft:textures/item/diamond.png")));
        assertEquals(PackStatus.LOSES, r.summary(id("PS_A_Base.zip")).status());
        assertEquals(PackStatus.COMPLETELY_COVERED, r.summary(id("PS_D_Hidden.zip")).status());
        assertTrue(r.fileConflicts().stream().noneMatch(c -> c.location().endsWith("diamond.png")));
    }

    @Test
    void newOrderReusesTheIndexes() {
        IndexCache cache = new IndexCache(null);
        ScanResult first = PackEvaluator.evaluate(TestPacks.readmeOrder(), cache, PackscopeSettings.DEFAULTS);
        // D moves to the top: now D wins the diamond and d_covered, B loses both
        String[] moved = new String[TestPacks.ORDER.length];
        moved[0] = "PS_D_Hidden.zip";
        System.arraycopy(TestPacks.ORDER, 0, moved, 1, TestPacks.ORDER.length - 1);
        ScanResult second = PackEvaluator.evaluate(TestPacks.order(moved), cache, PackscopeSettings.DEFAULTS);
        assertSame(first.indexes().get(id("PS_A_Base.zip")), second.indexes().get(id("PS_A_Base.zip")), "zip index from the cache");
        assertEquals(PackStatus.WINS, second.summary(id("PS_D_Hidden.zip")).status());
        assertEquals(PackStatus.COMPLETELY_COVERED, second.summary(id("PS_B_Top")).status(), "B loses both files to D");
    }
}
