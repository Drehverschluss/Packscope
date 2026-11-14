package com.drehverschluss.packscope.core;

import java.nio.file.Path;
import java.util.List;
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
    private final IntendedStore intended;
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
     * @param intendedFile {@code config/packscope-intended.json}
     * @param versions shown in the report, e.g. "Packscope 0.1.0 · Minecraft 1.21.1 · NeoForge 21.1.265"
     */
    public PackscopeService(Path gameDir, Path intendedFile, String versions) {
        Path base = gameDir.toAbsolutePath().normalize().resolve("packscope");
        this.reportDir = base.resolve("reports");
        this.cache = new IndexCache(base.resolve("cache"));
        this.intended = new IntendedStore(intendedFile.toAbsolutePath().normalize());
        this.versions = versions;
    }

    /**
     * The intended markings. Change them on the render thread ({@code mark}, {@code unmark}, {@code markAllOf} ...);
     * they apply to the next evaluation without a new scan.
     */
    public IntendedStore intended() {
        return intended;
    }

    public Path reportDir() {
        return reportDir;
    }

    /** Last finished result, or null. */
    @Nullable
    public ScanResult last() {
        return last;
    }

    /**
     * Scans (cached) and evaluates the order on the scan thread.
     *
     * @param intended snapshot of the intended markings, taken together with the order
     */
    public CompletableFuture<ScanResult> evaluate(PackOrder order, PackscopeSettings settings, IntendedSet intended) {
        return CompletableFuture.supplyAsync(() -> run(order, settings, intended), executor);
    }

    /** Like {@link #evaluate(PackOrder, PackscopeSettings, IntendedSet)} with the current markings (call on the render thread). */
    public CompletableFuture<ScanResult> evaluate(PackOrder order, PackscopeSettings settings) {
        return evaluate(order, settings, intended.snapshot());
    }

    /** Like {@link #report(PackOrder, PackscopeSettings, IntendedSet)} with the current markings (call on the render thread). */
    public CompletableFuture<Report> report(PackOrder order, PackscopeSettings settings) {
        return report(order, settings, intended.snapshot());
    }

    /** Evaluates and writes both report files on the scan thread. */
    public CompletableFuture<Report> report(PackOrder order, PackscopeSettings settings, IntendedSet intended) {
        return report(order, settings, intended, List.of());
    }

    /**
     * Evaluates and writes both report files on the scan thread.
     *
     * @param notes extra lines for the head of the report (e.g. "order from the pack screen, not applied yet")
     */
    public CompletableFuture<Report> report(PackOrder order, PackscopeSettings settings, IntendedSet intended, List<String> notes) {
        return CompletableFuture.supplyAsync(() -> {
            ScanResult r = run(order, settings, intended);
            try {
                Path md = ReportWriter.write(r, settings, versions, notes, reportDir);
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

    private ScanResult run(PackOrder order, PackscopeSettings settings, IntendedSet intended) {
        IndexCache c = cache;
        if (!settings.cacheEnabled()) {
            c = new IndexCache(null);
        }
        c.setDiskEnabled(settings.cacheEnabled());
        ScanResult r = PackEvaluator.evaluate(order, c, settings, intended);
        last = r;
        return r;
    }

    public record Report(ScanResult result, Path markdown) {}
}
