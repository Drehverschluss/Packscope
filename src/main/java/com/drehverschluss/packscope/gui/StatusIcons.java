package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.gui.model.StatusLook;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Placeholder status icons (own 9×9 GUI sprites, final graphics in Phase 6). */
public final class StatusIcons {
    public static final int SIZE = 9;

    private StatusIcons() {}

    public static ResourceLocation sprite(StatusLook look) {
        return ResourceLocation.fromNamespaceAndPath("packscope", "status/" + look.sprite);
    }

    public static void draw(GuiGraphics g, StatusLook look, int x, int y) {
        g.blitSprite(sprite(look), x, y, SIZE, SIZE);
    }
}
