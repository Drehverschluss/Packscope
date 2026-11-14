package com.drehverschluss.packscope.compat.packedpacks;

import com.drehverschluss.packscope.core.PackNames;
import com.drehverschluss.packscope.core.ScanResult;
import com.drehverschluss.packscope.gui.VanillaMenu;
import com.drehverschluss.packscope.gui.model.ConflictView;
import io.github.fishstiz.packed_packs.api.EventBus;
import io.github.fishstiz.packed_packs.api.PackedPacksApi;
import io.github.fishstiz.packed_packs.api.PackedPacksInitializer;
import io.github.fishstiz.packed_packs.api.context.PackContext;
import io.github.fishstiz.packed_packs.api.events.ContextMenuEvent;
import io.github.fishstiz.packed_packs.api.events.InitializeLayoutEvent;
import io.github.fishstiz.packed_packs.api.events.InitializePackEntryEvent;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Packscope in the Packed Packs screen (Konzept 7.2). Packed Packs loads this class through
 * {@code META-INF/services/io.github.fishstiz.packed_packs.api.PackedPacksInitializer}; without Packed Packs nobody
 * reads that file and no class of this package is ever loaded. {@code onInitialize} runs when the Packed Packs screen
 * opens for the first time and only registers listeners. Every listener stays out of the datapack screen and does
 * nothing with {@code showInPackedPacks = false} ({@link PackedPacksState#of}).
 */
public final class PackedPacksIntegration implements PackedPacksInitializer {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("packscope", "conflicts");
    /** Space between the icon and the right edge of the entry. */
    static final int ICON_MARGIN = 2;

    @Override
    public void onInitialize(PackedPacksApi api) {
        EventBus bus = api.eventBus();
        bus.register(InitializeLayoutEvent.class, ID, PackedPacksIntegration::onLayout);
        bus.register(InitializePackEntryEvent.class, ID, PackedPacksIntegration::onEntry);
        bus.register(ContextMenuEvent.PackEntry.class, ID, PackedPacksIntegration::onContextMenu);
    }

    /** Screen (re)built: take the order, add the header button. */
    private static void onLayout(InitializeLayoutEvent event) {
        PackedPacksState state = PackedPacksState.of(event.screenContext());
        if (state == null) return;
        state.sync();
        event.addWidget(InitializeLayoutEvent.Pos.AFTER_TITLE, ConflictsButton.create(state));
    }

    /** A leaf entry is built (lazily when first drawn, again after every rebuild of the lists). */
    private static void onEntry(InitializePackEntryEvent event) {
        PackedPacksState state = PackedPacksState.of(event.screenContext());
        if (state == null) return;
        state.sync();
        PackContext pack = event.packContext();
        String id = pack.pack().getId();
        state.rememberIcon(id, pack.icon());
        if (!state.isSelected(id) || !state.isIconEntry(id)) return;
        event.addTopRight(ICON_MARGIN, new StatusWidget(state, id, pack.pack().getTitle()));
    }

    /** Right click on an active pack: show conflicts, mark all / remove all markings (Konzept 4.1, 5.2). */
    private static void onContextMenu(ContextMenuEvent.PackEntry event) {
        PackedPacksState state = PackedPacksState.of(event.screenContext());
        if (state == null) return;
        state.sync();
        String id = event.packContext().pack().getId();
        if (!state.isSelected(id) || !state.isIconEntry(id)) return;
        event.addItem(item -> item.label(Component.translatable("packscope.menu.show"))
            .tooltip(Tooltip.create(Component.translatable("packscope.menu.show.tooltip")))
            .action(() -> VanillaMenu.openDetails(state.screen(), state, id)));
        ScanResult r = state.latest();
        if (r == null || r.summary(id) == null) return;
        if (ConflictView.of(r, id).hasOpenPairs()) {
            event.addItem(item -> item.label(Component.translatable("packscope.menu.mark_all"))
                .tooltip(Tooltip.create(Component.translatable("packscope.detail.mark_all.tooltip")))
                .action(() -> {
                    ScanResult now = state.latest();
                    if (now != null) state.intended().markAllOf(id, now);
                    state.refresh();
                }));
        }
        boolean anyMarked = state.intended().markings().stream()
            .anyMatch(m -> PackNames.matches(m.winner(), id) || PackNames.matches(m.loser(), id));
        if (anyMarked) {
            event.addItem(item -> item.label(Component.translatable("packscope.menu.unmark_all"))
                .tooltip(Tooltip.create(Component.translatable("packscope.detail.unmark_all.tooltip")))
                .action(() -> {
                    state.intended().unmarkAllOf(id);
                    state.refresh();
                }));
        }
    }
}
