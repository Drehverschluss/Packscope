package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.core.minecraft.PackOrders;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.jetbrains.annotations.Nullable;

/**
 * Live evaluation for one open vanilla resource pack screen (Konzept 5.1 "Live", 7.1). After every
 * {@code populateLists} the order in the screen is taken and completed with the selected packs the screen does not list
 * (hidden by mod code, or hidden in a Packed Packs profile). The rest is {@link LiveResults}.
 */
public final class MenuState extends LiveResults {
    private final Function<Pack, ResourceLocation> icons;
    private List<Pack> visibleTopFirst = List.of();
    private List<Pack> completeTopFirst = List.of();

    public MenuState(UiPlatform platform, PackRepository repository, Function<Pack, ResourceLocation> icons) {
        super(platform, repository);
        this.icons = icons;
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

    /**
     * The lists of the screen changed ({@code populateLists}).
     *
     * <p>{@code PackSelectionModel} writes a (de)selection into the repository only <b>after</b> {@code populateLists}
     * and a move not at all, so the repository order is stale here. Only packs that are in neither list of the screen
     * are taken from it (they cannot have been changed in the screen); their place is the neighbour rule of
     * {@link PackOrders#completeMenuOrder}.
     *
     * @param selectedTopFirst   {@code PackSelectionModel.selected} (index 0 = top)
     * @param unselected         {@code PackSelectionModel.unselected}
     * @param model              the model (its entry lists leave out packs hidden in a Packed Packs profile)
     */
    public void update(List<Pack> selectedTopFirst, List<Pack> unselected, PackSelectionModel model) {
        visibleTopFirst = List.copyOf(selectedTopFirst);
        Set<Pack> inScreen = new HashSet<>(selectedTopFirst);
        inScreen.addAll(unselected);
        completeTopFirst = PackOrders.completeMenuOrder(visibleTopFirst, repository.getSelectedPacks(), p -> !inScreen.contains(p));
        Set<String> hidden = hiddenInScreen(model, repository);
        for (Pack p : completeTopFirst) if (!visibleTopFirst.contains(p)) hidden.add(p.getId());
        setOrder(completeTopFirst, hidden);
    }

    @Nullable
    @Override
    public ResourceLocation icon(String packId) {
        Pack p = menuPack(packId);
        return p == null ? null : icons.apply(p);
    }

    /** The screen order (without the packs hidden by mod code) against the applied ones in options.txt. */
    @Override
    public boolean orderApplied() {
        return orderApplied(completeTopFirst);
    }
}
