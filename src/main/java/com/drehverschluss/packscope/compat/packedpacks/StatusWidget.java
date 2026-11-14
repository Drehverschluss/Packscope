package com.drehverschluss.packscope.compat.packedpacks;

import com.drehverschluss.packscope.gui.StatusIcons;
import com.drehverschluss.packscope.gui.Texts;
import com.drehverschluss.packscope.gui.VanillaMenu;
import com.drehverschluss.packscope.gui.model.StatusLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;

/**
 * Status icon in a pack entry of the Packed Packs screen (Konzept 5.1): same icon and tooltip as in the vanilla screen,
 * a left click opens the detail screen. It reads the live state on every frame (so it follows moves without a rebuild)
 * and is only drawn and clickable while the pack is active and has an icon.
 */
final class StatusWidget extends AbstractWidget {
    private final PackedPacksState state;
    private final String packId;

    StatusWidget(PackedPacksState state, String packId, Component packTitle) {
        super(0, 0, StatusIcons.SIZE, StatusIcons.SIZE, packTitle);
        this.state = state;
        this.packId = packId;
        active = false;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        state.poll();
        StatusLook look = state.enabled() && state.isSelected(packId) ? state.look(packId) : null;
        active = look != null;
        if (look == null) return;
        StatusIcons.draw(g, look, getX(), getY());
        if (isHovered()) {
            state.screen().setTooltipForNextRenderPass(
                Texts.tooltip(Minecraft.getInstance().font, state.tooltip(packId), VanillaMenu.TOOLTIP_WIDTH),
                DefaultTooltipPositioner.INSTANCE, true);
        }
    }

    /** Left click only (right clicks go to the Packed Packs context menu). */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != 0 || !clicked(mouseX, mouseY)) return false;
        playDownSound(Minecraft.getInstance().getSoundManager());
        VanillaMenu.openDetails(state.screen(), state, packId);
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("packscope.narration.icon", getMessage()));
    }
}
