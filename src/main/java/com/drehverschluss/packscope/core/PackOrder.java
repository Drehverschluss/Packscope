package com.drehverschluss.packscope.core;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Active packs from top to bottom: index 0 is the strongest pack, as in the pack screen. Mod resources are already
 * expanded into their children (at their place).
 *
 * @param languages language files that count ({@code en_us} + the selected language, read on the render thread);
 *                  empty = all languages ({@code allLanguages})
 */
public record PackOrder(List<PackRef> packs, List<String> languages) {
    public PackOrder {
        packs = List.copyOf(packs);
        languages = List.copyOf(languages);
    }

    /** All languages count. */
    public PackOrder(List<PackRef> packs) {
        this(packs, List.of());
    }

    public PackOrder withLanguages(List<String> languages) {
        return new PackOrder(packs, languages);
    }

    /** Whether language files of {@code language} are evaluated. */
    public boolean countsLanguage(String language) {
        return languages.isEmpty() || languages.contains(language);
    }

    /** Key for result caching: ids, roles, overlays in order, and the counted languages. */
    public String key() {
        return packs.stream().map(p -> p.id() + "|" + p.kind() + "|" + p.overlays() + (p.hidden() ? "|hidden" : ""))
            .collect(Collectors.joining("\n")) + "\nlanguages=" + languages;
    }

    public int resourcePackCount() {
        return (int) packs.stream().filter(PackRef::isResourcePack).count();
    }
}
