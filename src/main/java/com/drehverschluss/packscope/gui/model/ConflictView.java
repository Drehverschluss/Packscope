package com.drehverschluss.packscope.gui.model;

import com.drehverschluss.packscope.core.FileKind;
import com.drehverschluss.packscope.core.PackRole;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.core.ScanResult.FileConflict;
import com.drehverschluss.packscope.core.ScanResult.FilteredFile;
import com.drehverschluss.packscope.core.ScanResult.LangConflict;
import com.drehverschluss.packscope.core.ScanResult.ModOverride;
import com.drehverschluss.packscope.core.ScanResult.PackSummary;
import com.drehverschluss.packscope.core.ScanResult.PairConflict;
import com.drehverschluss.packscope.core.ScanResult.SoundReplacement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Everything one resource pack has to do with other packs in one {@link ScanResult}, grouped like the detail screen
 * (Konzept 5.2): which packs it overrides (pairwise, like MO2), which packs override it (always the pack whose file
 * reaches the game), replaced sound events, hidden files, mod/vanilla info and harmless duplicates.
 */
public final class ConflictView {
    public enum ItemKind {FILE, LANG_KEY, SOUND_EVENT, FILTERED}

    /**
     * One contested element.
     *
     * @param dead     a .mcmeta that never applies because a higher pack replaced the texture
     * @param source   the core record ({@link FileConflict}, {@link LangConflict}, {@link SoundReplacement},
     *                 {@link FilteredFile})
     */
    public record Item(ItemKind kind, String location, @Nullable String langKey, boolean dead, Object source) {
        public FileCategory category() {
            return switch (kind) {
                case LANG_KEY -> FileCategory.LANG;
                case SOUND_EVENT -> FileCategory.SOUNDS;
                default -> FileCategory.of(location);
            };
        }

        /** Lower-case text the search looks at: namespace, path and language key. */
        public String searchText() {
            return (location + (langKey == null ? "" : " " + langKey)).toLowerCase(Locale.ROOT);
        }

        public boolean blockstate() {
            return kind == ItemKind.FILE && FileKind.of(location) == FileKind.BLOCKSTATE;
        }

        public boolean matches(String query, FileCategory filter) {
            return filter.accepts(category()) && (query.isEmpty() || searchText().contains(query));
        }
    }

    /**
     * Conflicts of this pack with one other resource pack.
     *
     * @param overrides true: this pack over {@code counterpart} ("Überschreibt"), false: {@code counterpart} over this
     *                  pack ("Wird überschrieben von")
     * @param intended  the pair winner → loser is marked as intended
     * @param marking   index of the covering marking, -1 if none
     */
    public record Group(String pack, String counterpart, boolean overrides, List<Item> items, int files, int langKeys,
                        int soundEvents, int filtered, int dead, boolean intended, boolean againstMarking, int marking) {
        public String winner() {
            return overrides ? pack : counterpart;
        }

        public String loser() {
            return overrides ? counterpart : pack;
        }

        /** Same numbers as the core pair ({@code ScanResult.pair}); .mcmeta without effect come extra in {@link #dead()}. */
        public int total() {
            return files + langKeys + soundEvents + filtered;
        }
    }

    /** Mod/vanilla info for one owner (mod id, {@code minecraft} for vanilla). */
    public record ModGroup(String ownerKey, String ownerName, List<ModOverride> items, int count, int reversed) {}

    private final ScanResult result;
    private final String packId;
    private final List<Group> overrides;
    private final List<Group> overriddenBy;
    private final List<SoundReplacement> replacedSounds = new ArrayList<>();
    private final List<FilteredFile> hides = new ArrayList<>();
    private final List<ModGroup> modGroups;
    private final List<FileConflict> harmless = new ArrayList<>();

    private ConflictView(ScanResult result, String packId) {
        this.result = result;
        this.packId = packId;
        Map<String, Integer> pos = positions(result);
        Set<String> rps = new HashSet<>();
        for (PackSummary s : result.packs()) if (s.role() == PackRole.RESOURCE_PACK) rps.add(s.id());
        int me = pos.getOrDefault(packId, -1);
        Map<String, List<Item>> over = new LinkedHashMap<>();
        Map<String, List<Item>> under = new LinkedHashMap<>();

        for (FileConflict fc : result.fileConflicts()) {
            boolean mine = fc.winner().equals(packId) || fc.losers().contains(packId) || fc.dead().contains(packId)
                || fc.harmless().contains(packId);
            if (!mine) continue;
            Item item = new Item(ItemKind.FILE, fc.location(), null, false, fc);
            List<String> owners = new ArrayList<>();
            owners.add(fc.winner());
            owners.addAll(fc.losers());
            if (owners.contains(packId)) {
                for (String l : fc.losers()) {
                    if (pos.getOrDefault(l, -1) > me) over.computeIfAbsent(l, k -> new ArrayList<>()).add(item);
                }
            }
            if (fc.losers().contains(packId)) under.computeIfAbsent(fc.winner(), k -> new ArrayList<>()).add(item);
            if (fc.dead().contains(packId)) {
                under.computeIfAbsent(fc.winner(), k -> new ArrayList<>()).add(new Item(ItemKind.FILE, fc.location(), null, true, fc));
            }
            if (fc.winner().equals(packId)) {
                for (String d : fc.dead()) {
                    over.computeIfAbsent(d, k -> new ArrayList<>()).add(new Item(ItemKind.FILE, fc.location(), null, true, fc));
                }
            }
            if (!fc.harmless().isEmpty() && (fc.harmless().contains(packId) || fc.winner().equals(packId))) harmless.add(fc);
        }
        for (LangConflict lc : result.langConflicts()) {
            boolean loser = false;
            for (ScanResult.LangLoser l : lc.losers()) loser |= l.pack().equals(packId);
            if (!lc.winner().equals(packId) && !loser) continue;
            Item item = new Item(ItemKind.LANG_KEY, lc.location(), lc.key(), false, lc);
            for (ScanResult.LangLoser l : lc.losers()) {
                if (pos.getOrDefault(l.pack(), -1) > me) over.computeIfAbsent(l.pack(), k -> new ArrayList<>()).add(item);
            }
            if (loser) under.computeIfAbsent(lc.winner(), k -> new ArrayList<>()).add(item);
        }
        for (SoundReplacement sr : result.soundReplacements()) {
            Item item = new Item(ItemKind.SOUND_EVENT, sr.event(), null, false, sr);
            if (sr.pack().equals(packId)) {
                replacedSounds.add(sr);
                for (String l : sr.replacedPacks()) {
                    if (rps.contains(l)) over.computeIfAbsent(l, k -> new ArrayList<>()).add(item);
                }
            } else if (sr.replacedPacks().contains(packId)) {
                under.computeIfAbsent(sr.pack(), k -> new ArrayList<>()).add(item);
            }
        }
        for (FilteredFile ff : result.filtered()) {
            Item item = new Item(ItemKind.FILTERED, ff.location(), null, false, ff);
            if (ff.filterPack().equals(packId)) {
                hides.add(ff);
                if (rps.contains(packId)) {
                    for (String h : ff.hiddenPacks()) {
                        if (rps.contains(h)) over.computeIfAbsent(h, k -> new ArrayList<>()).add(item);
                    }
                }
            } else if (ff.hiddenPacks().contains(packId) && rps.contains(ff.filterPack())) {
                under.computeIfAbsent(ff.filterPack(), k -> new ArrayList<>()).add(item);
            }
        }

        this.overrides = groups(over, true, pos);
        this.overriddenBy = groups(under, false, pos);

        Map<String, List<ModOverride>> byOwner = new LinkedHashMap<>();
        Map<String, String> ownerNames = new HashMap<>();
        for (ModOverride mo : result.modOverrides()) {
            if (!mo.pack().equals(packId)) continue;
            byOwner.computeIfAbsent(mo.ownerKey(), k -> new ArrayList<>()).add(mo);
            ownerNames.put(mo.ownerKey(), mo.ownerName());
        }
        List<ModGroup> mods = new ArrayList<>();
        for (Map.Entry<String, List<ModOverride>> e : byOwner.entrySet()) {
            int count = 0;
            int reversed = 0;
            for (ModOverride mo : e.getValue()) {
                count += mo.count();
                if (mo.reversed()) reversed += mo.count();
            }
            List<ModOverride> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparing(ModOverride::location));
            mods.add(new ModGroup(e.getKey(), ownerNames.get(e.getKey()), List.copyOf(sorted), count, reversed));
        }
        mods.sort(Comparator.comparingInt(ModGroup::count).reversed().thenComparing(ModGroup::ownerName));
        this.modGroups = List.copyOf(mods);
        harmless.sort(Comparator.comparing(FileConflict::location));
    }

    public static ConflictView of(ScanResult result, String packId) {
        return new ConflictView(result, packId);
    }

    static Map<String, Integer> positions(ScanResult result) {
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < result.order().packs().size(); i++) pos.put(result.order().packs().get(i).id(), i);
        return pos;
    }

    private List<Group> groups(Map<String, List<Item>> byPack, boolean overrides, Map<String, Integer> pos) {
        List<Group> list = new ArrayList<>();
        for (Map.Entry<String, List<Item>> e : byPack.entrySet()) {
            List<Item> items = new ArrayList<>(e.getValue());
            items.sort(Comparator.comparing(Item::location).thenComparing(i -> i.langKey() == null ? "" : i.langKey()));
            int files = 0, lang = 0, sounds = 0, filtered = 0, dead = 0;
            for (Item i : items) {
                switch (i.kind()) {
                    case FILE -> {
                        if (i.dead()) dead++;
                        else files++;
                    }
                    case LANG_KEY -> lang++;
                    case SOUND_EVENT -> sounds++;
                    case FILTERED -> filtered++;
                }
            }
            String winner = overrides ? packId : e.getKey();
            String loser = overrides ? e.getKey() : packId;
            PairConflict p = result.pair(winner, loser);
            list.add(new Group(packId, e.getKey(), overrides, List.copyOf(items), files, lang, sounds, filtered, dead,
                p != null && p.intended(), p != null && p.againstMarking(), p == null ? -1 : p.markingIndex()));
        }
        // not intended first, then the biggest, then by position (top first)
        list.sort(Comparator.comparing(Group::intended).thenComparing(Comparator.comparingInt((Group g) -> g.total() + g.dead()).reversed())
            .thenComparingInt(g -> pos.getOrDefault(g.counterpart(), Integer.MAX_VALUE)));
        return List.copyOf(list);
    }

    public ScanResult result() {
        return result;
    }

    public String packId() {
        return packId;
    }

    @Nullable
    public PackSummary summary() {
        return result.summary(packId);
    }

    /** Packs this pack overrides (pairwise), not intended first, biggest first. */
    public List<Group> overrides() {
        return overrides;
    }

    /** Packs whose file reaches the game instead of this pack's, not intended first, biggest first. */
    public List<Group> overriddenBy() {
        return overriddenBy;
    }

    public List<SoundReplacement> replacedSounds() {
        return replacedSounds;
    }

    public List<FilteredFile> hides() {
        return hides;
    }

    public List<ModGroup> modGroups() {
        return modGroups;
    }

    public List<FileConflict> harmless() {
        return harmless;
    }

    /** Pairs of this pack that lie against a marking (the marking says the other way round). */
    public List<PairConflict> againstMarking() {
        List<PairConflict> list = new ArrayList<>();
        for (PairConflict p : result.pairs()) {
            if (p.againstMarking() && (p.upper().equals(packId) || p.lower().equals(packId))) list.add(p);
        }
        return list;
    }

    /** Whether "mark all" has something to do: a pair with this pack that is not intended yet. */
    public boolean hasOpenPairs() {
        for (PairConflict p : result.pairs()) {
            if (!p.intended() && (p.upper().equals(packId) || p.lower().equals(packId))) return true;
        }
        return false;
    }
}
