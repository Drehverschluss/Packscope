package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.core.IntendedSet;
import com.drehverschluss.packscope.core.IntendedStore;
import com.drehverschluss.packscope.core.PackOrder;
import com.drehverschluss.packscope.core.PackRole;
import com.drehverschluss.packscope.core.PackscopeSettings;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.core.minecraft.PackOrders;
import com.drehverschluss.packscope.gui.model.Line;
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
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live evaluation for one open vanilla resource pack screen (Konzept 5.1 "Live", 7.1). After every
 * {@code populateLists} the order in the screen is taken (with the hidden packs, Phase-3A-Bericht 4) and its result is
 * taken from the cache or evaluated in the background. Only the newest order counts: while an evaluation runs, later
 * orders wait and the one in between is skipped; a result for an old order is cached but not shown. The pack entries
 * read {@link #look(String)} and {@link #tooltip(String)} on every frame, so the right icon appears without rebuilding
 * the list. Render thread only.
 */
public final class MenuState implements ResultSource {
    private static final Logger LOG = LoggerFactory.getLogger("Packscope");
    /** After an order change the old result stays on screen this long before "checking" appears (no flicker). */
    static final long GRACE_NANOS = 300_000_000L;
    /** A result of an earlier pack screen is shown at most this long while it is checked again. */
    static final long PROVISIONAL_NANOS = 5_000_000_000L;

    private final UiPlatform platform;
    private final PackRepository repository;
    private final Function<Pack, ResourceLocation> icons;
    private final int session = PackscopeUi.newSession();
    private final Set<String> parentIds = new HashSet<>();

    private List<Pack> visibleTopFirst = List.of();
    private List<Pack> menuTopFirst = List.of();
    private PackOrder order = new PackOrder(List.of());
    private String key = "";
    @Nullable
    private ScanResult shown;
    @Nullable
    private ScanResult previous;
    private long previousUntil;
    private boolean running;
    private boolean failed;
    private int generation;
    private int evaluations;
    private long lastUpdateNanos;

    @Nullable
    private ScanResult tooltipsFor;
    private final Map<String, List<Line>> tooltips = new HashMap<>();

    public MenuState(UiPlatform platform, PackRepository repository, Function<Pack, ResourceLocation> icons) {
        this.platform = platform;
        this.repository = repository;
        this.icons = icons;
        platform.service().intended().reloadIfChanged();
    }

    /**
     * Creates the state if this is the resource pack screen and Packscope shows icons there, else returns null
     * (datapack screen, {@code showInVanillaScreen = false}).
     */
    @Nullable
    public static MenuState create(PackRepository repository, Function<Pack, ResourceLocation> icons) {
        UiPlatform p = PackscopeUi.platform();
        if (p == null || repository != Minecraft.getInstance().getResourcePackRepository()) return null;
        if (!p.settings().showInVanillaScreen()) return null;
        return new MenuState(p, repository, icons);
    }

    // ------------------------------------------------------------------------------------------------ order

    /**
     * The lists of the screen changed ({@code populateLists}).
     *
     * @param selectedTopFirst {@code PackSelectionModel.selected} (index 0 = top, without hidden packs)
     */
    public void update(List<Pack> selectedTopFirst) {
        long start = System.nanoTime();
        parentIds.clear();
        for (Pack p : repository.getAvailablePacks()) if (!p.getChildren().isEmpty()) parentIds.add(p.getId());
        visibleTopFirst = List.copyOf(selectedTopFirst);
        menuTopFirst = PackOrders.completeMenuOrder(visibleTopFirst, repository.getSelectedPacks());
        PackscopeSettings s = platform.settings();
        List<String> languages = s.allLanguages() ? List.of() : PackOrders.loadedLanguages();
        order = PackOrders.fromTopFirst(menuTopFirst, platform.modNames(), languages);
        request();
        lastUpdateNanos = System.nanoTime() - start;
    }

    @Override
    public void refresh() {
        request();
    }

    private void request() {
        PackscopeSettings s = platform.settings();
        IntendedSet snap = platform.service().intended().snapshot();
        String k = order.key() + "\n#intended=" + snap.version() + "\n#settings=" + s;
        if (k.equals(key) && (shown != null || running)) return;
        key = k;
        failed = false;
        PackscopeUi.Cached c = PackscopeUi.cached(k);
        if (c != null && c.session() == session) {
            show(c.result());
            return;
        }
        if (shown != null) {
            previous = shown;
            previousUntil = System.nanoTime() + GRACE_NANOS;
        }
        if (c != null) {
            // same order in an earlier pack screen: show it while checking again (files may have changed)
            previous = c.result();
            previousUntil = System.nanoTime() + PROVISIONAL_NANOS;
        }
        if (shown != null) generation++;
        shown = null;
        if (!running) start(order, s, snap, k);
    }

    private void start(PackOrder o, PackscopeSettings s, IntendedSet snap, String k) {
        running = true;
        evaluations++;
        Minecraft mc = Minecraft.getInstance();
        platform.service().evaluate(o, s, snap).whenComplete((r, e) -> mc.execute(() -> finished(k, r, e)));
    }

    private void finished(String k, @Nullable ScanResult r, @Nullable Throwable e) {
        running = false;
        if (r != null) PackscopeUi.put(k, r, session);
        else LOG.warn("Packscope: evaluation for the pack screen failed", e);
        if (k.equals(key)) {
            if (r != null) {
                show(r);
            } else {
                failed = true;
                previous = null;
                generation++;
            }
        } else {
            // the order changed meanwhile: only the newest one counts
            key = "";
            request();
        }
    }

    private void show(ScanResult r) {
        if (shown != r) generation++;
        shown = r;
        previous = null;
    }

    // ------------------------------------------------------------------------------------------------ entries

    /** Result to draw now: the current one, or the previous one shortly after a change. */
    @Nullable
    private ScanResult drawn() {
        if (shown != null) return shown;
        if (previous != null && System.nanoTime() < previousUntil) return previous;
        return null;
    }

    /** Whether the entry of this pack gets an icon place (and a shorter title): not vanilla, not mod resources. */
    public boolean isIconEntry(String packId) {
        return !"vanilla".equals(packId) && !parentIds.contains(packId);
    }

    /** Icon of a pack entry now, or null for none. */
    @Nullable
    public StatusLook look(String packId) {
        if (!isIconEntry(packId)) return null;
        ScanResult r = drawn();
        if (r == null) return failed ? null : StatusLook.CHECKING;
        ScanResult.PackSummary s = r.summary(packId);
        if (s == null) return shown == null && !failed ? StatusLook.CHECKING : null;
        if (s.role() != PackRole.RESOURCE_PACK) return null;
        return StatusLook.of(s.status(), platform.settings());
    }

    /** Tooltip of a pack entry icon (cached per result). */
    public List<Line> tooltip(String packId) {
        ScanResult r = drawn();
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
        return shown != null ? shown : previous;
    }

    @Override
    public boolean pending() {
        return shown == null && !failed;
    }

    @Override
    public int generation() {
        return generation;
    }

    @Override
    public IntendedStore intended() {
        return platform.service().intended();
    }

    @Nullable
    @Override
    public ResourceLocation icon(String packId) {
        for (Pack p : menuTopFirst) {
            if (p.getId().equals(packId)) return icons.apply(p);
        }
        return null;
    }

    /** Ids of the screen order (bottom first, without hidden packs) against the applied ones in options.txt. */
    @Override
    public boolean orderApplied() {
        Minecraft mc = Minecraft.getInstance();
        Set<String> available = new HashSet<>(repository.getAvailableIds());
        List<String> applied = new ArrayList<>();
        for (String id : mc.options.resourcePacks) if (available.contains(id)) applied.add(id);
        List<String> menu = new ArrayList<>();
        for (int i = visibleTopFirst.size() - 1; i >= 0; i--) menu.add(visibleTopFirst.get(i).getId());
        return applied.equals(menu);
    }

    @Override
    public CompletableFuture<Path> exportReport() {
        List<String> notes = orderApplied() ? List.of()
            : List.of("The load order is the one in the pack screen and is not applied yet (press \"Done\" to apply it).");
        return platform.service().report(order, platform.settings(), platform.service().intended().snapshot(), notes)
            .thenApply(r -> r.markdown());
    }

    // ------------------------------------------------------------------------------------------------ info

    public PackOrder order() {
        return order;
    }

    /** Evaluations this screen started (the rest came from the cache). */
    public int evaluations() {
        return evaluations;
    }

    /** Time the last {@link #update} took on the render thread, in nanoseconds. */
    public long lastUpdateNanos() {
        return lastUpdateNanos;
    }

    public Collection<Pack> menuOrder() {
        return menuTopFirst;
    }
}
