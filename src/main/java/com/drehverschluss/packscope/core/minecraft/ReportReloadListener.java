package com.drehverschluss.packscope.core.minecraft;

import com.drehverschluss.packscope.core.PackscopeSettings;
import java.util.function.Supplier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * After every resource reload: drops the caches of dynamic packs (Phase 5C) and writes the report when
 * {@code autoExportReport} is on. Plain vanilla listener; the
 * loader part registers it (NeoForge: {@code RegisterClientReloadListenersEvent}).
 */
public final class ReportReloadListener implements ResourceManagerReloadListener {
    private final Supplier<PackscopeSettings> settings;
    private final Runnable exportReport;
    private final Runnable resourcesChanged;

    /**
     * @param exportReport     called on the render thread; takes the snapshot and starts the report in the background
     * @param resourcesChanged called on every reload first: drops what a reload can change (dynamic and injected packs)
     */
    public ReportReloadListener(Supplier<PackscopeSettings> settings, Runnable exportReport, Runnable resourcesChanged) {
        this.settings = settings;
        this.exportReport = exportReport;
        this.resourcesChanged = resourcesChanged;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        resourcesChanged.run();
        if (settings.get().autoExportReport()) exportReport.run();
    }
}
