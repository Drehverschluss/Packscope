package com.drehverschluss.packscope.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scan + evaluation of one {@link PackOrder}: indexes the resource packs (cached), asks vanilla/mod packs only for the
 * paths the resource packs have, then runs the {@link ConflictResolver}. Runs on the scan thread.
 */
public final class PackEvaluator {
    private PackEvaluator() {}

    public static ScanResult evaluate(PackOrder order, IndexCache cache, PackscopeSettings settings) {
        return evaluate(order, cache, settings, IntendedSet.EMPTY);
    }

    /** @param intended snapshot of the intended markings (taken on the render thread) */
    public static ScanResult evaluate(PackOrder order, IndexCache cache, PackscopeSettings settings, IntendedSet intended) {
        long start = System.nanoTime();
        List<PackRef> packs = order.packs();
        List<PackIndex> indexes = new ArrayList<>(packs.size());
        Set<String> queries = new HashSet<>();
        for (PackRef ref : packs) {
            if (ref.kind() == ScanKind.LOOKUP) {
                indexes.add(null);
                continue;
            }
            PackIndex index = PackScanner.scan(ref, cache);
            indexes.add(index);
            for (String loc : index.files().keySet()) {
                queries.add(loc);
                // the resource below a .mcmeta decides which .mcmeta applies, also if only vanilla/mods have it
                if (loc.endsWith(".mcmeta")) queries.add(loc.substring(0, loc.length() - ".mcmeta".length()));
            }
        }
        for (int i = 0; i < packs.size(); i++) {
            if (indexes.get(i) == null) indexes.set(i, PackScanner.lookup(packs.get(i), queries, cache));
        }
        long scanned = System.nanoTime();
        ScanResult result = ConflictResolver.resolve(order, indexes, settings, cache, intended);
        return result.withTimes(scanned - start, result.resolveNanos());
    }
}
