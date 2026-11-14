package com.drehverschluss.packscope.gui.model;

import static com.drehverschluss.packscope.core.TestPacks.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drehverschluss.packscope.core.FileKind;
import com.drehverschluss.packscope.core.IndexCache;
import com.drehverschluss.packscope.core.IntendedMarking;
import com.drehverschluss.packscope.core.IntendedSet;
import com.drehverschluss.packscope.core.PackEvaluator;
import com.drehverschluss.packscope.core.PackOrder;
import com.drehverschluss.packscope.core.PackRef;
import com.drehverschluss.packscope.core.PackRole;
import com.drehverschluss.packscope.core.PackStatus;
import com.drehverschluss.packscope.core.PackscopeSettings;
import com.drehverschluss.packscope.core.ScanKind;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.core.TestPacks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Presentation logic of the pack screen: tooltip lines, groups, sorting, search, filter, number format (Phase 3B). */
class PresentationTest {
    private static final PackscopeSettings S = PackscopeSettings.DEFAULTS;

    private static ScanResult eval(IntendedMarking... markings) {
        return PackEvaluator.evaluate(TestPacks.readmeOrder(), new IndexCache(null), S,
            markings.length == 0 ? IntendedSet.EMPTY : IntendedSet.of(markings));
    }

    private static IntendedMarking mark(String winner, String loser) {
        return new IntendedMarking(id(winner), id(loser), null);
    }

    private static List<String> keys(List<Line> lines) {
        return lines.stream().map(Line::key).toList();
    }

    // ------------------------------------------------------------------------------------------------ tooltip

    @Test
    void tooltipWins() {
        ScanResult r = eval();
        List<Line> t = TooltipModel.build(r, id("PS_B_Top"), S, "en_us");
        assertEquals("packscope.status.wins", t.get(0).key());
        assertEquals(Line.Tone.NEUTRAL, t.get(0).tone());
        assertEquals("packscope.tooltip.overrides", t.get(1).key());
        // D loses 2 files to B (diamond, d_covered.txt), A 1: biggest first
        assertEquals("PS_D_Hidden.zip", t.get(2).args().get(0));
        assertEquals("PS_A_Base.zip", t.get(3).args().get(0));
        Line.Parts parts = (Line.Parts) t.get(2).args().get(1);
        assertEquals("packscope.count.files.many", ((Line) parts.parts().get(0)).key());
        assertEquals("2", ((Line) parts.parts().get(0)).args().get(0));
        assertEquals("packscope.tooltip.click", t.get(t.size() - 1).key());
        assertFalse(keys(t).contains("packscope.tooltip.overridden_by"));
    }

    @Test
    void tooltipCoveredAndOnlyCopies() {
        ScanResult r = eval();
        List<Line> d = TooltipModel.build(r, id("PS_D_Hidden.zip"), S, "en_us");
        assertEquals("packscope.status.completely_covered", d.get(0).key());
        assertEquals(Line.Tone.RED, d.get(0).tone());
        assertEquals("packscope.tooltip.covered", d.get(1).key());
        assertTrue(keys(d).contains("packscope.tooltip.overridden_by"));

        List<Line> e = TooltipModel.build(r, id("PS_E_CopyOnly.zip"), S, "en_us");
        assertEquals("packscope.tooltip.only_copies", e.get(1).key());
        assertEquals(List.of("PS_B_Top"), ((Line.Parts) e.get(1).args().get(0)).parts());
    }

    @Test
    void tooltipLosesSoundsAndLanguage() {
        ScanResult r = eval();
        List<Line> low = TooltipModel.build(r, id("PS_Lang_Low.zip"), S, "en_us");
        assertEquals("packscope.status.loses", low.get(0).key());
        assertEquals("packscope.tooltip.overridden_by", low.get(1).key());
        assertEquals("PS_Lang_High", low.get(2).args().get(0));
        Line count = (Line) ((Line.Parts) low.get(2).args().get(1)).parts().get(0);
        assertEquals("packscope.count.lang_keys.one", count.key());

        List<Line> snd = TooltipModel.build(r, id("PS_Sound_Replace"), S, "en_us");
        assertEquals("packscope.status.replaces_sounds", snd.get(0).key());
        assertEquals(Line.Tone.YELLOW, snd.get(0).tone());
        assertEquals("packscope.tooltip.replaces_sounds", snd.get(1).key());
        Line sc = (Line) ((Line.Parts) snd.get(3).args().get(1)).parts().get(0);
        assertEquals("packscope.count.sound_events.one", sc.key());
    }

    @Test
    void tooltipIntendedAndAgainstMarking() {
        ScanResult r = eval(mark("PS_Anim_High", "PS_Anim_Low.zip"), mark("PS_Lang_Low.zip", "PS_Lang_High"));
        List<Line> high = TooltipModel.build(r, id("PS_Anim_High"), S, "en_us");
        assertEquals("packscope.status.intended_only", high.get(0).key());
        assertEquals(Line.Tone.GREY, high.get(0).tone());
        assertTrue(keys(high).contains("packscope.tooltip.intended_overrides"));
        assertFalse(keys(high).contains("packscope.tooltip.overrides"));
        List<Line> low = TooltipModel.build(r, id("PS_Anim_Low.zip"), S, "en_us");
        assertTrue(keys(low).contains("packscope.tooltip.intended_overridden"));

        List<Line> lh = TooltipModel.build(r, id("PS_Lang_High"), S, "en_us");
        assertEquals("packscope.status.against_marking", lh.get(0).key());
        Line against = lh.get(1);
        assertEquals("packscope.tooltip.against_marking", against.key());
        // marking: Lang_Low should be above Lang_High
        assertEquals(List.of("PS_Lang_Low.zip", "PS_Lang_High"), against.args());
    }

    @Test
    void tooltipModInfoFollowsTheSetting() {
        ScanResult r = eval();
        List<Line> on = TooltipModel.build(r, id("PS_ModTex.zip"), S, "en_us");
        assertTrue(keys(on).contains("packscope.tooltip.mod_overrides"));
        PackscopeSettings off = new PackscopeSettings(true, true, true, false, List.of(), 64, true, false, false, false, true);
        assertFalse(keys(TooltipModel.build(r, id("PS_ModTex.zip"), off, "en_us")).contains("packscope.tooltip.mod_overrides"));
    }

    @Test
    void tooltipNamesThreeBiggestAndMore() {
        ScanResult r = synthetic(5);
        List<Line> t = TooltipModel.build(r, "file/T", S, "en_us");
        assertEquals("packscope.tooltip.overrides", t.get(1).key());
        assertEquals(List.of("L5", "L4", "L3"), List.of(t.get(2).args().get(0), t.get(3).args().get(0), t.get(4).args().get(0)));
        assertEquals("packscope.tooltip.more_packs", t.get(5).key());
        assertEquals("2", t.get(5).args().get(0));
        // exactly three: no "+ more"
        assertFalse(keys(TooltipModel.build(synthetic(3), "file/T", S, "en_us")).contains("packscope.tooltip.more_packs"));
    }

    @Test
    void hiddenPacksAreMarked() {
        ScanResult r = synthetic(2, true);
        List<Line> t = TooltipModel.build(r, "file/T", S, "en_us");
        Object name = t.get(2).args().get(0);
        assertTrue(name instanceof Line l && l.key().equals("packscope.name.hidden") && l.args().get(0).equals("L2"), String.valueOf(name));
    }

    /** "T" overrides L1..Ln, Li loses i files to T. */
    private static ScanResult synthetic(int n) {
        return synthetic(n, false);
    }

    private static ScanResult synthetic(int n, boolean lastHidden) {
        List<PackRef> refs = new ArrayList<>();
        refs.add(new PackRef("file/T", "T", PackRole.RESOURCE_PACK, ScanKind.ZIP, null, List.of(), null, null, null, null));
        for (int i = n; i >= 1; i--) {
            refs.add(new PackRef("file/L" + i, "L" + i, PackRole.RESOURCE_PACK, ScanKind.ZIP, null, List.of(), null, null, null, null,
                lastHidden && i == n));
        }
        List<ScanResult.FileConflict> files = new ArrayList<>();
        List<ScanResult.PairConflict> pairs = new ArrayList<>();
        List<ScanResult.PackSummary> sums = new ArrayList<>();
        Map<String, Integer> wins = new HashMap<>();
        for (int i = 1; i <= n; i++) {
            for (int f = 0; f < i; f++) {
                files.add(new ScanResult.FileConflict("t:textures/l" + i + "_" + f + ".png", FileKind.WHOLE, "file/T", List.of("file/L" + i),
                    List.of(), List.of(), null, false));
            }
            pairs.add(new ScanResult.PairConflict("file/T", "file/L" + i, i, 0, 0, 0, false, -1, false));
            wins.put("file/L" + i, i);
        }
        sums.add(summary("file/T", "T", PackStatus.WINS, false, wins, Map.of()));
        for (int i = n; i >= 1; i--) sums.add(summary("file/L" + i, "L" + i, PackStatus.LOSES, lastHidden && i == n, Map.of(), Map.of("file/T", i)));
        return new ScanResult(Instant.now(), new PackOrder(refs), sums, files, List.of(), List.of(), List.of(), List.of(), pairs, List.of(),
            0, List.of(), Map.of(), 0, 0);
    }

    private static ScanResult.PackSummary summary(String id, String title, PackStatus status, boolean hidden, Map<String, Integer> wins,
                                                  Map<String, Integer> loses) {
        return new ScanResult.PackSummary(id, title, PackRole.RESOURCE_PACK, ScanKind.ZIP, false, hidden, 1, status, wins.size(), loses.size(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, wins, loses, Map.of(), Map.of(), null, null);
    }

    // ------------------------------------------------------------------------------------------------ groups, sorting

    @Test
    void groupsBiggestFirstIntendedLast() {
        ConflictView v = ConflictView.of(eval(), id("PS_B_Top"));
        assertEquals(List.of(id("PS_D_Hidden.zip"), id("PS_A_Base.zip")), v.overrides().stream().map(ConflictView.Group::counterpart).toList());
        // pairwise numbers match the core
        ConflictView.Group d = v.overrides().get(0);
        assertEquals(2, d.files());
        assertEquals(eval().pair(id("PS_B_Top"), id("PS_D_Hidden.zip")).files(), d.files());

        ConflictView m = ConflictView.of(eval(mark("PS_B_Top", "PS_D_Hidden.zip")), id("PS_B_Top"));
        assertEquals(List.of(id("PS_A_Base.zip"), id("PS_D_Hidden.zip")), m.overrides().stream().map(ConflictView.Group::counterpart).toList());
        assertTrue(m.overrides().get(1).intended());
    }

    @Test
    void overriddenByNamesThePackThatReachesTheGame() {
        // the diamond of D loses to A and B pairwise, but only B's file reaches the game
        ConflictView v = ConflictView.of(eval(), id("PS_D_Hidden.zip"));
        assertEquals(List.of(id("PS_B_Top")), v.overriddenBy().stream().map(ConflictView.Group::counterpart).toList());
        assertEquals(2, v.overriddenBy().get(0).files());
    }

    @Test
    void deadMcmetaAndBlockstateTags() {
        ScanResult r = eval();
        DetailModel m = DetailModel.forPack(r, id("PS_Anim_Low.zip"), List.of(), "en_us");
        List<DetailModel.Row> rows = m.flatten(Map.of(), "", FileCategory.ALL);
        assertTrue(rows.stream().anyMatch(x -> x.text().args().contains("packscope_test:textures/block/anim_a.png.mcmeta")
            && x.tags().stream().anyMatch(t -> t.key().equals("packscope.detail.tag.dead"))), rows.toString());
        assertTrue(rows.stream().anyMatch(x -> "packscope_test:textures/block/anim_a.png".equals(x.previewLocation())));
        assertEquals(DetailModel.Action.Kind.MARK, rows.stream().filter(x -> x.type() == DetailModel.RowType.GROUP).findFirst().orElseThrow()
            .action().kind());
    }

    @Test
    void sectionsAndAllPacksView() {
        ScanResult r = eval(mark("PS_Anim_High", "PS_Anim_Low.zip"), new IntendedMarking("file/Not_Here-1.0.zip", id("PS_B_Top"), "gone"));
        List<DetailModel.Row> rows = DetailModel.forAll(r, "en_us").flatten(Map.of(), "", FileCategory.ALL);
        List<String> sections = rows.stream().filter(x -> x.type() == DetailModel.RowType.SECTION).map(x -> x.text().key()).toList();
        assertEquals(List.of("packscope.section.packs", "packscope.section.pairs", "packscope.section.covered", "packscope.section.markings",
            "packscope.section.mods_all"), sections);
        List<DetailModel.Row> pairs = rows.stream().filter(x -> x.type() == DetailModel.RowType.PAIR).toList();
        // not intended pairs first, the intended one last
        assertTrue(pairs.get(pairs.size() - 1).tags().stream().anyMatch(t -> t.key().equals("packscope.detail.tag.intended")));
        assertEquals(DetailModel.Action.Kind.UNMARK, pairs.get(pairs.size() - 1).action().kind());
        List<DetailModel.Row> markings = rows.stream().filter(x -> x.type() == DetailModel.RowType.MARKING).toList();
        assertEquals(2, markings.size());
        assertTrue(markings.stream().anyMatch(x -> x.tags().get(0).key().equals("packscope.marking.pack_not_active")));
        assertTrue(markings.stream().allMatch(x -> x.action().kind() == DetailModel.Action.Kind.REMOVE));
    }

    @Test
    void groupsAreBuiltWhenExpanded() {
        ScanResult r = eval(mark("PS_B_Top", "PS_D_Hidden.zip"));
        DetailModel m = DetailModel.forPack(r, id("PS_B_Top"), List.of(), "en_us");
        List<DetailModel.Row> rows = m.flatten(Map.of(), "", FileCategory.ALL);
        DetailModel.Row intendedGroup = rows.stream().filter(x -> x.id().equals("over:" + id("PS_D_Hidden.zip"))).findFirst().orElseThrow();
        assertFalse(intendedGroup.expanded(), "intended groups start collapsed");
        int built = m.builtParents();
        List<DetailModel.Row> open = m.flatten(Map.of(intendedGroup.id(), true), "", FileCategory.ALL);
        assertEquals(built + 1, m.builtParents());
        assertTrue(open.size() > rows.size());
    }

    // ------------------------------------------------------------------------------------------------ search, filter

    @Test
    void searchFindsPathsAndKeys() {
        ScanResult r = eval();
        DetailModel a = DetailModel.forPack(r, id("PS_A_Base.zip"), List.of(), "en_us");
        List<DetailModel.Row> items = a.flatten(Map.of(), "DIAMOND", FileCategory.ALL).stream()
            .filter(x -> x.type() == DetailModel.RowType.ITEM).toList();
        assertFalse(items.isEmpty());
        assertTrue(items.stream().allMatch(x -> x.text().args().get(0).toString().contains("diamond")));

        DetailModel low = DetailModel.forPack(r, id("PS_Lang_Low.zip"), List.of(), "en_us");
        List<DetailModel.Row> keys = low.flatten(Map.of(), "test.shared", FileCategory.ALL).stream()
            .filter(x -> x.type() == DetailModel.RowType.ITEM).toList();
        assertEquals(1, keys.size());
        assertEquals("packscope.test.shared", keys.get(0).text().args().get(0));
        assertEquals("\"HIGH\"", keys.get(0).text().args().get(1));
        assertEquals("\"LOW\"", keys.get(0).text().args().get(2));

        List<DetailModel.Row> none = a.flatten(Map.of(), "no-such-thing", FileCategory.ALL);
        assertEquals(1, none.size());
        assertEquals("packscope.detail.no_results", none.get(0).text().key());
    }

    @Test
    void filterByFileType() {
        ScanResult r = eval();
        DetailModel a = DetailModel.forPack(r, id("PS_A_Base.zip"), List.of(), "en_us");
        List<DetailModel.Row> tex = a.flatten(Map.of(), "", FileCategory.TEXTURES).stream().filter(x -> x.type() == DetailModel.RowType.ITEM).toList();
        assertFalse(tex.isEmpty());
        assertTrue(tex.stream().allMatch(x -> x.text().args().get(0).toString().contains(":textures/")));
        List<DetailModel.Row> other = a.flatten(Map.of(), "", FileCategory.OTHER).stream().filter(x -> x.type() == DetailModel.RowType.ITEM).toList();
        assertTrue(other.stream().allMatch(x -> x.text().args().get(0).toString().contains(":texts/")), other.toString());

        DetailModel snd = DetailModel.forPack(r, id("PS_Sound_Add.zip"), List.of(), "en_us");
        assertFalse(snd.flatten(Map.of(), "", FileCategory.SOUNDS).stream().noneMatch(x -> x.type() == DetailModel.RowType.ITEM));
        assertEquals("packscope.detail.no_results", snd.flatten(Map.of(), "", FileCategory.MODELS).get(0).text().key());

        assertEquals(FileCategory.TEXTURES, FileCategory.of("minecraft:textures/item/diamond.png"));
        assertEquals(FileCategory.TEXTURES, FileCategory.of("minecraft:textures/item/diamond.png.mcmeta"));
        assertEquals(FileCategory.MODELS, FileCategory.of("minecraft:blockstates/stone.json"));
        assertEquals(FileCategory.LANG, FileCategory.of("minecraft:lang/en_us.json"));
        assertEquals(FileCategory.SOUNDS, FileCategory.of("minecraft:sounds.json"));
        assertEquals(FileCategory.OTHER, FileCategory.of("minecraft:shaders/core/x.json"));
        assertTrue(FileCategory.isPreviewable("minecraft:textures/item/diamond.png"));
        assertFalse(FileCategory.isPreviewable("minecraft:textures/item/diamond.png.mcmeta"));
    }

    // ------------------------------------------------------------------------------------------------ numbers, names

    @Test
    void numberFormatFollowsTheGameLanguage() {
        assertEquals("1.323", Numbers.format(1323, "de_de"));
        assertEquals("1,323", Numbers.format(1323, "en_us"));
        assertEquals("7", Numbers.format(7, "de_de"));
        Line l = Counts.count("lang_keys", 1323, "de_de");
        assertEquals("packscope.count.lang_keys.many", l.key());
        assertEquals("1.323", l.args().get(0));
        assertEquals("packscope.count.files.one", Counts.count("files", 1, "en_us").key());
    }

    @Test
    void longNamesAreShortened() {
        assertEquals("abc", Names.shorten("abc", 5));
        assertEquals("abcd…", Names.shorten("abcdefgh", 5));
        assertNotNull(StatusLook.of(PackStatus.INTENDED_ONLY, S));
        PackscopeSettings noIntended = new PackscopeSettings(true, true, true, true, List.of(), 64, true, false, false, false, false);
        assertEquals(null, StatusLook.of(PackStatus.INTENDED_ONLY, noIntended));
        assertEquals(null, StatusLook.of(PackStatus.NO_CONFLICT, S));
        assertEquals(StatusLook.COVERED, StatusLook.of(PackStatus.COMPLETELY_COVERED, S));
    }
}
