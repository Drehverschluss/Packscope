package com.drehverschluss.packscope.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Result of one evaluation of a {@link PackOrder}. Pack ids refer to {@link PackRef#id()}.
 *
 * @param packs             one summary per pack of the order, same order (top first)
 * @param fileConflicts     whole-file conflicts between resource packs (incl. blockstates and .mcmeta), sorted by location
 * @param langConflicts     language keys defined by more than one resource pack with different text
 * @param soundReplacements sound events a resource pack replaces ({@code "replace": true}) in lower resource packs
 * @param filtered          files hidden by a {@code filter} in pack.mcmeta
 * @param modOverrides      resource pack files/keys/events that replace (or lose to) mod and vanilla resources
 * @param indexes           index per pack id (for later use, e.g. the detail screen)
 */
public record ScanResult(Instant created, PackOrder order, List<PackSummary> packs, List<FileConflict> fileConflicts,
                         List<LangConflict> langConflicts, List<SoundReplacement> soundReplacements, List<FilteredFile> filtered,
                         List<ModOverride> modOverrides, List<String> warnings, Map<String, PackIndex> indexes,
                         long scanNanos, long resolveNanos) {

    @Nullable
    public PackSummary summary(String packId) {
        for (PackSummary s : packs) if (s.id().equals(packId)) return s;
        return null;
    }

    /** Number of real conflicts: files with a losing resource pack, language keys, replaced sound events. */
    public int conflictCount() {
        int files = (int) fileConflicts.stream().filter(c -> !c.losers().isEmpty() || !c.dead().isEmpty()).count();
        return files + langConflicts.size() + soundReplacements.size();
    }

    public List<PackSummary> coveredPacks() {
        return packs.stream().filter(p -> p.status() == PackStatus.COMPLETELY_COVERED).toList();
    }

    public ScanResult withTimes(long scan, long resolve) {
        return new ScanResult(created, order, packs, fileConflicts, langConflicts, soundReplacements, filtered, modOverrides,
            warnings, indexes, scan, resolve);
    }

    /**
     * Numbers per pack. Counted per file, language key or sound event ("item").
     *
     * @param status             only meaningful for resource packs ({@link PackStatus#NO_CONFLICT} for mod/vanilla)
     * @param wins               items where this pack overrides at least one lower resource pack
     * @param losses             items overridden by a higher resource pack (incl. sound events replaced by one)
     * @param dead               .mcmeta files that never apply because a higher pack replaced the resource
     * @param harmless           items identical to the winning copy (only with ignoreIdentical)
     * @param hiddenByFilter     files hidden by the filter of a higher pack
     * @param hidesOthers        files of lower packs this pack hides with its filter
     * @param replacedSoundEvents sound events of lower resource packs this pack replaces
     * @param lostSoundEvents    own sound events replaced by a higher resource pack
     * @param lostToMods         items overridden by mod/vanilla resources above this pack
     * @param modOverrides       items that replace mod/vanilla resources (info)
     * @param winsAgainst        pack id -> items this pack wins against it
     * @param losesTo            pack id -> items this pack loses to it
     * @param modOverridesByOwner mod id ("minecraft" for vanilla) -> items replaced
     */
    public record PackSummary(String id, String title, PackRole role, ScanKind kind, boolean approximate, int fileCount,
                              PackStatus status, int wins, int losses, int dead, int harmless, int hiddenByFilter,
                              int hidesOthers, int replacedSoundEvents, int lostSoundEvents, int lostToMods, int modOverrides,
                              Map<String, Integer> winsAgainst, Map<String, Integer> losesTo,
                              Map<String, Integer> modOverridesByOwner, @Nullable String modId, @Nullable String parentId) {}

    /** @param note e.g. "merged per block state in game" for blockstates */
    public record FileConflict(String location, FileKind kind, String winner, List<String> losers, List<String> harmless,
                               List<String> dead, @Nullable String note) {}

    public record LangConflict(String location, String language, String key, String winner, String winnerText,
                               List<LangLoser> losers) {}

    public record LangLoser(String pack, String text) {}

    /** @param event {@code namespace:event} */
    public record SoundReplacement(String event, String pack, List<String> replacedPacks, List<String> lostSounds) {}

    public record FilteredFile(String location, String filterPack, List<String> hiddenPacks) {}

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
