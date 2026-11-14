package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.core.IntendedStore;
import com.drehverschluss.packscope.core.ScanResult;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * What the detail screen needs from the pack screen it was opened from: the live result of the current order, a way to
 * evaluate again after a marking, pack icons and the report export. The vanilla screen uses {@link MenuState}; the
 * Packed Packs screen (Phase 4) can bring its own.
 */
public interface ResultSource {
    /** Newest result for the order in the pack screen, or the one before while it is being evaluated again; null at first. */
    @Nullable
    ScanResult latest();

    /** An evaluation for the current order is still running. */
    boolean pending();

    /** Changes whenever {@link #latest()} changes (the detail screen rebuilds its rows then). */
    int generation();

    /** Evaluate the current order again (after marking, without a new scan). */
    void refresh();

    IntendedStore intended();

    /** Icon of a pack in the pack screen, or null. */
    @Nullable
    ResourceLocation icon(String packId);

    /** Whether the order in the pack screen is the applied one (else the report says so). */
    boolean orderApplied();

    /** Writes both report files with the order in the pack screen; returns the Markdown file. */
    CompletableFuture<Path> exportReport();
}
