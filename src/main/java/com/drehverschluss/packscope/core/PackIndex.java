package com.drehverschluss.packscope.core;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * All files of one pack below {@code assets/} (active overlays merged in, the later overlay wins), keyed by
 * {@code namespace:path}. For lookup packs only the paths that were asked for and exist.
 *
 * @param approximate true if the listing may be incomplete (fallback scan)
 * @param warnings    problems found while scanning (broken pack.mcmeta, unreadable files ...)
 * @param scanNanos   time the scan took
 */
public record PackIndex(String packId, ScanKind kind, Map<String, FileEntry> files, @Nullable PackFilter filter,
                        boolean approximate, List<String> warnings, long scanNanos) {
    public PackIndex {
        files = Map.copyOf(files);
        warnings = List.copyOf(warnings);
    }
}
