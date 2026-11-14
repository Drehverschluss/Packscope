package com.drehverschluss.packscope.compat.packedpacks;

import com.drehverschluss.packscope.gui.StatusIcons;
import com.drehverschluss.packscope.gui.VanillaMenu;
import com.drehverschluss.packscope.gui.model.StatusLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * "Conflicts" button in the header of the Packed Packs screen ({@code InitializeLayoutEvent}, position
 * {@code AFTER_TITLE}): opens the detail screen in the "all packs" view. The header is a flex layout that places it
 * between the profile title and the Packed Packs buttons, so it never covers them; in a narrow screen it is only an icon
 * as high and wide as the Packed Packs buttons.
 */
final class ConflictsButton extends Button {
    /** Height of the Packed Packs header buttons. */
    static final int HEIGHT = 20;
    /** Below this screen width (GUI pixels) only the icon is shown. */
    static final int NARROW = 480;

    private final boolean iconOnly;

    private ConflictsButton(int width, boolean iconOnly, Component label, OnPress onPress) {
        super(0, 0, width, HEIGHT, iconOnly ? Component.empty() : label, onPress, DEFAULT_NARRATION);
        this.iconOnly = iconOnly;
        setTooltip(Tooltip.create(Component.translatable("packscope.button.conflicts.tooltip")));
    }

    static ConflictsButton create(PackedPacksState state) {
        Font font = Minecraft.getInstance().font;
        Component label = Component.translatable("packscope.button.conflicts");
        boolean icon = state.screen().width < NARROW;
        int w = icon ? HEIGHT : font.width(label) + 12;
        return new ConflictsButton(w, icon, label, b -> VanillaMenu.openDetails(state.screen(), state, null));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(g, mouseX, mouseY, partialTick);
        if (iconOnly) {
            StatusIcons.draw(g, StatusLook.WINS_AND_LOSES, getX() + (getWidth() - StatusIcons.SIZE) / 2,
                getY() + (getHeight() - StatusIcons.SIZE) / 2);
        }
    }
}
