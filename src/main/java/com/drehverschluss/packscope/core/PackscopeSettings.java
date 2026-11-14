package com.drehverschluss.packscope.core;

import java.util.List;

/**
 * Settings the core works with. Filled by the loader part from its config; the core never sees the config system.
 */
public record PackscopeSettings(boolean showInVanillaScreen, boolean showInPackedPacks, boolean ignoreIdentical,
                                boolean showModOverridesInTooltip, List<String> ignoredPaths, int previewSize,
                                boolean cacheEnabled, boolean autoExportReport, boolean reportListModOverrides) {
    public static final PackscopeSettings DEFAULTS = new PackscopeSettings(true, true, true, true, List.of(), 64, true, false, false);

    public PackscopeSettings {
        ignoredPaths = List.copyOf(ignoredPaths);
    }

    public PackscopeSettings withIgnoreIdentical(boolean value) {
        return new PackscopeSettings(showInVanillaScreen, showInPackedPacks, value, showModOverridesInTooltip, ignoredPaths,
            previewSize, cacheEnabled, autoExportReport, reportListModOverrides);
    }

    public PackscopeSettings withIgnoredPaths(List<String> value) {
        return new PackscopeSettings(showInVanillaScreen, showInPackedPacks, ignoreIdentical, showModOverridesInTooltip, value,
            previewSize, cacheEnabled, autoExportReport, reportListModOverrides);
    }

    public PackscopeSettings withCacheEnabled(boolean value) {
        return new PackscopeSettings(showInVanillaScreen, showInPackedPacks, ignoreIdentical, showModOverridesInTooltip, ignoredPaths,
            previewSize, value, autoExportReport, reportListModOverrides);
    }

    public PackscopeSettings withReportListModOverrides(boolean value) {
        return new PackscopeSettings(showInVanillaScreen, showInPackedPacks, ignoreIdentical, showModOverridesInTooltip, ignoredPaths,
            previewSize, cacheEnabled, autoExportReport, value);
    }
}
