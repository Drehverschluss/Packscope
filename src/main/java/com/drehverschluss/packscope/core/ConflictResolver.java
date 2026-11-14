package com.drehverschluss.packscope.core;

import com.drehverschluss.packscope.core.ScanResult.FileConflict;
import com.drehverschluss.packscope.core.ScanResult.FilteredFile;
import com.drehverschluss.packscope.core.ScanResult.LangConflict;
import com.drehverschluss.packscope.core.ScanResult.LangLoser;
import com.drehverschluss.packscope.core.ScanResult.MarkingState;
import com.drehverschluss.packscope.core.ScanResult.MarkingStatus;
import com.drehverschluss.packscope.core.ScanResult.ModOverride;
import com.drehverschluss.packscope.core.ScanResult.OverrideType;
import com.drehverschluss.packscope.core.ScanResult.PackSummary;
import com.drehverschluss.packscope.core.ScanResult.PairConflict;
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
 * Decides for every file, language key and sound event which pack wins (Konzept 3 + 4), pairwise like Mod Organizer 2,
 * and applies the intended markings (Konzept 4.1). Pure computation on the indexes; only language files and sounds.json
 * found in more than one pack are read. Cheap to rerun for a new order or new markings.
 */
public final class ConflictResolver {
    private static final int FILE = 0;
    private static final int LANG = 1;
    private static final int SOUND = 2;
    private static final int FILTER = 3;

    private final List<PackRef> packs;
    private final List<PackIndex> indexes;
    private final PackOrder order;
    private final PackscopeSettings settings;
    private final IndexCache cache;
    private final IntendedSet intended;
    private final Locations.Ignored ignored;
    private final Stats[] stats;
    private final Map<String, List<Integer>> where = new HashMap<>();
    private final List<Integer> filterPacks = new ArrayList<>();
    private final Map<Long, int[]> pairCounts = new LinkedHashMap<>();
    private final Map<Long, Integer> coverMemo = new HashMap<>();
    private final Map<Long, Integer> reverseMemo = new HashMap<>();

    private final List<FileConflict> fileConflicts = new ArrayList<>();
    private final List<LangConflict> langConflicts = new ArrayList<>();
    private final List<SoundReplacement> soundReplacements = new ArrayList<>();
    private final List<FilteredFile> filtered = new ArrayList<>();
    private final List<ModOverride> modOverrides = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private ContentSource content;

    private ConflictResolver(PackOrder order, List<PackIndex> indexes, PackscopeSettings settings, IndexCache cache, IntendedSet intended) {
        this.order = order;
        this.packs = order.packs();
        this.indexes = indexes;
        this.settings = settings;
        this.cache = cache;
        this.intended = intended == null ? IntendedSet.EMPTY : intended;
        this.ignored = new Locations.Ignored(settings.ignoredPaths());
        this.stats = new Stats[packs.size()];
        for (int i = 0; i < stats.length; i++) stats[i] = new Stats();
    }

    /**
     * @param indexes  one index per pack of {@code order}, same order
     * @param cache    keeps parsed language/sounds files between evaluations
     * @param intended snapshot of the intended markings
     */
    public static ScanResult resolve(PackOrder order, List<PackIndex> indexes, PackscopeSettings settings, IndexCache cache,
                                     IntendedSet intended) {
        if (indexes.size() != order.packs().size()) throw new IllegalArgumentException("one index per pack expected");
        return new ConflictResolver(order, indexes, settings, cache, intended).run();
    }

    public static ScanResult resolve(PackOrder order, List<PackIndex> indexes, PackscopeSettings settings, IndexCache cache) {
        return resolve(order, indexes, settings, cache, IntendedSet.EMPTY);
    }

    private ScanResult run() {
        long start = System.nanoTime();
        warnings.addAll(intended.warnings());
        for (int i = 0; i < packs.size(); i++) {
            PackIndex idx = indexes.get(i);
            for (String w : idx.warnings()) warnings.add(packs.get(i).id() + ": " + w);
            if (idx.filter() != null) filterPacks.add(i);
            for (String loc : idx.files().keySet()) {
                if (!ignored.isEmpty() && ignored.matches(loc)) continue;
                if (FileKind.of(loc) == FileKind.LANG && !order.countsLanguage(FileKind.language(loc))) continue;
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
                    case MERGED -> merged(loc, cand);
                }
            }
        }
        List<PairConflict> pairs = pairs();
        List<MarkingState> markingStates = markingStates();
        List<PackSummary> summaries = new ArrayList<>();
        Map<String, PackIndex> byId = new LinkedHashMap<>();
        for (int i = 0; i < packs.size(); i++) {
            summaries.add(stats[i].summary(packs.get(i), indexes.get(i)));
            byId.put(packs.get(i).id(), indexes.get(i));
        }
        return new ScanResult(Instant.now(), order, summaries, fileConflicts, langConflicts, soundReplacements, filtered,
            modOverrides, pairs, markingStates, intended.version(), warnings, byId, 0, System.nanoTime() - start);
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
            boolean allIntended = true;
            boolean anyRp = false;
            for (int c : cand) {
                if (c <= f) {
                    kept.add(c);
                    continue;
                }
                hidden.add(packs.get(c).id());
                if (record && rp(c)) {
                    stats[c].hiddenByFilter++;
                    stats[c].items++;
                    if (rp(f)) {
                        anyRp = true;
                        pair(f, c)[FILTER]++;
                        checkReversed(f, c);
                        if (covering(f, c) < 0) allIntended = false;
                    }
                }
            }
            if (record) {
                filtered.add(new FilteredFile(loc, packs.get(f).id(), hidden, anyRp && allIntended));
                if (rp(f)) stats[f].hidesOthers++;
            }
            return kept;
        }
        return cand;
    }

    // ------------------------------------------------------------------------------------------------ whole files

    private void whole(String loc, FileKind kind, List<Integer> cand, String note) {
        int w = cand.get(0);
        if (!rp(w)) {
            lostToMod(loc, cand, w, OverrideType.FILE);
            return;
        }
        List<Integer> owners = new ArrayList<>();
        Set<Integer> copies = new LinkedHashSet<>();
        Set<Integer> losers = new LinkedHashSet<>();
        for (int c : cand) {
            if (!rp(c)) continue;
            owners.add(c);
            if (c == w) continue;
            if (identical(w, c, loc)) copies.add(c);
            else losers.add(c);
        }
        boolean allIntended = settle(owners, w, copies, losers, FILE);
        recordModOverrides(loc, cand, OverrideType.FILE);
        if (!losers.isEmpty() || !copies.isEmpty()) {
            fileConflicts.add(new FileConflict(loc, kind, packs.get(w).id(), ids(losers), ids(copies), List.of(), note, allIntended));
        }
    }

    /** A mod/vanilla pack above resource packs wins: info only, the resource packs lose the item. */
    private void lostToMod(String loc, List<Integer> cand, int w, OverrideType type) {
        for (int c : cand) {
            if (!rp(c)) continue;
            stats[c].lostToMods++;
            stats[c].items++;
            modOverride(c, loc, w, type, true);
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
        List<Integer> dead = new ArrayList<>();
        for (int c : cand) {
            if (c > tw && rp(c)) {
                // below the pack whose resource wins: this .mcmeta never applies
                dead.add(c);
                stats[c].dead++;
                stats[c].items++;
            }
        }
        List<Integer> active = new ArrayList<>();
        for (int c : cand) if (c <= tw) active.add(c);
        boolean allIntended = true;
        List<Integer> losers = List.of();
        List<Integer> copies = List.of();
        if (eff >= 0 && !rp(eff)) {
            lostToMod(loc, active, eff, OverrideType.FILE);
        } else if (eff >= 0) {
            List<Integer> owners = new ArrayList<>();
            Set<Integer> c2 = new LinkedHashSet<>();
            Set<Integer> l2 = new LinkedHashSet<>();
            for (int c : active) {
                if (!rp(c)) continue;
                owners.add(c);
                if (c == eff) continue;
                if (identical(eff, c, loc)) c2.add(c);
                else l2.add(c);
            }
            allIntended = settle(owners, eff, c2, l2, FILE);
            losers = new ArrayList<>(l2);
            copies = new ArrayList<>(c2);
        }
        recordModOverrides(loc, cand, OverrideType.FILE);
        int shownWinner = eff >= 0 ? eff : tw;
        if (!losers.isEmpty() || !copies.isEmpty() || !dead.isEmpty()) {
            String note = dead.isEmpty() ? null : "resource from " + packs.get(tw).title() + (eff < 0 ? ", no .mcmeta applies" : "");
            boolean intendedFlag = losers.isEmpty() ? deadIntended(tw, dead) : allIntended && deadIntended(tw, dead);
            fileConflicts.add(new FileConflict(loc, FileKind.MCMETA, packs.get(shownWinner).id(), ids(losers), ids(copies), ids(dead),
                note, intendedFlag));
        }
    }

    private boolean deadIntended(int textureWinner, List<Integer> dead) {
        if (dead.isEmpty()) return true;
        if (!rp(textureWinner)) return false;
        for (int d : dead) if (covering(textureWinner, d) < 0) return false;
        return true;
    }

    // ------------------------------------------------------------------------------------------------ language files

    /**
     * Resource pack against resource pack: per key, with texts. Against mod/vanilla files only the number of shared
     * keys per file pair (info, cached), so packs with many language files stay cheap. Only counted languages get here.
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
            stats[rps.get(0)].items++;
            stats[rps.get(0)].effective++;
        }
        for (int r : rps) {
            for (int l : lookups) {
                int shared = langOverlap(r, l, loc, data.get(r));
                if (shared <= 0) continue;
                if (l > r) {
                    modOverride(r, loc, l, OverrideType.LANG_KEY, false, shared);
                } else {
                    stats[r].lostToMods += shared;
                    stats[r].items += shared;
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
            Set<Integer> copies = new LinkedHashSet<>();
            Set<Integer> losers = new LinkedHashSet<>();
            List<LangLoser> loserTexts = new ArrayList<>();
            for (int k = 1; k < owners.size(); k++) {
                int o = owners.get(k);
                String text = data.get(o).get(key);
                if (settings.ignoreIdentical() && wText.equals(text)) {
                    copies.add(o);
                } else {
                    losers.add(o);
                    loserTexts.add(new LangLoser(packs.get(o).id(), text));
                }
            }
            boolean allIntended = settle(owners, w, copies, losers, LANG);
            if (!losers.isEmpty()) langConflicts.add(new LangConflict(loc, language, key, packs.get(w).id(), wText, loserTexts, allIntended));
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
            if (rp(cand.get(0))) {
                stats[cand.get(0)].items++;
                stats[cand.get(0)].effective++;
            }
            return;
        }
        // a byte-identical copy of a higher resource pack's sounds.json adds nothing: count it as a copy, leave it out
        List<Integer> withoutCopies = new ArrayList<>();
        for (int c : cand) {
            int original = rp(c) ? higherIdentical(loc, cand, c) : -1;
            if (original < 0) {
                withoutCopies.add(c);
                continue;
            }
            stats[c].items++;
            stats[c].copies++;
            stats[c].copiesOf.merge(packs.get(original).id(), 1, Integer::sum);
        }
        if (withoutCopies.size() == 1) {
            if (rp(withoutCopies.get(0))) {
                stats[withoutCopies.get(0)].items++;
                stats[withoutCopies.get(0)].effective++;
            }
            return;
        }
        cand = withoutCopies;
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
                merged(loc, cand);
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
            boolean allIntended = true;
            for (int o : owners) {
                if (killer < 0) {
                    if (rp(o)) {
                        stats[o].items++;
                        stats[o].effective++;
                    }
                    if (data.get(o).get(event).replace()) killer = o;
                    continue;
                }
                if (rp(killer) && rp(o)) {
                    replaced.add(packs.get(o).id());
                    lostSounds.addAll(data.get(o).get(event).sounds());
                    stats[o].lostSoundEvents++;
                    stats[o].items++;
                    stats[o].losesTo.merge(packs.get(killer).id(), 1, Integer::sum);
                    stats[killer].winsAgainst.merge(packs.get(o).id(), 1, Integer::sum);
                    pair(killer, o)[SOUND]++;
                    checkReversed(killer, o);
                    if (covering(killer, o) >= 0) {
                        stats[o].intendedLosses++;
                    } else {
                        stats[o].losses++;
                        allIntended = false;
                    }
                } else if (rp(killer)) {
                    modOverride(killer, eventId, o, OverrideType.SOUND_EVENT, false);
                } else if (rp(o)) {
                    stats[o].lostToMods++;
                    stats[o].items++;
                    modOverride(o, eventId, killer, OverrideType.SOUND_EVENT, true);
                }
            }
            if (!replaced.isEmpty()) {
                if (allIntended) {
                    stats[killer].intendedSoundEvents++;
                    stats[killer].intendedWins++;
                } else {
                    stats[killer].replacedSoundEvents++;
                    stats[killer].wins++;
                }
                soundReplacements.add(new SoundReplacement(eventId, packs.get(killer).id(), replaced, lostSounds, allIntended));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ merged files

    /**
     * Merged files (atlases, fonts): every copy takes effect, except a byte-identical copy of a higher resource pack's
     * file, which adds nothing (Konzept 4, copies).
     */
    private void merged(String loc, List<Integer> cand) {
        for (int c : cand) {
            if (!rp(c)) continue;
            int original = higherIdentical(loc, cand, c);
            stats[c].items++;
            if (original >= 0) {
                stats[c].copies++;
                stats[c].copiesOf.merge(packs.get(original).id(), 1, Integer::sum);
            } else {
                stats[c].effective++;
            }
        }
    }

    /** The highest resource pack above {@code c} with a byte-identical copy of {@code loc}, or -1. */
    private int higherIdentical(String loc, List<Integer> cand, int c) {
        for (int h : cand) {
            if (h >= c) break;
            if (rp(h) && identical(h, c, loc)) return h;
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------ settling one item

    /**
     * Counts one contested item between resource packs ({@code owners} top first, all resource packs).
     * Pairwise like MO2: every owner overrides every lower owner that loses the item. Byte-identical copies of the
     * winning file count as that file: they create no pairs and no wins of their own (they have no effect, Konzept 4).
     * A loss is intended if every pack above that overrides it is covered by a marking. Returns true if every loss of
     * the item is intended.
     */
    private boolean settle(List<Integer> owners, int winner, Set<Integer> copies, Set<Integer> losers, int slot) {
        boolean allIntended = !losers.isEmpty();
        for (int pos = 0; pos < owners.size(); pos++) {
            int l = owners.get(pos);
            if (!losers.contains(l)) continue;
            boolean lossIntended = true;
            for (int up = 0; up < pos; up++) {
                int u = owners.get(up);
                if (copies.contains(u)) continue;
                pair(u, l)[slot]++;
                checkReversed(u, l);
                if (covering(u, l) < 0) lossIntended = false;
            }
            stats[l].items++;
            stats[l].losesTo.merge(packs.get(winner).id(), 1, Integer::sum);
            if (lossIntended) {
                stats[l].intendedLosses++;
            } else {
                stats[l].losses++;
                allIntended = false;
            }
        }
        for (int pos = 0; pos < owners.size(); pos++) {
            int u = owners.get(pos);
            if (copies.contains(u)) continue;
            boolean beatPlain = false;
            boolean beatIntended = false;
            for (int low = pos + 1; low < owners.size(); low++) {
                int l = owners.get(low);
                if (!losers.contains(l)) continue;
                stats[u].winsAgainst.merge(packs.get(l).id(), 1, Integer::sum);
                if (covering(u, l) >= 0) beatIntended = true;
                else beatPlain = true;
            }
            if (beatPlain) stats[u].wins++;
            else if (beatIntended) stats[u].intendedWins++;
        }
        for (int c : copies) {
            stats[c].copies++;
            stats[c].items++;
            stats[c].copiesOf.merge(packs.get(winner).id(), 1, Integer::sum);
        }
        stats[winner].items++;
        stats[winner].effective++;
        return allIntended;
    }

    // ------------------------------------------------------------------------------------------------ pairs and markings

    private long key(int upper, int lower) {
        return (long) upper * packs.size() + lower;
    }

    /** Counters of "upper overrides lower": files, language keys, sound events, filtered files. */
    private int[] pair(int upper, int lower) {
        return pairCounts.computeIfAbsent(key(upper, lower), k -> new int[4]);
    }

    /** Index of the marking covering "upper over lower", or -1. */
    private int covering(int upper, int lower) {
        return coverMemo.computeIfAbsent(key(upper, lower), k -> intended.covering(packs.get(upper).id(), packs.get(lower).id()));
    }

    /** Index of a marking that says "lower over upper", or -1. */
    private int reversing(int upper, int lower) {
        return reverseMemo.computeIfAbsent(key(upper, lower), k -> intended.reversing(packs.get(upper).id(), packs.get(lower).id()));
    }

    private void checkReversed(int upper, int lower) {
        if (reversing(upper, lower) >= 0) {
            stats[upper].againstMarking = true;
            stats[lower].againstMarking = true;
        }
    }

    private List<PairConflict> pairs() {
        List<PairConflict> out = new ArrayList<>();
        List<Long> keys = new ArrayList<>(pairCounts.keySet());
        keys.sort(null);
        for (long k : keys) {
            int u = (int) (k / packs.size());
            int l = (int) (k % packs.size());
            int[] c = pairCounts.get(k);
            int m = covering(u, l);
            out.add(new PairConflict(packs.get(u).id(), packs.get(l).id(), c[FILE], c[LANG], c[SOUND], c[FILTER], m >= 0, m,
                reversing(u, l) >= 0));
        }
        return out;
    }

    private List<MarkingState> markingStates() {
        List<MarkingState> out = new ArrayList<>();
        List<IntendedMarking> list = intended.markings();
        for (int i = 0; i < list.size(); i++) {
            IntendedMarking m = list.get(i);
            List<Integer> winners = new ArrayList<>();
            List<Integer> losers = new ArrayList<>();
            for (int p = 0; p < packs.size(); p++) {
                if (!rp(p)) continue;
                if (PackNames.matches(m.winner(), packs.get(p).id())) winners.add(p);
                if (PackNames.matches(m.loser(), packs.get(p).id())) losers.add(p);
            }
            if (winners.isEmpty() || losers.isEmpty()) {
                out.add(new MarkingState(i, m, MarkingStatus.PACK_NOT_ACTIVE, winners.isEmpty() ? null : packs.get(winners.get(0)).id(),
                    losers.isEmpty() ? null : packs.get(losers.get(0)).id(), 0));
                continue;
            }
            MarkingState state = null;
            for (int w : winners) {
                for (int l : losers) {
                    if (w == l) continue;
                    int[] forward = pairCounts.get(key(w, l));
                    if (forward != null) {
                        state = new MarkingState(i, m, MarkingStatus.WORKS, packs.get(w).id(), packs.get(l).id(), sum(forward));
                    }
                }
            }
            if (state == null) {
                for (int w : winners) {
                    for (int l : losers) {
                        int[] backward = w == l ? null : pairCounts.get(key(l, w));
                        if (backward != null) {
                            state = new MarkingState(i, m, MarkingStatus.REVERSED, packs.get(w).id(), packs.get(l).id(), sum(backward));
                        }
                    }
                }
            }
            if (state == null) {
                state = new MarkingState(i, m, MarkingStatus.NO_CONFLICT, packs.get(winners.get(0)).id(), packs.get(losers.get(0)).id(), 0);
            }
            out.add(state);
        }
        return out;
    }

    private static int sum(int[] c) {
        int s = 0;
        for (int v : c) s += v;
        return s;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private boolean rp(int i) {
        return packs.get(i).isResourcePack();
    }

    private List<String> ids(java.util.Collection<Integer> indexes) {
        List<String> out = new ArrayList<>(indexes.size());
        for (int i : indexes) out.add(packs.get(i).id());
        return out;
    }

    private boolean identical(int a, int b, String loc) {
        if (!settings.ignoreIdentical()) return false;
        FileEntry ea = indexes.get(a).files().get(loc);
        FileEntry eb = indexes.get(b).files().get(loc);
        return ea != null && ea.sameContent(eb);
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
        /** Items of this pack that take part in the evaluation (incl. copies, hidden, dead). */
        int items;
        /** Items whose copy the game uses. */
        int effective;
        int wins;
        int losses;
        int intendedWins;
        int intendedLosses;
        int dead;
        int copies;
        int hiddenByFilter;
        int hidesOthers;
        int replacedSoundEvents;
        int intendedSoundEvents;
        int lostSoundEvents;
        int lostToMods;
        int modOverrides;
        boolean againstMarking;
        final Map<String, Integer> winsAgainst = new LinkedHashMap<>();
        final Map<String, Integer> losesTo = new LinkedHashMap<>();
        final Map<String, Integer> copiesOf = new LinkedHashMap<>();
        final Map<String, Integer> modOverridesByOwner = new TreeMap<>();

        PackSummary summary(PackRef ref, PackIndex index) {
            PackStatus status = PackStatus.NO_CONFLICT;
            boolean covered = ref.isResourcePack() && items > 0 && effective == 0;
            if (ref.isResourcePack()) {
                if (covered) status = PackStatus.COMPLETELY_COVERED;
                else if (againstMarking) status = PackStatus.AGAINST_MARKING;
                else if (replacedSoundEvents > 0) status = PackStatus.REPLACES_SOUNDS;
                else if (wins > 0 && losses > 0) status = PackStatus.WINS_AND_LOSES;
                else if (wins > 0) status = PackStatus.WINS;
                else if (losses > 0) status = PackStatus.LOSES;
                else if (intendedWins > 0 || intendedLosses > 0 || intendedSoundEvents > 0) status = PackStatus.INTENDED_ONLY;
            }
            boolean onlyCopies = covered && copies == items;
            return new PackSummary(ref.id(), ref.title(), ref.role(), index.kind(), index.approximate(), ref.hidden(),
                index.files().size(), status, wins, losses, intendedWins, intendedLosses, dead, copies, hiddenByFilter, hidesOthers,
                replacedSoundEvents, intendedSoundEvents, lostSoundEvents, lostToMods, modOverrides, againstMarking, onlyCopies,
                Collections.unmodifiableMap(winsAgainst), Collections.unmodifiableMap(losesTo), Collections.unmodifiableMap(copiesOf),
                Collections.unmodifiableMap(modOverridesByOwner), ref.modId(), ref.parentId());
        }
    }
}
