package com.drehverschluss.packscope.core;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the core: scans and evaluates on one own background thread (daemon), writes reports. The caller
 * takes the {@link PackOrder} snapshot on the render thread and gets the result back as a future (hand it back to the
 * render thread with {@code Minecraft.execute}).
 */
public final class PackscopeService {
    private static final Logger LOG = LoggerFactory.getLogger("Packscope");

    private final Path reportDir;
    private final IndexCache cache;
    private final String versions;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Packscope-Scan");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    @Nullable
    private volatile ScanResult last;

    /**
     * @param gameDir  game directory; cache in {@code packscope/cache}, reports in {@code packscope/reports}
     * @param versions shown in the report, e.g. "Packscope 0.1.0 · Minecraft 1.21.1 · NeoForge 21.1.265"
     */
    public PackscopeService(Path gameDir, String versions) {
        Path base = gameDir.toAbsolutePath().normalize().resolve("packscope");
        this.reportDir = base.resolve("reports");
        this.cache = new IndexCache(base.resolve("cache"));
        this.versions = versions;
    }

    public Path reportDir() {
        return reportDir;
    }

    /** Last finished result, or null. */
    @Nullable
    public ScanResult last() {
        return last;
    }

    /** Scans (cached) and evaluates the order on the scan thread. */
    public CompletableFuture<ScanResult> evaluate(PackOrder order, PackscopeSettings settings) {
        return CompletableFuture.supplyAsync(() -> run(order, settings), executor);
    }

    /** Evaluates and writes both report files on the scan thread. */
    public CompletableFuture<Report> report(PackOrder order, PackscopeSettings settings) {
        return CompletableFuture.supplyAsync(() -> {
            ScanResult r = run(order, settings);
            try {
                Path md = ReportWriter.write(r, settings, versions, reportDir);
                LOG.info("Packscope: report written to {} ({} packs, {} conflicts, scan {} ms)", md, order.resourcePackCount(),
                    r.conflictCount(), r.scanNanos() / 1_000_000);
                return new Report(r, md);
            } catch (Exception e) {
                throw new RuntimeException("cannot write the report: " + e.getMessage(), e);
            }
        }, executor);
    }

    /** Drops the memory and disk cache ({@code /packscope rescan}); runs on the scan thread before later jobs. */
    public CompletableFuture<Void> clearCache() {
        return CompletableFuture.runAsync(() -> {
            cache.clear();
            last = null;
        }, executor);
    }

    private ScanResult run(PackOrder order, PackscopeSettings settings) {
        IndexCache c = cache;
        if (!settings.cacheEnabled()) {
            c = new IndexCache(null);
        }
        c.setDiskEnabled(settings.cacheEnabled());
        ScanResult r = PackEvaluator.evaluate(order, c, settings);
        last = r;
        return r;
    }

    public record Report(ScanResult result, Path markdown) {}
}
