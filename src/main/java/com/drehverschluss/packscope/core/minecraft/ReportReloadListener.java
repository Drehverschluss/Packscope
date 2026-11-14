package com.drehverschluss.packscope.core.minecraft;

import com.drehverschluss.packscope.core.PackscopeSettings;
import java.util.function.Supplier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * Writes the report after every resource reload when {@code autoExportReport} is on. Plain vanilla listener; the
 * loader part registers it (NeoForge: {@code RegisterClientReloadListenersEvent}).
 */
public final class ReportReloadListener implements ResourceManagerReloadListener {
    private final Supplier<PackscopeSettings> settings;
    private final Runnable exportReport;

    /** @param exportReport called on the render thread; takes the snapshot and starts the report in the background */
    public ReportReloadListener(Supplier<PackscopeSettings> settings, Runnable exportReport) {
        this.settings = settings;
        this.exportReport = exportReport;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        if (settings.get().autoExportReport()) exportReport.run();
    }
}
