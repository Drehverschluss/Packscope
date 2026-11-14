package com.drehverschluss.packscope.gui.model;

import com.drehverschluss.packscope.core.IntendedMarking;
import com.drehverschluss.packscope.core.PackRole;
import com.drehverschluss.packscope.core.PackStatus;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.core.ScanResult.FileConflict;
import com.drehverschluss.packscope.core.ScanResult.FilteredFile;
import com.drehverschluss.packscope.core.ScanResult.LangConflict;
import com.drehverschluss.packscope.core.ScanResult.LangLoser;
import com.drehverschluss.packscope.core.ScanResult.MarkingState;
import com.drehverschluss.packscope.core.ScanResult.ModOverride;
import com.drehverschluss.packscope.core.ScanResult.PackSummary;
import com.drehverschluss.packscope.core.ScanResult.PairConflict;
import com.drehverschluss.packscope.core.ScanResult.SoundReplacement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Rows of the detail screen (Konzept 5.2) as a tree of sections, groups and rows, built from one {@link ScanResult}.
 * Rows of a section or group are only built when it is expanded (or when a search/filter has to look into it), and
 * {@link #flatten} returns only what is visible, so the screen can draw just the rows on screen.
 */
public final class DetailModel {
    public enum RowType {SECTION, GROUP, ITEM, PACK, PAIR, MARKING, INFO}

    /** Button in a row. */
    public record Action(Kind kind, String winner, String loser, @Nullable IntendedMarking marking) {
        public enum Kind {MARK, UNMARK, REMOVE}

        public static Action mark(String winner, String loser) {
            return new Action(Kind.MARK, winner, loser, null);
        }

        public static Action unmark(String winner, String loser) {
            return new Action(Kind.UNMARK, winner, loser, null);
        }

        public static Action remove(IntendedMarking m) {
            return new Action(Kind.REMOVE, m.winner(), m.loser(), m);
        }

        public String key() {
            return "packscope.button." + kind.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One visible row.
     *
     * @param text            main text
     * @param tags            short grey/yellow texts after the main text
     * @param hover           tooltip lines (e.g. full language texts), may be empty
     * @param targetPack      click opens the view of this pack (all-packs view)
     * @param previewLocation texture that the preview can show for this row
     * @param status          status icon in front of the row (pack rows)
     */
    public record Row(RowType type, String id, int depth, Line text, List<Line> tags, List<Line> hover, boolean expandable,
                      boolean expanded, @Nullable Action action, @Nullable String targetPack, @Nullable String previewLocation,
                      @Nullable PackStatus status) {
        Row withExpanded(boolean e) {
            return new Row(type, id, depth, text, tags, hover, expandable, e, action, targetPack, previewLocation, status);
        }
    }

    /** A node of the tree: a row and, for sections and groups, children built on demand. */
    static final class Node {
        final Row row;
        final boolean defaultExpanded;
        @Nullable
        final Supplier<List<Node>> childSupplier;
        @Nullable
        final FileCategory category;
        final String searchText;
        @Nullable
        private List<Node> children;

        Node(Row row, boolean defaultExpanded, @Nullable Supplier<List<Node>> childSupplier, @Nullable FileCategory category,
             String searchText) {
            this.row = row;
            this.defaultExpanded = defaultExpanded;
            this.childSupplier = childSupplier;
            this.category = category;
            this.searchText = searchText.toLowerCase(Locale.ROOT);
        }

        boolean parent() {
            return childSupplier != null;
        }

        List<Node> children() {
            if (children == null) children = childSupplier == null ? List.of() : List.copyOf(childSupplier.get());
            return children;
        }

        boolean built() {
            return children != null;
        }

        boolean leafMatches(String query, FileCategory filter) {
            if (category != null && !filter.accepts(category)) return false;
            if (category == null && filter != FileCategory.ALL && row.type == RowType.ITEM) return false;
            return query.isEmpty() || searchText.contains(query);
        }
    }

    private final List<Node> roots;
    private final ScanResult result;
    @Nullable
    private final String packId;

    private DetailModel(ScanResult result, @Nullable String packId, List<Node> roots) {
        this.result = result;
        this.packId = packId;
        this.roots = roots;
    }

    public ScanResult result() {
        return result;
    }

    /** Pack of the "this pack" view, null for the all-packs view. */
    @Nullable
    public String packId() {
        return packId;
    }

    /** Number of tree nodes whose rows are built (for tests: collapsed groups stay unbuilt). */
    public int builtParents() {
        int[] n = {0};
        countBuilt(roots, n);
        return n[0];
    }

    private static void countBuilt(List<Node> nodes, int[] n) {
        for (Node node : nodes) {
            if (node.parent() && node.built()) {
                n[0]++;
                countBuilt(node.children, n);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ flatten

    /**
     * Visible rows.
     *
     * @param toggles  ids the user expanded (true) or collapsed (false); others use their default
     * @param query    search text (path, namespace, language key; pack names in the all-packs view), any case
     * @param filter   file type filter
     */
    public List<Row> flatten(Map<String, Boolean> toggles, String query, FileCategory filter) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        boolean filtering = !q.isEmpty() || filter != FileCategory.ALL;
        List<Row> out = new ArrayList<>();
        for (Node n : roots) add(n, toggles, q, filter, filtering, out);
        if (out.isEmpty()) {
            out.add(info("packscope.detail.empty", filtering ? Line.of(Line.Tone.GREY, "packscope.detail.no_results")
                : Line.of(Line.Tone.GREY, "packscope.detail.no_conflicts")));
        }
        return out;
    }

    private static void add(Node n, Map<String, Boolean> toggles, String q, FileCategory filter, boolean filtering, List<Row> out) {
        if (!n.parent()) {
            if (!filtering || n.leafMatches(q, filter)) out.add(n.row);
            return;
        }
        if (filtering && !visible(n, q, filter)) return;
        Boolean t = toggles.get(n.row.id());
        boolean expanded = t != null ? t : (!q.isEmpty() || n.defaultExpanded);
        out.add(n.row.withExpanded(expanded));
        if (!expanded) return;
        for (Node c : n.children()) add(c, toggles, q, filter, filtering, out);
    }

    private static boolean visible(Node n, String q, FileCategory filter) {
        if (!n.parent()) return n.leafMatches(q, filter);
        for (Node c : n.children()) if (visible(c, q, filter)) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ "this pack"

    /**
     * Rows of the "this pack" view.
     *
     * @param markings current markings (for the notes of intended groups), index = {@code PairConflict.markingIndex}
     */
    public static DetailModel forPack(ScanResult r, String packId, List<IntendedMarking> markings, String lang) {
        Names names = new Names(r);
        ConflictView v = ConflictView.of(r, packId);
        List<Node> roots = new ArrayList<>();
        PackSummary s = r.summary(packId);

        List<Node> warnings = new ArrayList<>();
        if (s != null && s.status() == PackStatus.COMPLETELY_COVERED) {
            warnings.add(leaf(info("w:covered", s.onlyCopies()
                ? Line.of(Line.Tone.RED, "packscope.tooltip.only_copies", TooltipModel.topNames(s.copiesOf(), names, lang))
                : Line.of(Line.Tone.RED, "packscope.tooltip.covered"))));
        }
        for (PairConflict p : v.againstMarking()) {
            warnings.add(leaf(info("w:against:" + p.upper() + ">" + p.lower(),
                Line.of(Line.Tone.YELLOW, "packscope.tooltip.against_marking", names.display(p.lower()), names.display(p.upper())))));
        }
        if (s != null && s.replacedSoundEvents() > 0) {
            warnings.add(leaf(info("w:sounds", Line.of(Line.Tone.YELLOW, "packscope.tooltip.replaces_sounds",
                Counts.count("sound_events", s.replacedSoundEvents(), lang)))));
        }
        if (!warnings.isEmpty()) roots.add(section("warnings", warnings.size(), lang, true, () -> warnings));

        if (!v.overrides().isEmpty()) {
            roots.add(section("overrides", v.overrides().size(), lang, true,
                () -> v.overrides().stream().map(g -> group(r, g, names, markings, lang)).toList()));
        }
        if (!v.overriddenBy().isEmpty()) {
            roots.add(section("overridden_by", v.overriddenBy().size(), lang, true,
                () -> v.overriddenBy().stream().map(g -> group(r, g, names, markings, lang)).toList()));
        }
        if (!v.replacedSounds().isEmpty()) {
            roots.add(section("sounds", v.replacedSounds().size(), lang, true, () -> {
                List<Node> list = new ArrayList<>();
                for (SoundReplacement sr : v.replacedSounds()) {
                    Row row = row(RowType.ITEM, "snd:" + sr.event(), 1, Line.of("packscope.detail.sound_replaced", sr.event(),
                        displays(sr.replacedPacks(), names), joined(sr.lostSounds())), tags(sr.intended() ? intendedTag() : null), null);
                    list.add(new Node(row, false, null, FileCategory.SOUNDS, sr.event()));
                }
                return list;
            }));
        }
        if (!v.hides().isEmpty()) {
            roots.add(section("hides", v.hides().size(), lang, true, () -> {
                List<Node> list = new ArrayList<>();
                for (FilteredFile ff : v.hides()) {
                    Row row = row(RowType.ITEM, "hide:" + ff.location(), 1, Line.of("packscope.detail.hides", ff.location(),
                        displays(ff.hiddenPacks(), names)), tags(ff.intended() ? intendedTag() : null), preview(ff.location()));
                    list.add(new Node(row, false, null, FileCategory.of(ff.location()), ff.location()));
                }
                return list;
            }));
        }
        if (!v.modGroups().isEmpty()) {
            roots.add(section("mods", v.modGroups().size(), lang, true, () -> {
                List<Node> list = new ArrayList<>();
                for (ConflictView.ModGroup mg : v.modGroups()) {
                    String id = "mod:" + mg.ownerKey();
                    Row header = new Row(RowType.GROUP, id, 1, Line.of("packscope.detail.mod_group",
                        Names.shorten(mg.ownerName(), Names.MAX_LENGTH), Counts.count("items", mg.count(), lang)), mg.reversed() > 0
                        ? List.of(Line.of(Line.Tone.GREY, "packscope.detail.tag.mod_wins_count", Numbers.format(mg.reversed(), lang)))
                        : List.of(), List.of(), true, false, null, null, null, null);
                    list.add(new Node(header, false, () -> {
                        List<Node> rows = new ArrayList<>();
                        for (ModOverride mo : mg.items()) rows.add(modRow(mo, lang));
                        return rows;
                    }, null, ""));
                }
                return list;
            }));
        }
        if (!v.harmless().isEmpty()) {
            roots.add(section("harmless", v.harmless().size(), lang, false, () -> {
                List<Node> list = new ArrayList<>();
                for (FileConflict fc : v.harmless()) {
                    Line text = fc.winner().equals(packId)
                        ? Line.of(Line.Tone.GREY, "packscope.detail.harmless_copied_by", fc.location(), displays(fc.harmless(), names))
                        : Line.of(Line.Tone.GREY, "packscope.detail.harmless_copy_of", fc.location(), names.display(fc.winner()));
                    Row row = row(RowType.ITEM, "dup:" + fc.location(), 1, text, List.of(), preview(fc.location()));
                    list.add(new Node(row, false, null, FileCategory.of(fc.location()), fc.location()));
                }
                return list;
            }));
        }
        return new DetailModel(r, packId, roots);
    }

    private static Node group(ScanResult r, ConflictView.Group g, Names names, List<IntendedMarking> markings, String lang) {
        String id = (g.overrides() ? "over:" : "under:") + g.counterpart();
        List<Line> tags = new ArrayList<>();
        if (g.intended()) {
            tags.add(intendedTag());
            IntendedMarking m = g.marking() >= 0 && g.marking() < markings.size() ? markings.get(g.marking()) : null;
            if (m != null && m.note() != null && !m.note().isBlank()) {
                tags.add(Line.of(Line.Tone.GREY, "packscope.detail.note", Names.shorten(m.note(), 60)));
            }
        }
        if (g.againstMarking()) tags.add(Line.of(Line.Tone.YELLOW, "packscope.detail.tag.against_marking"));
        Line text = Line.of(g.intended() ? Line.Tone.GREY : Line.Tone.NORMAL, "packscope.detail.group",
            names.display(g.counterpart()), Counts.parts(g, lang));
        Action action = g.intended() ? Action.unmark(g.winner(), g.loser()) : Action.mark(g.winner(), g.loser());
        Row header = new Row(RowType.GROUP, id, 1, text, List.copyOf(tags), List.of(), true, false, action, g.counterpart(), null, null);
        return new Node(header, !g.intended(), () -> {
            List<Node> rows = new ArrayList<>();
            for (ConflictView.Item it : g.items()) rows.add(itemRow(r, g, it, names, lang));
            return rows;
        }, null, "");
    }

    private static Node itemRow(ScanResult r, ConflictView.Group g, ConflictView.Item it, Names names, String lang) {
        String id = (g.overrides() ? "over:" : "under:") + g.counterpart() + "|" + it.kind() + "|" + it.location()
            + (it.langKey() == null ? "" : "|" + it.langKey()) + (it.dead() ? "|dead" : "");
        List<Line> tags = new ArrayList<>();
        List<Line> hover = new ArrayList<>();
        Line text;
        switch (it.kind()) {
            case LANG_KEY -> {
                LangConflict lc = (LangConflict) it.source();
                String loserText = "";
                for (LangLoser l : lc.losers()) {
                    if (l.pack().equals(g.loser())) loserText = l.text();
                }
                text = Line.of("packscope.detail.lang", lc.key(), quote(lc.winnerText(), 40), quote(loserText, 40));
                tags.add(Line.of(Line.Tone.DARK_GREY, "packscope.detail.tag.language", lc.language()));
                hover.add(Line.of(Line.Tone.GREY, "packscope.detail.lang_file", lc.location()));
                hover.add(Line.of("packscope.detail.lang_text", names.display(lc.winner()), quote(lc.winnerText(), 200)));
                for (LangLoser l : lc.losers()) {
                    hover.add(Line.of(Line.Tone.GREY, "packscope.detail.lang_text", names.display(l.pack()), quote(l.text(), 200)));
                }
            }
            case SOUND_EVENT -> {
                SoundReplacement sr = (SoundReplacement) it.source();
                text = Line.of("packscope.detail.sound", sr.event(), joined(sr.lostSounds()));
            }
            case FILTERED -> {
                text = Line.of("packscope.detail.file", it.location());
                tags.add(Line.of(Line.Tone.GREY, "packscope.detail.tag.filtered"));
            }
            default -> {
                text = Line.of("packscope.detail.file", it.location());
                if (it.blockstate()) tags.add(Line.of(Line.Tone.GREY, "packscope.detail.tag.blockstate"));
                if (it.dead()) tags.add(Line.of(Line.Tone.GREY, "packscope.detail.tag.dead"));
            }
        }
        if (g.intended()) text = text.withTone(Line.Tone.GREY);
        Row row = new Row(RowType.ITEM, id, 2, text, List.copyOf(tags), List.copyOf(hover), false, false, null, null,
            it.kind() == ConflictView.ItemKind.FILE || it.kind() == ConflictView.ItemKind.FILTERED ? preview(it.location()) : null, null);
        return new Node(row, false, null, it.category(), it.searchText());
    }

    private static Node modRow(ModOverride mo, String lang) {
        Line text = switch (mo.type()) {
            case LANG_KEY -> Line.of("packscope.detail.mod_lang", mo.location(), Counts.count("lang_keys", mo.count(), lang));
            case SOUND_EVENT -> Line.of("packscope.detail.sound_plain", mo.location());
            case FILE -> Line.of("packscope.detail.file", mo.location());
        };
        List<Line> tags = mo.reversed() ? List.of(Line.of(Line.Tone.GREY, "packscope.detail.tag.mod_wins")) : List.of();
        Row row = row(RowType.ITEM, "modrow:" + mo.target() + "|" + mo.type() + "|" + mo.location(), 2, text.withTone(Line.Tone.GREY), tags,
            mo.type() == ScanResult.OverrideType.FILE ? preview(mo.location()) : null);
        FileCategory cat = switch (mo.type()) {
            case LANG_KEY -> FileCategory.LANG;
            case SOUND_EVENT -> FileCategory.SOUNDS;
            case FILE -> FileCategory.of(mo.location());
        };
        return new Node(row, false, null, cat, mo.location());
    }

    // ------------------------------------------------------------------------------------------------ "all packs"

    /** Rows of the "all packs" view: packs with status, pairs, covered packs, against marking, markings, mod info. */
    public static DetailModel forAll(ScanResult r, String lang) {
        Names names = new Names(r);
        List<Node> roots = new ArrayList<>();
        List<PackSummary> rps = r.packs().stream().filter(p -> p.role() == PackRole.RESOURCE_PACK).toList();

        roots.add(section("packs", rps.size(), lang, true, () -> {
            List<Node> list = new ArrayList<>();
            for (PackSummary p : rps) {
                List<Line> tags = new ArrayList<>();
                tags.add(Line.of(StatusLook.tone(p.status()), StatusLook.key(p.status())));
                if (p.wins() + p.losses() > 0) {
                    tags.add(Line.of(Line.Tone.GREY, "packscope.detail.pack_numbers", Numbers.format(p.wins(), lang),
                        Numbers.format(p.losses(), lang)));
                }
                Row row = new Row(RowType.PACK, "pack:" + p.id(), 1, Line.of("packscope.detail.pack_row", names.display(p.id())),
                    List.copyOf(tags), List.of(Line.of(Line.Tone.GREY, "packscope.detail.open_pack")), false, false, null, p.id(), null,
                    p.status());
                list.add(new Node(row, false, null, null, p.title() + " " + p.id()));
            }
            return list;
        }));

        List<PairConflict> pairs = new ArrayList<>(r.pairs());
        pairs.sort(Comparator.comparing(PairConflict::intended).thenComparing(Comparator.comparingInt(PairConflict::total).reversed()));
        if (!pairs.isEmpty()) {
            roots.add(section("pairs", pairs.size(), lang, true, () -> {
                List<Node> list = new ArrayList<>();
                for (PairConflict p : pairs) {
                    List<Line> tags = new ArrayList<>();
                    if (p.intended()) tags.add(intendedTag());
                    if (p.againstMarking()) tags.add(Line.of(Line.Tone.YELLOW, "packscope.detail.tag.against_marking"));
                    Line text = Line.of(p.intended() ? Line.Tone.GREY : Line.Tone.NORMAL, "packscope.detail.pair",
                        names.display(p.upper()), names.display(p.lower()), Counts.parts(p.files(), p.langKeys(), p.soundEvents(), p.filtered(), lang));
                    Row row = new Row(RowType.PAIR, "pair:" + p.upper() + ">" + p.lower(), 1, text, List.copyOf(tags),
                        List.of(Line.of(Line.Tone.GREY, "packscope.detail.open_pack")), false, false,
                        p.intended() ? Action.unmark(p.upper(), p.lower()) : Action.mark(p.upper(), p.lower()), p.upper(), null, null);
                    list.add(new Node(row, false, null, null, names.title(p.upper()) + " " + names.title(p.lower())));
                }
                return list;
            }));
        }

        List<PackSummary> covered = r.coveredPacks();
        if (!covered.isEmpty()) {
            roots.add(section("covered", covered.size(), lang, true, () -> {
                List<Node> list = new ArrayList<>();
                for (PackSummary p : covered) {
                    Line reason = p.onlyCopies()
                        ? Line.of(Line.Tone.GREY, "packscope.detail.only_copies_of", TooltipModel.topNames(p.copiesOf(), names, lang))
                        : Line.of(Line.Tone.GREY, "packscope.detail.overridden_by_list", TooltipModel.topNames(p.losesTo(), names, lang));
                    Row row = new Row(RowType.PACK, "covered:" + p.id(), 1, Line.of(Line.Tone.RED, "packscope.detail.pack_row",
                        names.display(p.id())), List.of(reason), List.of(Line.of(Line.Tone.GREY, "packscope.detail.open_pack")), false,
                        false, null, p.id(), null, p.status());
                    list.add(new Node(row, false, null, null, p.title() + " " + p.id()));
                }
                return list;
            }));
        }

        List<PairConflict> against = r.pairs().stream().filter(PairConflict::againstMarking).toList();
        if (!against.isEmpty()) {
            roots.add(section("against", against.size(), lang, true, () -> {
                List<Node> list = new ArrayList<>();
                for (PairConflict p : against) {
                    Row row = new Row(RowType.PAIR, "against:" + p.upper() + ">" + p.lower(), 1, Line.of(Line.Tone.YELLOW,
                        "packscope.tooltip.against_marking", names.display(p.lower()), names.display(p.upper())), List.of(),
                        List.of(Line.of(Line.Tone.GREY, "packscope.detail.open_pack")), false, false, null, p.lower(), null, null);
                    list.add(new Node(row, false, null, null, names.title(p.upper()) + " " + names.title(p.lower())));
                }
                return list;
            }));
        }

        roots.add(section("markings", r.markings().size(), lang, true, () -> {
            List<Node> list = new ArrayList<>();
            if (r.markings().isEmpty()) {
                list.add(new Node(info("markings:none", Line.of(Line.Tone.GREY, "packscope.detail.no_markings")), false, null, null, ""));
            }
            for (MarkingState m : r.markings()) {
                Object winner = m.winnerPack() != null ? names.display(m.winnerPack()) : Names.shorten(m.marking().winner(), Names.MAX_LENGTH);
                Object loser = m.loserPack() != null ? names.display(m.loserPack()) : Names.shorten(m.marking().loser(), Names.MAX_LENGTH);
                Line.Tone tone = switch (m.status()) {
                    case WORKS -> Line.Tone.GREEN;
                    case REVERSED -> Line.Tone.YELLOW;
                    default -> Line.Tone.GREY;
                };
                List<Line> tags = new ArrayList<>();
                tags.add(m.status() == ScanResult.MarkingStatus.WORKS
                    ? Line.of(tone, "packscope.marking.works", Counts.count("items", m.items(), lang))
                    : Line.of(tone, "packscope.marking." + m.status().id()));
                if (m.marking().note() != null && !m.marking().note().isBlank()) {
                    tags.add(Line.of(Line.Tone.GREY, "packscope.detail.note", Names.shorten(m.marking().note(), 60)));
                }
                List<Line> hover = List.of(Line.of(Line.Tone.GREY, "packscope.detail.marking_ids", m.marking().winner(), m.marking().loser()));
                Row row = new Row(RowType.MARKING, "marking:" + m.index() + ":" + m.marking().winner() + ">" + m.marking().loser(), 1,
                    Line.of("packscope.detail.marking_row", winner, loser), List.copyOf(tags), hover, false, false,
                    Action.remove(m.marking()), m.winnerPack(), null, null);
                list.add(new Node(row, false, null, null, m.marking().winner() + " " + m.marking().loser() + " "
                    + (m.winnerPack() == null ? "" : names.title(m.winnerPack())) + " " + (m.loserPack() == null ? "" : names.title(m.loserPack()))));
            }
            return list;
        }));

        List<PackSummary> withMods = rps.stream().filter(p -> p.modOverrides() > 0).toList();
        if (!withMods.isEmpty()) {
            Map<String, String> ownerNames = new HashMap<>();
            for (ModOverride mo : r.modOverrides()) ownerNames.putIfAbsent(mo.ownerKey(), mo.ownerName());
            roots.add(section("mods_all", withMods.size(), lang, false, () -> {
                List<Node> list = new ArrayList<>();
                for (PackSummary p : withMods) {
                    List<Object> owners = new ArrayList<>();
                    p.modOverridesByOwner().entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(e -> owners.add(Line.of("packscope.owner_count",
                            Names.shorten(ownerNames.getOrDefault(e.getKey(), e.getKey()), Names.MAX_LENGTH), Numbers.format(e.getValue(), lang))));
                    Row row = new Row(RowType.PACK, "modsum:" + p.id(), 1, Line.of(Line.Tone.GREY, "packscope.detail.mod_sum",
                        names.display(p.id()), Counts.count("items", p.modOverrides(), lang)), List.of(Line.of(Line.Tone.GREY,
                        "packscope.detail.plain", new Line.Parts(owners))), List.of(Line.of(Line.Tone.GREY, "packscope.detail.open_pack")),
                        false, false, null, p.id(), null, null);
                    list.add(new Node(row, false, null, null, p.title() + " " + p.id()));
                }
                return list;
            }));
        }
        return new DetailModel(r, null, roots);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Sections that count packs say "1 pack" / "3 packs", the others just the number. */
    private static final java.util.Set<String> PACK_SECTIONS = java.util.Set.of("overrides", "overridden_by", "mods_all");

    private static Node section(String id, int count, String lang, boolean expanded, Supplier<List<Node>> children) {
        Object n = PACK_SECTIONS.contains(id) ? Counts.count("packs", count, lang) : Numbers.format(count, lang);
        Row row = new Row(RowType.SECTION, "section:" + id, 0, Line.of(Line.Tone.HEADER, "packscope.section." + id, n), List.of(),
            List.of(), true, false, null, null, null, null);
        return new Node(row, expanded, children, null, "");
    }

    private static Node leaf(Row row) {
        return new Node(row, false, null, null, "");
    }

    private static Row info(String id, Line text) {
        return new Row(RowType.INFO, id, 1, text, List.of(), List.of(), false, false, null, null, null, null);
    }

    private static Row row(RowType type, String id, int depth, Line text, List<Line> tags, @Nullable String preview) {
        return new Row(type, id, depth, text, List.copyOf(tags), List.of(), false, false, null, null, preview, null);
    }

    private static Line intendedTag() {
        return Line.of(Line.Tone.GREY, "packscope.detail.tag.intended");
    }

    private static List<Line> tags(@Nullable Line tag) {
        return tag == null ? List.of() : List.of(tag);
    }

    @Nullable
    private static String preview(String location) {
        return FileCategory.isPreviewable(location) ? location : null;
    }

    private static Line.Parts displays(List<String> ids, Names names) {
        List<Object> list = new ArrayList<>();
        for (String id : ids) list.add(names.display(id));
        return new Line.Parts(list);
    }

    private static Line.Parts joined(List<String> values) {
        return new Line.Parts(new ArrayList<>(values));
    }

    static String quote(String text, int max) {
        String t = text == null ? "" : text.replace('\n', ' ');
        return "\"" + Names.shorten(t, max) + "\"";
    }
}
