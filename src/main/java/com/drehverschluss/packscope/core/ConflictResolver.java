package com.drehverschluss.packscope.core;

import com.drehverschluss.packscope.core.ScanResult.FileConflict;
import com.drehverschluss.packscope.core.ScanResult.FilteredFile;
import com.drehverschluss.packscope.core.ScanResult.LangConflict;
import com.drehverschluss.packscope.core.ScanResult.LangLoser;
import com.drehverschluss.packscope.core.ScanResult.ModOverride;
import com.drehverschluss.packscope.core.ScanResult.OverrideType;
import com.drehverschluss.packscope.core.ScanResult.PackSummary;
import com.drehverschluss.packscope.core.ScanResult.SoundReplacement;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides for every file, language key and sound event which pack wins (Konzept 3 + 4). Pure computation on the
 * indexes; only language files and sounds.json found in more than one pack are read. Cheap to rerun for a new order.
 */
public final class ConflictResolver {
    private final List<PackRef> packs;
    private final List<PackIndex> indexes;
    private final PackscopeSettings settings;
    private final Locations.Ignored ignored;
    private final Stats[] stats;
    private final Map<String, List<Integer>> where = new HashMap<>();
    private final List<Integer> filterPacks = new ArrayList<>();

    private final List<FileConflict> fileConflicts = new ArrayList<>();
    private final List<LangConflict> langConflicts = new ArrayList<>();
    private final List<SoundReplacement> soundReplacements = new ArrayList<>();
    private final List<FilteredFile> filtered = new ArrayList<>();
    private final List<ModOverride> modOverrides = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final IndexCache cache;
    private ContentSource content;

    private ConflictResolver(PackOrder order, List<PackIndex> indexes, PackscopeSettings settings, IndexCache cache) {
        this.packs = order.packs();
        this.indexes = indexes;
        this.settings = settings;
        this.cache = cache;
        this.ignored = new Locations.Ignored(settings.ignoredPaths());
        this.stats = new Stats[packs.size()];
        for (int i = 0; i < stats.length; i++) stats[i] = new Stats();
    }

    /**
     * @param indexes one index per pack of {@code order}, same order
     * @param cache   keeps parsed language/sounds files between evaluations
     */
    public static ScanResult resolve(PackOrder order, List<PackIndex> indexes, PackscopeSettings settings, IndexCache cache) {
        if (indexes.size() != order.packs().size()) throw new IllegalArgumentException("one index per pack expected");
        return new ConflictResolver(order, indexes, settings, cache).run(order);
    }

    private ScanResult run(PackOrder order) {
        long start = System.nanoTime();
        for (int i = 0; i < packs.size(); i++) {
            PackIndex idx = indexes.get(i);
            for (String w : idx.warnings()) warnings.add(packs.get(i).id() + ": " + w);
            if (idx.filter() != null) filterPacks.add(i);
            for (String loc : idx.files().keySet()) {
                if (!ignored.isEmpty() && ignored.matches(loc)) continue;
                where.computeIfAbsent(loc, k -> new ArrayList<>(2)).add(i);
            }
        }
        List<String> locations = new ArrayList<>(where.keySet());
        locations.sort(null);
        try (ContentSource source = new ContentSource(packs, indexes)) {
            this.content = source;
            for (String loc : locations) {
                List<Integer> cand = visible(loc, true);
                if (cand.isEmpty()) continue;
                switch (FileKind.of(loc)) {
                    case WHOLE -> whole(loc, FileKind.WHOLE, cand, null);
                    case BLOCKSTATE -> whole(loc, FileKind.BLOCKSTATE, cand, "merged per block state in game");
                    case MCMETA -> mcmeta(loc, cand);
                    case LANG -> lang(loc, cand);
                    case SOUNDS -> sounds(loc, cand);
                    case MERGED -> merged(cand);
                }
            }
        }
        List<PackSummary> summaries = new ArrayList<>();
        Map<String, PackIndex> byId = new LinkedHashMap<>();
        for (int i = 0; i < packs.size(); i++) {
            summaries.add(stats[i].summary(packs.get(i), indexes.get(i)));
            byId.put(packs.get(i).id(), indexes.get(i));
        }
        return new ScanResult(Instant.now(), order, summaries, fileConflicts, langConflicts, soundReplacements, filtered,
            modOverrides, warnings, byId, 0, System.nanoTime() - start);
    }

    // ------------------------------------------------------------------------------------------------ filters

    /**
     * Packs (top first) that have {@code loc} and are not hidden by the filter of a higher pack. With {@code record} the
     * hidden copies are counted and reported.
     */
    private List<Integer> visible(String loc, boolean record) {
        List<Integer> cand = where.getOrDefault(loc, List.of());
        if (cand.isEmpty() || filterPacks.isEmpty()) return cand;
        int lowest = cand.get(cand.size() - 1);
        for (int f : filterPacks) {
            if (f >= lowest) break;
            if (!indexes.get(f).filter().hides(loc)) continue;
            List<Integer> kept = new ArrayList<>();
            List<String> hidden = new ArrayList<>();
            for (int c : cand) {
                if (c <= f) {
                    kept.add(c);
                } else {
                    hidden.add(packs.get(c).id());
                    if (record && rp(c)) {
                        stats[c].hiddenByFilter++;
                        stats[c].considered++;
                    }
                }
            }
            if (record) {
                filtered.add(new FilteredFile(loc, packs.get(f).id(), hidden));
                if (rp(f)) stats[f].hidesOthers++;
            }
            return kept;
        }
        return cand;
    }

    // ------------------------------------------------------------------------------------------------ whole files

    private void whole(String loc, FileKind kind, List<Integer> cand, String note) {
        int w = cand.get(0);
        List<String> losers = new ArrayList<>();
        List<String> harmless = new ArrayList<>();
        Set<Integer> lost = new HashSet<>();
        for (int k = 1; k < cand.size(); k++) {
            int c = cand.get(k);
            if (rp(w) && rp(c)) {
                if (identical(w, c, loc)) {
                    harmless.add(packs.get(c).id());
                    stats[c].harmless++;
                } else {
                    losers.add(packs.get(c).id());
                    lose(c, w);
                    lost.add(c);
                }
            } else if (!rp(w) && rp(c)) {
                stats[c].lostToMods++;
                stats[c].considered++;
                modOverride(c, loc, w, OverrideType.FILE, true);
            }
        }
        if (rp(w)) win(w);
        overrides(cand, lost);
        recordModOverrides(loc, cand, OverrideType.FILE);
        if (rp(w) && (!losers.isEmpty() || !harmless.isEmpty())) {
            fileConflicts.add(new FileConflict(loc, kind, packs.get(w).id(), losers, harmless, List.of(), note));
        }
    }

    // ------------------------------------------------------------------------------------------------ .mcmeta

    private void mcmeta(String loc, List<Integer> cand) {
        String resource = loc.substring(0, loc.length() - ".mcmeta".length());
        List<Integer> resourceCand = visible(resource, false);
        if (resourceCand.isEmpty()) {
            whole(loc, FileKind.MCMETA, cand, "no matching resource");
            return;
        }
        int tw = resourceCand.get(0);
        int eff = -1;
        for (int c : cand) {
            if (c <= tw) {
                eff = c;
                break;
            }
        }
        List<String> losers = new ArrayList<>();
        List<String> harmless = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        Set<Integer> lost = new HashSet<>();
        for (int c : cand) {
            if (c == eff) continue;
            if (eff >= 0 && c <= tw) {
                if (rp(eff) && rp(c)) {
                    if (identical(eff, c, loc)) {
                        harmless.add(packs.get(c).id());
                        stats[c].harmless++;
                    } else {
                        losers.add(packs.get(c).id());
                        lose(c, eff);
                        lost.add(c);
                    }
                } else if (!rp(eff) && rp(c)) {
                    stats[c].lostToMods++;
                    stats[c].considered++;
                    modOverride(c, loc, eff, OverrideType.FILE, true);
                }
            } else if (rp(c)) {
                // below the pack whose resource wins: this .mcmeta never applies
                dead.add(packs.get(c).id());
                stats[c].dead++;
                stats[c].considered++;
            }
        }
        if (eff >= 0 && rp(eff)) win(eff);
        overrides(cand, lost);
        recordModOverrides(loc, cand, OverrideType.FILE);
        int shownWinner = eff >= 0 ? eff : tw;
        if (rp(shownWinner) && (!losers.isEmpty() || !harmless.isEmpty() || !dead.isEmpty())) {
            String note = dead.isEmpty() ? null : "resource from " + packs.get(tw).id() + (eff < 0 ? ", no .mcmeta applies" : "");
            fileConflicts.add(new FileConflict(loc, FileKind.MCMETA, packs.get(shownWinner).id(), losers, harmless, dead, note));
        } else if (!rp(shownWinner) && !dead.isEmpty()) {
            fileConflicts.add(new FileConflict(loc, FileKind.MCMETA, packs.get(shownWinner).id(), List.of(), List.of(), dead,
                "resource from " + packs.get(tw).id()));
        }
    }

    // ------------------------------------------------------------------------------------------------ language files

    /**
     * Resource pack against resource pack: per key, with texts. Against mod/vanilla files only the number of shared
     * keys per file pair (info, cached), so packs with hundreds of language files stay cheap.
     */
    private void lang(String loc, List<Integer> cand) {
        List<Integer> rps = new ArrayList<>();
        List<Integer> lookups = new ArrayList<>();
        for (int c : cand) (rp(c) ? rps : lookups).add(c);
        if (rps.isEmpty()) return;
        Map<Integer, Map<String, String>> data = new LinkedHashMap<>();
        if (rps.size() >= 2) {
            for (int r : rps) {
                try {
                    Map<String, String> m = rpLang(r, loc, true);
                    if (m != null) data.put(r, m);
                } catch (IOException | RuntimeException e) {
                    warnings.add(packs.get(r).id() + ": broken language file " + loc + " (" + e.getMessage() + "), rated as whole file");
                    whole(loc, FileKind.LANG, cand, "broken JSON, rated as whole file");
                    return;
                }
            }
            langKeys(loc, data);
        } else {
            win(rps.get(0));
        }
        for (int r : rps) {
            for (int l : lookups) {
                int shared = langOverlap(r, l, loc, data.get(r));
                if (shared <= 0) continue;
                if (l > r) {
                    modOverride(r, loc, l, OverrideType.LANG_KEY, false, shared);
                } else {
                    stats[r].lostToMods += shared;
                    stats[r].considered += shared;
                    modOverride(r, loc, l, OverrideType.LANG_KEY, true, shared);
                }
            }
        }
    }

    /** Per-key resolution between resource packs. */
    private void langKeys(String loc, Map<Integer, Map<String, String>> data) {
        String language = FileKind.language(loc);
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, String> m : data.values()) keys.addAll(m.keySet());
        for (String key : keys) {
            List<Integer> owners = new ArrayList<>();
            for (Map.Entry<Integer, Map<String, String>> e : data.entrySet()) if (e.getValue().containsKey(key)) owners.add(e.getKey());
            int w = owners.get(0);
            String wText = data.get(w).get(key);
            List<LangLoser> losers = new ArrayList<>();
            Set<Integer> lost = new HashSet<>();
            for (int k = 1; k < owners.size(); k++) {
                int o = owners.get(k);
                String text = data.get(o).get(key);
                if (settings.ignoreIdentical() && wText.equals(text)) {
                    stats[o].harmless++;
                } else {
                    losers.add(new LangLoser(packs.get(o).id(), text));
                    lose(o, w);
                    lost.add(o);
                }
            }
            win(w);
            overrides(owners, lost);
            if (!losers.isEmpty()) langConflicts.add(new LangConflict(loc, language, key, packs.get(w).id(), wText, losers));
        }
    }

    /** Parsed language file of resource pack {@code r}; kept in the cache if {@code keep}. */
    private Map<String, String> rpLang(int r, String loc, boolean keep) throws IOException {
        String key = fileKey(r, loc);
        Map<String, String> m = cache.langFiles.get(key);
        if (m != null) return m;
        byte[] bytes = content.read(r, loc);
        if (bytes == null) {
            warnings.add(packs.get(r).id() + ": cannot read " + loc);
            return null;
        }
        m = JsonMergers.lang(bytes);
        if (keep) cache.langFiles.put(key, m);
        return m;
    }

    /** Keys the language file of resource pack {@code r} shares with the same file of mod/vanilla pack {@code l}. */
    private int langOverlap(int r, int l, String loc, Map<String, String> rData) {
        String key = fileKey(r, loc) + "|" + packs.get(l).id();
        Integer cached = cache.langOverlap.get(key);
        if (cached != null) return cached;
        int shared = 0;
        try {
            Map<String, String> mine = rData != null ? rData : rpLang(r, loc, false);
            byte[] bytes = content.read(l, loc);
            if (mine != null && bytes != null) {
                for (String k : JsonMergers.lang(bytes).keySet()) if (mine.containsKey(k)) shared++;
            }
        } catch (IOException | RuntimeException e) {
            warnings.add(packs.get(l).id() + ": cannot read language file " + loc + " (" + e.getMessage() + ")");
        }
        cache.langOverlap.put(key, shared);
        return shared;
    }

    /** Cache key of a file: pack id, location and content (size + CRC); lookup packs only id and location. */
    private String fileKey(int i, String loc) {
        FileEntry e = indexes.get(i).files().get(loc);
        String base = packs.get(i).id() + "|" + loc;
        return e != null && e.known() ? base + "|" + e.size() + "|" + e.crc() : base;
    }

    // ------------------------------------------------------------------------------------------------ sounds.json

    private void sounds(String loc, List<Integer> cand) {
        if (cand.size() == 1) {
            if (rp(cand.get(0))) win(cand.get(0));
            return;
        }
        Map<Integer, Map<String, JsonMergers.SoundEvent>> data = new LinkedHashMap<>();
        for (int c : cand) {
            try {
                String key = fileKey(c, loc);
                Map<String, JsonMergers.SoundEvent> parsed = cache.soundFiles.get(key);
                if (parsed == null) {
                    byte[] bytes = content.read(c, loc);
                    if (bytes == null) {
                        warnings.add(packs.get(c).id() + ": cannot read " + loc);
                        continue;
                    }
                    parsed = JsonMergers.sounds(bytes);
                    cache.soundFiles.put(key, parsed);
                }
                data.put(c, parsed);
            } catch (IOException | RuntimeException e) {
                warnings.add(packs.get(c).id() + ": broken sounds.json " + loc + " (" + e.getMessage() + "), rated as merged file");
                merged(cand);
                return;
            }
        }
        String ns = Locations.namespace(loc);
        Set<String> events = new LinkedHashSet<>();
        for (Map<String, JsonMergers.SoundEvent> m : data.values()) events.addAll(m.keySet());
        for (String event : events) {
            List<Integer> owners = new ArrayList<>();
            for (Map.Entry<Integer, Map<String, JsonMergers.SoundEvent>> e : data.entrySet()) if (e.getValue().containsKey(event)) owners.add(e.getKey());
            String eventId = ns + ":" + event;
            // vanilla applies bottom to top; "replace" drops everything below, so the top-most replacing owner wins
            int killer = -1;
            List<String> replaced = new ArrayList<>();
            List<String> lostSounds = new ArrayList<>();
            for (int o : owners) {
                if (killer < 0) {
                    if (rp(o)) win(o);
                    if (data.get(o).get(event).replace()) killer = o;
                    continue;
                }
                if (rp(killer) && rp(o)) {
                    replaced.add(packs.get(o).id());
                    lostSounds.addAll(data.get(o).get(event).sounds());
                    stats[o].lostSoundEvents++;
                    lose(o, killer);
                    stats[killer].winsAgainst.merge(packs.get(o).id(), 1, Integer::sum);
                } else if (rp(killer)) {
                    modOverride(killer, eventId, o, OverrideType.SOUND_EVENT, false);
                } else if (rp(o)) {
                    stats[o].lostToMods++;
                    stats[o].considered++;
                    modOverride(o, eventId, killer, OverrideType.SOUND_EVENT, true);
                }
            }
            if (!replaced.isEmpty()) {
                stats[killer].replacedSoundEvents++;
                stats[killer].wins++;
                soundReplacements.add(new SoundReplacement(eventId, packs.get(killer).id(), replaced, lostSounds));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ merged files

    private void merged(List<Integer> cand) {
        for (int c : cand) if (rp(c)) win(c);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private boolean rp(int i) {
        return packs.get(i).isResourcePack();
    }

    private boolean identical(int a, int b, String loc) {
        if (!settings.ignoreIdentical()) return false;
        FileEntry ea = indexes.get(a).files().get(loc);
        FileEntry eb = indexes.get(b).files().get(loc);
        return ea != null && ea.sameContent(eb);
    }

    /** Pack {@code i} keeps an item (its copy is the one the game uses). */
    private void win(int i) {
        stats[i].considered++;
        stats[i].effective++;
    }

    /** Pack {@code loser} loses an item; {@code winner} is the pack whose copy the game uses. */
    private void lose(int loser, int winner) {
        stats[loser].losses++;
        stats[loser].considered++;
        stats[loser].losesTo.merge(packs.get(winner).id(), 1, Integer::sum);
    }

    /**
     * Pairwise like Mod Organizer 2: every resource pack overrides every lower resource pack that loses the item, even
     * if a third pack above both wins. Counts one win per item and pack.
     */
    private void overrides(List<Integer> ownersTopFirst, Set<Integer> lost) {
        if (lost.isEmpty()) return;
        for (int a = 0; a < ownersTopFirst.size(); a++) {
            int upper = ownersTopFirst.get(a);
            if (!rp(upper)) continue;
            boolean beat = false;
            for (int b = a + 1; b < ownersTopFirst.size(); b++) {
                int lower = ownersTopFirst.get(b);
                if (lost.contains(lower)) {
                    stats[upper].winsAgainst.merge(packs.get(lower).id(), 1, Integer::sum);
                    beat = true;
                }
            }
            if (beat) stats[upper].wins++;
        }
    }

    /** Info: every resource pack copy of {@code loc} above a mod/vanilla copy replaces it. */
    private void recordModOverrides(String loc, List<Integer> cand, OverrideType type) {
        for (int a = 0; a < cand.size(); a++) {
            int r = cand.get(a);
            if (!rp(r)) continue;
            for (int b = a + 1; b < cand.size(); b++) {
                if (!rp(cand.get(b))) modOverride(r, loc, cand.get(b), type, false);
            }
        }
    }

    private void modOverride(int pack, String location, int target, OverrideType type, boolean reversed) {
        modOverride(pack, location, target, type, reversed, 1);
    }

    private void modOverride(int pack, String location, int target, OverrideType type, boolean reversed, int count) {
        PackRef t = packs.get(target);
        modOverrides.add(new ModOverride(packs.get(pack).id(), location, t.id(), t.ownerKey(), t.ownerName(), type, reversed, count));
        if (!reversed) {
            stats[pack].modOverrides += count;
            stats[pack].modOverridesByOwner.merge(t.ownerKey(), count, Integer::sum);
        }
    }

    /** Mutable counters of one pack during the evaluation. */
    private static final class Stats {
        int considered;
        int effective;
        int wins;
        int losses;
        int dead;
        int harmless;
        int hiddenByFilter;
        int hidesOthers;
        int replacedSoundEvents;
        int lostSoundEvents;
        int lostToMods;
        int modOverrides;
        final Map<String, Integer> winsAgainst = new LinkedHashMap<>();
        final Map<String, Integer> losesTo = new LinkedHashMap<>();
        final Map<String, Integer> modOverridesByOwner = new TreeMap<>();

        PackSummary summary(PackRef ref, PackIndex index) {
            PackStatus status = PackStatus.NO_CONFLICT;
            if (ref.isResourcePack()) {
                if (considered > 0 && effective == 0) status = PackStatus.COMPLETELY_COVERED;
                else if (replacedSoundEvents > 0) status = PackStatus.REPLACES_SOUNDS;
                else if (wins > 0 && losses > 0) status = PackStatus.WINS_AND_LOSES;
                else if (wins > 0) status = PackStatus.WINS;
                else if (losses > 0) status = PackStatus.LOSES;
            }
            return new PackSummary(ref.id(), ref.title(), ref.role(), index.kind(), index.approximate(), index.files().size(),
                status, wins, losses, dead, harmless, hiddenByFilter, hidesOthers, replacedSoundEvents, lostSoundEvents,
                lostToMods, modOverrides, Collections.unmodifiableMap(winsAgainst), Collections.unmodifiableMap(losesTo),
                Collections.unmodifiableMap(modOverridesByOwner), ref.modId(), ref.parentId());
        }
    }
}
