package com.drehverschluss.packscope.compat.packedpacks;

import com.drehverschluss.packscope.core.minecraft.PackOrders;
import com.drehverschluss.packscope.gui.LiveResults;
import com.drehverschluss.packscope.gui.PackscopeUi;
import com.drehverschluss.packscope.gui.UiPlatform;
import com.drehverschluss.packscope.gui.model.OrderWatch;
import io.github.fishstiz.packed_packs.api.context.ScreenContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import org.jetbrains.annotations.Nullable;

/**
 * Live evaluation for one open Packed Packs resource pack screen (Konzept 7.2). The order is
 * {@code ScreenContext.getSelectedPacks()} (index 0 = top, the state in the screen before "Apply"; it contains the
 * packs hidden in the profile, {@code vanilla} and {@code mod_resources}), completed with the packs hidden by mod code.
 * It is compared on every rebuild of the lists and at the latest while the icons are drawn, and evaluated only when the
 * ids changed. One state per screen instance: returning from the detail screen keeps it, a new opening gets a new one.
 */
final class PackedPacksState extends LiveResults {
    private static final Map<Screen, PackedPacksState> STATES = new WeakHashMap<>();

    private ScreenContext context;
    private final OrderWatch watch = new OrderWatch();
    private final Set<String> selectedIds = new HashSet<>();
    private final Map<String, ResourceLocation> icons = new HashMap<>();
    private List<Pack> completeTopFirst = List.of();

    private PackedPacksState(UiPlatform platform, ScreenContext context) {
        super(platform, context.packRepository());
        this.context = context;
    }

    /**
     * The state of this screen, created on first use; null where Packscope stays out: datapack screen,
     * {@code showInPackedPacks = false}, or Packscope not set up.
     */
    @Nullable
    static PackedPacksState of(ScreenContext context) {
        if (!context.isClientResources()) return null;
        UiPlatform p = PackscopeUi.platform();
        if (p == null || !p.settings().showInPackedPacks()) return null;
        PackedPacksState s = STATES.get(context.screen());
        if (s == null || s.repository != context.packRepository()) {
            s = new PackedPacksState(p, context);
            STATES.put(context.screen(), s);
        }
        s.context = context;
        return s;
    }

    /** Packscope is (still) switched on for the Packed Packs screen. */
    boolean enabled() {
        return platform.settings().showInPackedPacks();
    }

    /** Takes the order of the screen and evaluates it if the ids changed (before the first build the list is empty). */
    void sync() {
        List<Pack> selected = context.getSelectedPacks();
        List<String> ids = new ArrayList<>(selected.size());
        for (Pack p : selected) ids.add(p.getId());
        if (!watch.changed(ids)) return;
        selectedIds.clear();
        selectedIds.addAll(ids);
        completeTopFirst = PackOrders.completeMenuOrder(selected, repository.getSelectedPacks(), Pack::isHidden);
        setOrder(completeTopFirst, hiddenInProfile());
    }

    /** {@link #sync()} at most once per frame, called while the icons are drawn. */
    void poll() {
        if (watch.due(System.nanoTime())) sync();
    }

    /** Whether the pack is active (in the right list) in the screen right now. */
    boolean isSelected(String packId) {
        return selectedIds.contains(packId);
    }

    /** Packs the profile hides (the vanilla model of the same repository leaves them out of its entry lists). */
    private Set<String> hiddenInProfile() {
        PackSelectionModel model = new PackSelectionModel(() -> {}, p -> null, repository, r -> {});
        return hiddenInScreen(model, repository);
    }

    Screen screen() {
        return context.screen();
    }

    void rememberIcon(String packId, ResourceLocation icon) {
        icons.put(packId, icon);
    }

    @Nullable
    @Override
    public ResourceLocation icon(String packId) {
        return icons.get(packId);
    }

    /** The order in the screen against the applied one in options.txt (saved only on "Apply"/"Done"). */
    @Override
    public boolean orderApplied() {
        return orderApplied(completeTopFirst);
    }
}
