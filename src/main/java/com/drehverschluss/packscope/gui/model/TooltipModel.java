package com.drehverschluss.packscope.gui.model;

import com.drehverschluss.packscope.core.PackStatus;
import com.drehverschluss.packscope.core.PackscopeSettings;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.core.ScanResult.ModOverride;
import com.drehverschluss.packscope.core.ScanResult.PackSummary;
import com.drehverschluss.packscope.core.ScanResult.PairConflict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lines of the tooltip over the icon of a pack entry (Konzept 5.1): status in the icon colour, warnings with ⚠, the three
 * biggest packs it overrides and that override it ("+ N more packs"), intended conflicts and mod/vanilla info in grey,
 * "Click for details".
 */
public final class TooltipModel {
    /** Counterpart packs named per list; the rest becomes "+ N more packs". */
    public static final int SHOWN_PACKS = 3;

    private TooltipModel() {}

    /** Tooltip for "evaluation still running". */
    public static List<Line> checking() {
        return List.of(Line.of(Line.Tone.GREY, "packscope.status.checking"));
    }

    /**
     * @param lang game language code for the number format
     */
    public static List<Line> build(ScanResult result, String packId, PackscopeSettings settings, String lang) {
        PackSummary s = result.summary(packId);
        List<Line> lines = new ArrayList<>();
        if (s == null) return lines;
        Names names = new Names(result);
        ConflictView view = ConflictView.of(result, packId);
        lines.add(Line.of(StatusLook.tone(s.status()), StatusLook.key(s.status())));

        // warnings
        if (s.status() == PackStatus.COMPLETELY_COVERED) {
            if (s.onlyCopies()) {
                lines.add(Line.of(Line.Tone.RED, "packscope.tooltip.only_copies", topNames(s.copiesOf(), names, lang)));
            } else {
                lines.add(Line.of(Line.Tone.RED, "packscope.tooltip.covered"));
            }
        }
        for (PairConflict p : view.againstMarking()) {
            // the marking says "lower over upper", the order is "upper over lower"
            lines.add(Line.of(Line.Tone.YELLOW, "packscope.tooltip.against_marking", names.display(p.lower()), names.display(p.upper())));
        }
        if (s.replacedSoundEvents() > 0) {
            lines.add(Line.of(Line.Tone.YELLOW, "packscope.tooltip.replaces_sounds",
                Counts.count("sound_events", s.replacedSoundEvents(), lang)));
        }

        // not intended conflicts
        List<ConflictView.Group> over = view.overrides().stream().filter(g -> !g.intended()).toList();
        List<ConflictView.Group> under = view.overriddenBy().stream().filter(g -> !g.intended()).toList();
        groupList(lines, "packscope.tooltip.overrides", over, names, lang, Line.Tone.NEUTRAL);
        groupList(lines, "packscope.tooltip.overridden_by", under, names, lang, Line.Tone.NEUTRAL);

        // intended conflicts (grey)
        List<ConflictView.Group> intended = new ArrayList<>();
        view.overrides().stream().filter(ConflictView.Group::intended).forEach(intended::add);
        view.overriddenBy().stream().filter(ConflictView.Group::intended).forEach(intended::add);
        intended.sort(Comparator.comparingInt(ConflictView.Group::total).reversed());
        for (int i = 0; i < intended.size() && i < SHOWN_PACKS; i++) {
            ConflictView.Group g = intended.get(i);
            lines.add(Line.of(Line.Tone.GREY, g.overrides() ? "packscope.tooltip.intended_overrides" : "packscope.tooltip.intended_overridden",
                Counts.parts(g, lang), names.display(g.counterpart())));
        }
        if (intended.size() > SHOWN_PACKS) {
            lines.add(Line.of(Line.Tone.GREY, "packscope.tooltip.more_packs", Numbers.format(intended.size() - SHOWN_PACKS, lang)));
        }

        // mod/vanilla info (grey)
        if (settings.showModOverridesInTooltip()) {
            if (s.modOverrides() > 0) {
                Map<String, String> ownerNames = new HashMap<>();
                for (ModOverride mo : result.modOverrides()) ownerNames.putIfAbsent(mo.ownerKey(), mo.ownerName());
                lines.add(Line.of(Line.Tone.GREY, "packscope.tooltip.mod_overrides", Counts.count("items", s.modOverrides(), lang),
                    topOwners(s.modOverridesByOwner(), ownerNames, lang)));
            }
            if (s.lostToMods() > 0) {
                lines.add(Line.of(Line.Tone.GREY, "packscope.tooltip.lost_to_mods", Counts.count("items", s.lostToMods(), lang)));
            }
        }
        lines.add(Line.of(Line.Tone.DARK_GREY, "packscope.tooltip.click"));
        return lines;
    }

    /**
     * Status of a pack that holds other packs as children (e.g. {@code eatinganimations_compat}, Phase 5C): the most
     * important warning of a child first, else wins/loses/both from all children, else intended only, else no conflict.
     */
    public static PackStatus parentStatus(List<PackStatus> children) {
        for (PackStatus w : List.of(PackStatus.COMPLETELY_COVERED, PackStatus.AGAINST_MARKING, PackStatus.REPLACES_SOUNDS)) {
            if (children.contains(w)) return w;
        }
        boolean wins = false;
        boolean loses = false;
        for (PackStatus s : children) {
            if (s == PackStatus.WINS || s == PackStatus.WINS_AND_LOSES) wins = true;
            if (s == PackStatus.LOSES || s == PackStatus.WINS_AND_LOSES) loses = true;
        }
        if (wins && loses) return PackStatus.WINS_AND_LOSES;
        if (wins) return PackStatus.WINS;
        if (loses) return PackStatus.LOSES;
        return children.contains(PackStatus.INTENDED_ONLY) ? PackStatus.INTENDED_ONLY : PackStatus.NO_CONFLICT;
    }

    /** Tooltip of a parent pack: combined status, then every child with its status (top first). */
    public static List<Line> buildParent(ScanResult result, List<String> childIds, String lang) {
        List<Line> lines = new ArrayList<>();
        Names names = new Names(result);
        List<PackStatus> statuses = new ArrayList<>();
        List<Line> rows = new ArrayList<>();
        for (String id : childIds) {
            PackSummary s = result.summary(id);
            if (s == null) continue;
            statuses.add(s.status());
            rows.add(Line.of(Line.Tone.NEUTRAL, "packscope.tooltip.child", names.display(id),
                Line.of(StatusLook.tone(s.status()), StatusLook.key(s.status()))));
        }
        PackStatus status = parentStatus(statuses);
        lines.add(Line.of(StatusLook.tone(status), StatusLook.key(status)));
        lines.add(Line.of(Line.Tone.NEUTRAL, "packscope.tooltip.children", Numbers.format(rows.size(), lang)));
        lines.addAll(rows);
        lines.add(Line.of(Line.Tone.DARK_GREY, "packscope.tooltip.click"));
        return lines;
    }

    private static void groupList(List<Line> lines, String header, List<ConflictView.Group> groups, Names names, String lang,
                                  Line.Tone tone) {
        if (groups.isEmpty()) return;
        lines.add(Line.of(tone, header));
        for (int i = 0; i < groups.size() && i < SHOWN_PACKS; i++) {
            ConflictView.Group g = groups.get(i);
            lines.add(Line.of(tone, "packscope.tooltip.pack_line", names.display(g.counterpart()), Counts.parts(g, lang)));
        }
        if (groups.size() > SHOWN_PACKS) {
            lines.add(Line.of(Line.Tone.GREY, "packscope.tooltip.more_packs", Numbers.format(groups.size() - SHOWN_PACKS, lang)));
        }
    }

    /** "A, B, C + 2 more" from a pack id → count map, biggest first. */
    static Object topNames(Map<String, Integer> counts, Names names, String lang) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        List<Object> parts = new ArrayList<>();
        for (int i = 0; i < list.size() && i < SHOWN_PACKS; i++) parts.add(names.display(list.get(i).getKey()));
        if (list.size() > SHOWN_PACKS) parts.add(Line.of("packscope.more", Numbers.format(list.size() - SHOWN_PACKS, lang)));
        return new Line.Parts(parts);
    }

    private static Object topOwners(Map<String, Integer> counts, Map<String, String> ownerNames, String lang) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        List<Object> parts = new ArrayList<>();
        for (int i = 0; i < list.size() && i < SHOWN_PACKS; i++) {
            Map.Entry<String, Integer> e = list.get(i);
            parts.add(Line.of("packscope.owner_count", Names.shorten(ownerNames.getOrDefault(e.getKey(), e.getKey()), Names.MAX_LENGTH),
                Numbers.format(e.getValue(), lang)));
        }
        if (list.size() > SHOWN_PACKS) parts.add(Line.of("packscope.more", Numbers.format(list.size() - SHOWN_PACKS, lang)));
        return new Line.Parts(parts);
    }
}
