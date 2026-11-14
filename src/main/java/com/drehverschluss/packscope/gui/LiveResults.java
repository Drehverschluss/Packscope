package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.core.IntendedSet;
import com.drehverschluss.packscope.core.IntendedStore;
import com.drehverschluss.packscope.core.PackOrder;
import com.drehverschluss.packscope.core.PackRole;
import com.drehverschluss.packscope.core.PackscopeSettings;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.core.minecraft.PackOrders;
import com.drehverschluss.packscope.gui.model.Line;
import com.drehverschluss.packscope.gui.model.ResultTracker;
import com.drehverschluss.packscope.gui.model.StatusLook;
import com.drehverschluss.packscope.gui.model.TooltipModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live evaluation of one open resource pack screen, shared by the vanilla screen ({@link MenuState}) and the Packed
 * Packs screen (Konzept 5.1 "Live"). The screen hands in its complete order (top first, with the packs it does not
 * show) whenever it changed; the result comes from the cache or is evaluated in the background, and only the newest
 * order counts ({@link ResultTracker}). Icons and tooltips are read on every frame. Render thread only.
 */
public abstract class LiveResults implements ResultSource {
    private static final Logger LOG = LoggerFactory.getLogger("Packscope");

    protected final UiPlatform platform;
    protected final PackRepository repository;
    private final ResultTracker<ScanResult> tracker = new ResultTracker<>(PackscopeUi.newSession(), PackscopeUi.RESULTS,
        System::nanoTime);
    private final Set<String> parentIds = new HashSet<>();

    private List<Pack> menuTopFirst = List.of();
    private PackOrder order = new PackOrder(List.of());
    /** Order, settings and markings the current key stands for (used when an evaluation starts). */
    private PackscopeSettings wantedSettings = PackscopeSettings.DEFAULTS;
    private IntendedSet wantedMarkings = IntendedSet.EMPTY;
    private long lastUpdateNanos;

    @Nullable
    private ScanResult tooltipsFor;
    private final Map<String, List<Line>> tooltips = new HashMap<>();

    protected LiveResults(UiPlatform platform, PackRepository repository) {
        this.platform = platform;
        this.repository = repository;
        platform.service().intended().reloadIfChanged();
    }

    // ------------------------------------------------------------------------------------------------ order

    /**
     * The order of the screen changed.
     *
     * @param completeTopFirst all selected packs, top first, including the ones the screen does not show
     * @param hiddenIds        ids of packs that have no entry in the screen ("(hidden)" in tooltips and details)
     */
    protected final void setOrder(List<Pack> completeTopFirst, Set<String> hiddenIds) {
        long start = System.nanoTime();
        parentIds.clear();
        for (Pack p : repository.getAvailablePacks()) if (!p.getChildren().isEmpty()) parentIds.add(p.getId());
        menuTopFirst = List.copyOf(completeTopFirst);
        PackscopeSettings s = platform.settings();
        List<String> languages = s.allLanguages() ? List.of() : PackOrders.loadedLanguages();
        order = PackOrders.fromTopFirst(menuTopFirst, hiddenIds, platform.modNames(), languages);
        request();
        lastUpdateNanos = System.nanoTime() - start;
    }

    @Override
    public void refresh() {
        request();
    }

    private void request() {
        wantedSettings = platform.settings();
        wantedMarkings = platform.service().intended().snapshot();
        String k = order.key() + "\n#intended=" + wantedMarkings.version() + "\n#settings=" + wantedSettings;
        start(tracker.request(k));
    }

    private void start(@Nullable String k) {
        if (k == null) return;
        Minecraft mc = Minecraft.getInstance();
        platform.service().evaluate(order, wantedSettings, wantedMarkings).whenComplete((r, e) -> mc.execute(() -> {
            if (r == null) LOG.warn("Packscope: evaluation for the pack screen failed", e);
            start(tracker.finished(k, r));
        }));
    }

    // ------------------------------------------------------------------------------------------------ entries

    /** Whether the entry of this pack gets an icon (place): not vanilla, not mod resources. */
    public boolean isIconEntry(String packId) {
        return !"vanilla".equals(packId) && !parentIds.contains(packId);
    }

    /** Icon of a pack entry now, or null for none. */
    @Nullable
    public StatusLook look(String packId) {
        if (!isIconEntry(packId)) return null;
        ScanResult r = tracker.drawn();
        if (r == null) return tracker.failed() ? null : StatusLook.CHECKING;
        ScanResult.PackSummary s = r.summary(packId);
        if (s == null) return tracker.shown() == null && !tracker.failed() ? StatusLook.CHECKING : null;
        if (s.role() != PackRole.RESOURCE_PACK) return null;
        return StatusLook.of(s.status(), platform.settings());
    }

    /** Tooltip of a pack entry icon (cached per result). */
    public List<Line> tooltip(String packId) {
        ScanResult r = tracker.drawn();
        if (r == null || r.summary(packId) == null) return TooltipModel.checking();
        if (r != tooltipsFor) {
            tooltips.clear();
            tooltipsFor = r;
        }
        return tooltips.computeIfAbsent(packId, id -> TooltipModel.build(r, id, platform.settings(), PackscopeUi.language()));
    }

    // ------------------------------------------------------------------------------------------------ ResultSource

    @Nullable
    @Override
    public ScanResult latest() {
        return tracker.latest();
    }

    @Override
    public boolean pending() {
        return tracker.pending();
    }

    @Override
    public int generation() {
        return tracker.generation();
    }

    @Override
    public IntendedStore intended() {
        return platform.service().intended();
    }

    @Override
    public CompletableFuture<Path> exportReport() {
        List<String> notes = orderApplied() ? List.of()
            : List.of("The load order is the one in the pack screen and is not applied yet (press \"Done\" to apply it).");
        return platform.service().report(order, platform.settings(), platform.service().intended().snapshot(), notes)
            .thenApply(r -> r.markdown());
    }

    /**
     * Whether the order {@code topFirst} is the applied one in {@code options.resourcePacks} (packs hidden by mod code,
     * {@code Pack.isHidden()}, never stand there and are left out on both sides).
     */
    protected final boolean orderApplied(List<Pack> topFirst) {
        Minecraft mc = Minecraft.getInstance();
        Set<String> available = new HashSet<>(repository.getAvailableIds());
        List<String> applied = new ArrayList<>();
        for (String id : mc.options.resourcePacks) {
            Pack p = repository.getPack(id);
            if (available.contains(id) && p != null && !p.isHidden()) applied.add(id);
        }
        List<String> menu = new ArrayList<>();
        for (int i = topFirst.size() - 1; i >= 0; i--) {
            if (!topFirst.get(i).isHidden()) menu.add(topFirst.get(i).getId());
        }
        return applied.equals(menu);
    }

    /**
     * Ids of the packs a pack screen leaves out of its lists although they are not hidden by mod code: with Packed
     * Packs the packs hidden in the profile ({@code PackSelectionModel.getSelected/getUnselected} filter them, the
     * fields keep them, Phase-1-Bericht 5.8). Without Packed Packs this is empty.
     */
    protected static Set<String> hiddenInScreen(PackSelectionModel model, PackRepository repository) {
        Set<String> listed = new HashSet<>();
        model.getSelected().forEach(e -> listed.add(e.getId()));
        model.getUnselected().forEach(e -> listed.add(e.getId()));
        Set<String> out = new HashSet<>();
        for (Pack p : repository.getAvailablePacks()) {
            if (!p.isHidden() && !listed.contains(p.getId())) out.add(p.getId());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ info

    public PackOrder order() {
        return order;
    }

    /** Evaluations this screen started (the rest came from the cache). */
    public int evaluations() {
        return tracker.evaluations();
    }

    /** Time the last order update took on the render thread, in nanoseconds. */
    public long lastUpdateNanos() {
        return lastUpdateNanos;
    }

    /** The complete order of the screen, top first. */
    public Collection<Pack> menuOrder() {
        return menuTopFirst;
    }

    /** The pack of the screen order with this id, or null. */
    @Nullable
    protected final Pack menuPack(String packId) {
        for (Pack p : menuTopFirst) if (p.getId().equals(packId)) return p;
        return null;
    }
}
