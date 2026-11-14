package com.drehverschluss.packscope.mixin;

import com.drehverschluss.packscope.gui.MenuState;
import com.drehverschluss.packscope.gui.MenuStateHolder;
import com.drehverschluss.packscope.gui.VanillaMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.client.gui.screens.packs.TransferableSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Icon in the title row of the entries of the selected list (Konzept 5.1, 7.1): shorter title, icon + tooltip, click on
 * the icon opens the detail screen. Arrows on the pack icon, selecting the row and right clicks stay as they are.
 */
@Mixin(TransferableSelectionList.PackEntry.class)
public abstract class PackEntryMixin {
    @Shadow
    @Final
    private TransferableSelectionList parent;
    @Shadow
    @Final
    private PackSelectionModel.Entry pack;
    @Shadow
    @Final
    protected Minecraft minecraft;
    @Shadow
    @Final
    @Mutable
    private FormattedCharSequence nameDisplayCache;
    @Shadow
    @Final
    @Mutable
    private FormattedCharSequence incompatibleNameDisplayCache;

    @Unique
    private boolean packscope$iconShown;
    @Unique
    private int packscope$iconX;
    @Unique
    private int packscope$iconY;

    @Unique
    @Nullable
    private MenuState packscope$state() {
        if (!pack.isSelected()) return null;
        Object screen = ((TransferableSelectionListAccessor) parent).packscope$getScreen();
        return screen instanceof MenuStateHolder h ? h.packscope$state() : null;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void packscope$shortenTitle(Minecraft mc, TransferableSelectionList list, PackSelectionModel.Entry entry, CallbackInfo ci) {
        MenuState state = packscope$state();
        if (state == null || !state.isIconEntry(entry.getId())) return;
        nameDisplayCache = VanillaMenu.cacheName(mc, entry.getTitle(), VanillaMenu.SHORT_TITLE_WIDTH);
        incompatibleNameDisplayCache = VanillaMenu.cacheName(mc, Component.translatable("pack.incompatible"), VanillaMenu.SHORT_TITLE_WIDTH);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void packscope$renderIcon(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                                      boolean hovering, float partialTick, CallbackInfo ci) {
        packscope$iconShown = false;
        MenuState state = packscope$state();
        if (state == null) return;
        boolean scrollbar = ((AbstractSelectionListInvoker) parent).packscope$scrollbarVisible();
        packscope$iconShown = VanillaMenu.render(g, ((TransferableSelectionListAccessor) parent).packscope$getScreen(), state,
            pack.getId(), left, top, width, scrollbar, mouseX, mouseY, hovering);
        packscope$iconX = VanillaMenu.iconX(left, width, scrollbar);
        packscope$iconY = VanillaMenu.iconY(top);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void packscope$clickIcon(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || !packscope$iconShown) return;
        if (!VanillaMenu.hit(packscope$iconX, packscope$iconY, mouseX, mouseY)) return;
        MenuState state = packscope$state();
        if (state == null) return;
        minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        VanillaMenu.openDetails(((TransferableSelectionListAccessor) parent).packscope$getScreen(), state, pack.getId());
        cir.setReturnValue(true);
    }
}
