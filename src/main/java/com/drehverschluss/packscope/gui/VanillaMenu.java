package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.gui.model.StatusLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * The parts of the vanilla pack screen hooks that are not mixin plumbing: icon place, shorter title, drawing, tooltip
 * and opening the detail screen (Konzept 5.1, Phase-1-Bericht 4.2).
 */
public final class VanillaMenu {
    /** Vanilla limit for the title in {@code PackEntry.cacheName}. */
    public static final int VANILLA_TITLE_WIDTH = 157;
    /** Title width in entries with an icon place: icon + 2 px less. */
    public static final int SHORT_TITLE_WIDTH = VANILLA_TITLE_WIDTH - StatusIcons.SIZE - 2;
    public static final int TOOLTIP_WIDTH = 300;

    private VanillaMenu() {}

    /** Same as {@code PackEntry.cacheName}, with another width. */
    public static FormattedCharSequence cacheName(Minecraft mc, Component name, int maxWidth) {
        Font font = mc.font;
        if (font.width(name) > maxWidth) {
            FormattedText text = FormattedText.composite(font.substrByWidth(name, maxWidth - font.width("...")), FormattedText.of("..."));
            return Language.getInstance().getVisualOrder(text);
        }
        return name.getVisualOrderText();
    }

    /** x of the icon: {@code left + width - 3 - (scrollbar ? 7 : 0) - size} (Konzept 5.1). */
    public static int iconX(int left, int width, boolean scrollbar) {
        return left + width - 3 - (scrollbar ? 7 : 0) - StatusIcons.SIZE;
    }

    public static int iconY(int top) {
        return top + 1;
    }

    /**
     * Draws the icon of an entry of the selected list and sets its tooltip when the mouse is over it.
     *
     * @return whether an icon was drawn (only then a click on it opens the details)
     */
    public static boolean render(GuiGraphics g, Screen screen, LiveResults state, String packId, int left, int top, int width,
                                 boolean scrollbar, int mouseX, int mouseY, boolean hovering) {
        StatusLook look = state.look(packId);
        if (look == null) return false;
        int x = iconX(left, width, scrollbar);
        int y = iconY(top);
        StatusIcons.draw(g, look, x, y);
        if (hovering && hit(x, y, mouseX, mouseY)) {
            Minecraft mc = Minecraft.getInstance();
            screen.setTooltipForNextRenderPass(Texts.tooltip(mc.font, state.tooltip(packId), TOOLTIP_WIDTH),
                DefaultTooltipPositioner.INSTANCE, true);
        }
        return true;
    }

    public static boolean hit(int iconX, int iconY, double mouseX, double mouseY) {
        return mouseX >= iconX && mouseX < iconX + StatusIcons.SIZE && mouseY >= iconY && mouseY < iconY + StatusIcons.SIZE;
    }

    /** Opens the detail screen; {@code packId} null = "all packs". */
    public static void openDetails(Screen parent, ResultSource source, @Nullable String packId) {
        Minecraft.getInstance().setScreen(new DetailScreen(parent, source, packId));
    }
}
