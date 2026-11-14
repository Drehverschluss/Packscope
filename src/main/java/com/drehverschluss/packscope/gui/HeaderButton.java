package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.gui.model.StatusLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Conflicts" button in the top right corner of the vanilla resource pack screen (Konzept 7.1): opens the detail screen
 * in the "all packs" view. It places itself on every frame so it never covers the title or the "Drag and drop ..."
 * line, also after a resize: full height next to both lines if there is room, else a low button next to the title only,
 * else only an icon.
 */
public final class HeaderButton extends Button {
    static final int MARGIN = 4;
    /** Header rows of {@code PackSelectionScreen} (layout: header 33 px, two 9 px lines with 5 px spacing, centred). */
    static final int TITLE_Y = 5;
    static final int DROP_Y = 19;

    private final Screen screen;
    private final Component label;
    private boolean iconOnly;

    public HeaderButton(Screen screen, MenuState state) {
        super(0, 0, 60, 20, Component.translatable("packscope.button.conflicts"),
            b -> VanillaMenu.openDetails(screen, state, null), DEFAULT_NARRATION);
        this.screen = screen;
        this.label = Component.translatable("packscope.button.conflicts");
        setTooltip(Tooltip.create(Component.translatable("packscope.button.conflicts.tooltip")));
        place();
    }

    /** Position and size for the current screen size. */
    public void place() {
        Font font = Minecraft.getInstance().font;
        int textW = Math.max(40, font.width(label) + 12);
        int titleRight = screen.width / 2 + font.width(screen.getTitle()) / 2;
        int dropRight = screen.width / 2 + font.width(Component.translatable("pack.dropInfo")) / 2;
        int right = screen.width - MARGIN;
        if (right - textW >= Math.max(titleRight, dropRight) + MARGIN) {
            set(right - textW, 6, textW, 20, false);
        } else if (right - textW >= titleRight + MARGIN) {
            // low button: ends above the "Drag and drop" line
            set(right - textW, 2, textW, DROP_Y - 3 - 2, false);
        } else {
            set(right - 14, 2, 14, 14, true);
        }
    }

    private void set(int x, int y, int w, int h, boolean icon) {
        setX(x);
        setY(y);
        setWidth(w);
        setHeight(h);
        iconOnly = icon;
        setMessage(icon ? Component.empty() : label);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        place();
        super.renderWidget(g, mouseX, mouseY, partialTick);
        if (iconOnly) {
            StatusIcons.draw(g, StatusLook.WINS_AND_LOSES, getX() + (getWidth() - StatusIcons.SIZE) / 2,
                getY() + (getHeight() - StatusIcons.SIZE) / 2);
        }
    }
}
