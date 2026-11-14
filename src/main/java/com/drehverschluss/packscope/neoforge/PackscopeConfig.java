package com.drehverschluss.packscope.neoforge;

import com.drehverschluss.packscope.core.PackscopeSettings;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config {@code config/packscope-client.toml} (Konzept 9). The core only gets {@link #settings()}. */
public final class PackscopeConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue SHOW_IN_VANILLA_SCREEN;
    private static final ModConfigSpec.BooleanValue SHOW_IN_PACKED_PACKS;
    private static final ModConfigSpec.BooleanValue IGNORE_IDENTICAL;
    private static final ModConfigSpec.BooleanValue SHOW_MOD_OVERRIDES_IN_TOOLTIP;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> IGNORED_PATHS;
    private static final ModConfigSpec.IntValue PREVIEW_SIZE;
    private static final ModConfigSpec.BooleanValue CACHE_ENABLED;
    private static final ModConfigSpec.BooleanValue AUTO_EXPORT_REPORT;
    private static final ModConfigSpec.BooleanValue REPORT_LIST_MOD_OVERRIDES;
    private static final ModConfigSpec.BooleanValue ALL_LANGUAGES;
    private static final ModConfigSpec.BooleanValue SHOW_INTENDED_SYMBOL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        SHOW_IN_VANILLA_SCREEN = b
            .comment("Show conflict icons in the vanilla resource pack screen.")
            .translation("packscope.configuration.showInVanillaScreen")
            .define("showInVanillaScreen", true);
        SHOW_IN_PACKED_PACKS = b
            .comment("Show conflict icons in the Packed Packs screen (only if Packed Packs is installed).")
            .translation("packscope.configuration.showInPackedPacks")
            .define("showInPackedPacks", true);
        IGNORE_IDENTICAL = b
            .comment("Byte-identical files (same size and CRC32) and language entries with the same text are listed as harmless",
                "duplicates, not as conflicts.")
            .translation("packscope.configuration.ignoreIdentical")
            .define("ignoreIdentical", true);
        SHOW_MOD_OVERRIDES_IN_TOOLTIP = b
            .comment("Show the grey info lines about replaced files of mods and Minecraft in the icon tooltip.")
            .translation("packscope.configuration.showModOverridesInTooltip")
            .define("showModOverridesInTooltip", true);
        IGNORED_PATHS = b
            .comment("Files that never count as a conflict. '*' matches any characters, '?' one character.",
                "With a ':' the pattern is matched against namespace:path, without it against the path only.",
                "Example: [\"minecraft:textures/gui/title/*\", \"*.txt\"]")
            .translation("packscope.configuration.ignoredPaths")
            .defineListAllowEmpty("ignoredPaths", List.of(), () -> "minecraft:textures/gui/title/*", o -> o instanceof String);
        PREVIEW_SIZE = b
            .comment("Size of the texture preview in the detail screen, in pixels.")
            .translation("packscope.configuration.previewSize")
            .defineInRange("previewSize", 64, 16, 256);
        CACHE_ENABLED = b
            .comment("Keep scan results in memory and the checksums of folder packs in packscope/cache/ (game folder).")
            .translation("packscope.configuration.cacheEnabled")
            .define("cacheEnabled", true);
        AUTO_EXPORT_REPORT = b
            .comment("Write packscope/reports/packscope-report.md and .json after every resource reload.")
            .translation("packscope.configuration.autoExportReport")
            .define("autoExportReport", false);
        REPORT_LIST_MOD_OVERRIDES = b
            .comment("List every replaced mod/vanilla file in the report (otherwise only the numbers per mod).")
            .translation("packscope.configuration.reportListModOverrides")
            .define("reportListModOverrides", false);
        ALL_LANGUAGES = b
            .comment("Count language keys of all languages. Off: only en_us and the selected game language,",
                "the ones the game actually loads.")
            .translation("packscope.configuration.allLanguages")
            .define("allLanguages", false);
        SHOW_INTENDED_SYMBOL = b
            .comment("Show a grey icon for packs whose conflicts are all marked as intended. Off: no icon for them.")
            .translation("packscope.configuration.showIntendedSymbol")
            .define("showIntendedSymbol", true);
        SPEC = b.build();
    }

    private PackscopeConfig() {}

    /** Current values; defaults before the config is loaded. */
    public static PackscopeSettings settings() {
        if (!SPEC.isLoaded()) return PackscopeSettings.DEFAULTS;
        return new PackscopeSettings(SHOW_IN_VANILLA_SCREEN.get(), SHOW_IN_PACKED_PACKS.get(), IGNORE_IDENTICAL.get(),
            SHOW_MOD_OVERRIDES_IN_TOOLTIP.get(), List.copyOf(IGNORED_PATHS.get()), PREVIEW_SIZE.get(), CACHE_ENABLED.get(),
            AUTO_EXPORT_REPORT.get(), REPORT_LIST_MOD_OVERRIDES.get(), ALL_LANGUAGES.get(), SHOW_INTENDED_SYMBOL.get());
    }
}
