package com.drehverschluss.packscope.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Result of one evaluation of a {@link PackOrder} with a snapshot of the intended markings. Pack ids refer to
 * {@link PackRef#id()}.
 *
 * @param packs             one summary per pack of the order, same order (top first)
 * @param fileConflicts     whole-file conflicts between resource packs (incl. blockstates and .mcmeta), sorted by location
 * @param langConflicts     language keys defined by more than one resource pack with different text (counted languages only)
 * @param soundReplacements sound events a resource pack replaces ({@code "replace": true}) in lower resource packs
 * @param filtered          files hidden by a {@code filter} in pack.mcmeta
 * @param modOverrides      resource pack files/keys/events that replace (or lose to) mod and vanilla resources
 * @param pairs             every resource pack pair where the upper pack overrides the lower one (pairwise), with numbers
 * @param markings          every intended marking with its state for this order
 * @param intendedVersion   version of the {@link IntendedSet} used
 * @param indexes           index per pack id (for later use, e.g. the detail screen)
 */
public record ScanResult(Instant created, PackOrder order, List<PackSummary> packs, List<FileConflict> fileConflicts,
                         List<LangConflict> langConflicts, List<SoundReplacement> soundReplacements, List<FilteredFile> filtered,
                         List<ModOverride> modOverrides, List<PairConflict> pairs, List<MarkingState> markings,
                         long intendedVersion, List<String> warnings, Map<String, PackIndex> indexes,
                         long scanNanos, long resolveNanos) {

    @Nullable
    public PackSummary summary(String packId) {
        for (PackSummary s : packs) if (s.id().equals(packId)) return s;
        return null;
    }

    /** Pairwise numbers of {@code upper} over {@code lower}, or null if {@code upper} does not override {@code lower}. */
    @Nullable
    public PairConflict pair(String upper, String lower) {
        for (PairConflict p : pairs) if (p.upper().equals(upper) && p.lower().equals(lower)) return p;
        return null;
    }

    /** Number of real conflicts: files with a losing resource pack, language keys, replaced sound events (incl. intended). */
    public int conflictCount() {
        int files = (int) fileConflicts.stream().filter(c -> !c.losers().isEmpty() || !c.dead().isEmpty()).count();
        return files + langConflicts.size() + soundReplacements.size();
    }

    /** The part of {@link #conflictCount()} that is fully covered by intended markings. */
    public int intendedCount() {
        int files = (int) fileConflicts.stream().filter(c -> (!c.losers().isEmpty() || !c.dead().isEmpty()) && c.intended()).count();
        return files + (int) langConflicts.stream().filter(LangConflict::intended).count()
            + (int) soundReplacements.stream().filter(SoundReplacement::intended).count();
    }

    public List<PackSummary> coveredPacks() {
        return packs.stream().filter(p -> p.status() == PackStatus.COMPLETELY_COVERED).toList();
    }

    public ScanResult withTimes(long scan, long resolve) {
        return new ScanResult(created, order, packs, fileConflicts, langConflicts, soundReplacements, filtered, modOverrides,
            pairs, markings, intendedVersion, warnings, indexes, scan, resolve);
    }

    /**
     * Numbers per pack. Counted per file, language key or sound event ("item").
     *
     * @param status              only meaningful for resource packs ({@link PackStatus#NO_CONFLICT} for mod/vanilla)
     * @param hidden              selected, but not shown in the pack screen
     * @param wins                items where this pack overrides at least one lower resource pack (not intended)
     * @param losses              items overridden by a higher resource pack (incl. replaced sound events, not intended)
     * @param intendedWins        items it overrides only in intended pairs
     * @param intendedLosses      items it loses only in intended pairs
     * @param dead                .mcmeta files that never apply because a higher pack replaced the resource
     * @param copies              items identical to the copy the game uses (only with ignoreIdentical)
     * @param hiddenByFilter      files hidden by the filter of a higher pack
     * @param hidesOthers         files of lower packs this pack hides with its filter
     * @param replacedSoundEvents sound events of lower resource packs this pack replaces (not intended)
     * @param intendedSoundEvents sound events it replaces only in intended pairs
     * @param lostSoundEvents     own sound events replaced by a higher resource pack
     * @param lostToMods          items overridden by mod/vanilla resources above this pack
     * @param modOverrides        items that replace mod/vanilla resources (info)
     * @param againstMarking      an intended pair with this pack lies the other way round
     * @param onlyCopies          completely covered and every item is a copy (hint "only copies of ...")
     * @param winsAgainst         pack id -> items this pack overrides in it (pairwise)
     * @param losesTo             pack id -> items lost to it (the pack whose copy the game uses)
     * @param copiesOf            pack id -> items that are copies of its file
     * @param modOverridesByOwner mod id ("minecraft" for vanilla) -> items replaced
     * @param injected            put into the resource stack by a mod (not in the pack screen, Phase 5C)
     * @param injectedBy          name of that mod, if known
     */
    public record PackSummary(String id, String title, PackRole role, ScanKind kind, boolean approximate, boolean hidden,
                              int fileCount, PackStatus status, int wins, int losses, int intendedWins, int intendedLosses,
                              int dead, int copies, int hiddenByFilter, int hidesOthers, int replacedSoundEvents,
                              int intendedSoundEvents, int lostSoundEvents, int lostToMods, int modOverrides,
                              boolean againstMarking, boolean onlyCopies, Map<String, Integer> winsAgainst,
                              Map<String, Integer> losesTo, Map<String, Integer> copiesOf,
                              Map<String, Integer> modOverridesByOwner, @Nullable String modId, @Nullable String parentId,
                              boolean injected, @Nullable String injectedBy) {
        /** Old name of {@link #copies()}. */
        public int harmless() {
            return copies;
        }
    }

    /**
     * @param note     e.g. "merged per block state in game" for blockstates
     * @param intended every loss in this file is covered by an intended marking
     */
    public record FileConflict(String location, FileKind kind, String winner, List<String> losers, List<String> harmless,
                               List<String> dead, @Nullable String note, boolean intended) {}

    public record LangConflict(String location, String language, String key, String winner, String winnerText,
                               List<LangLoser> losers, boolean intended) {}

    public record LangLoser(String pack, String text) {}

    /** @param event {@code namespace:event} */
    public record SoundReplacement(String event, String pack, List<String> replacedPacks, List<String> lostSounds, boolean intended) {}

    public record FilteredFile(String location, String filterPack, List<String> hiddenPacks, boolean intended) {}

    /**
     * Resource pack {@code upper} overrides {@code lower} (pairwise, Konzept 3.1).
     *
     * @param intended       covered by the marking {@code markingIndex} ("upper over lower")
     * @param againstMarking a marking says "lower over upper"
     */
    public record PairConflict(String upper, String lower, int files, int langKeys, int soundEvents, int filtered,
                               boolean intended, int markingIndex, boolean againstMarking) {
        public int total() {
            return files + langKeys + soundEvents + filtered;
        }
    }

    public enum MarkingStatus {
        /** Both packs are active and the winner overrides the loser. */
        WORKS,
        /** Both packs are active, but the loser now lies above the winner. */
        REVERSED,
        /** At least one of the two packs is not active. */
        PACK_NOT_ACTIVE,
        /** Both active, the winner is above, but they have no conflict any more. */
        NO_CONFLICT;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * @param winnerPack active pack matched by {@code marking.winner()}, or null
     * @param loserPack  active pack matched by {@code marking.loser()}, or null
     * @param items      conflicts the marking covers (WORKS) or the reversed pair has (REVERSED)
     */
    public record MarkingState(int index, IntendedMarking marking, MarkingStatus status, @Nullable String winnerPack,
                               @Nullable String loserPack, int items) {}

    public enum OverrideType {
        FILE, LANG_KEY, SOUND_EVENT
    }

    /**
     * @param pack     resource pack
     * @param location file; for sound events {@code ns:event}
     * @param target   mod/vanilla pack id
     * @param type     file, language keys (one entry per language file, {@code count} = keys in both files) or sound event
     * @param reversed true if the mod/vanilla resource wins (pack placed below mod resources)
     * @param count    number of items (1 for files and sound events)
     */
    public record ModOverride(String pack, String location, String target, String ownerKey, String ownerName,
                              OverrideType type, boolean reversed, int count) {}
}
