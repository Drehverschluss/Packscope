package com.drehverschluss.packscope.core;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Active packs from top to bottom: index 0 is the strongest pack, as in the pack screen. Mod resources are already
 * expanded into their children (at their place).
 */
public record PackOrder(List<PackRef> packs) {
    public PackOrder {
        packs = List.copyOf(packs);
    }

    /** Key for result caching: ids, roles and overlays in order. */
    public String key() {
        return packs.stream().map(p -> p.id() + "|" + p.kind() + "|" + p.overlays()).collect(Collectors.joining("\n"));
    }

    public int resourcePackCount() {
        return (int) packs.stream().filter(PackRef::isResourcePack).count();
    }
}
