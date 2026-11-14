package com.drehverschluss.packscope.mixin;

import com.drehverschluss.packscope.gui.MenuState;
import com.drehverschluss.packscope.gui.MenuStateHolder;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packscope state of a pack screen. {@code populateLists} runs when the screen opens and after every change of the lists
 * (select, move, folder reload): at its head the state is created once (resource pack screen only, so the entries built
 * there already know it), at its tail the new order is evaluated (Konzept 7.1).
 */
@Mixin(PackSelectionScreen.class)
public abstract class PackSelectionScreenMixin implements MenuStateHolder {
    @Unique
    @Nullable
    private MenuState packscope$menuState;
    @Unique
    private boolean packscope$checked;

    @Inject(method = "populateLists", at = @At("HEAD"))
    private void packscope$beforePopulate(CallbackInfo ci) {
        if (packscope$checked) return;
        packscope$checked = true;
        PackSelectionScreenAccessor screen = (PackSelectionScreenAccessor) this;
        PackSelectionModelAccessor model = (PackSelectionModelAccessor) screen.packscope$getModel();
        packscope$menuState = MenuState.create(model.packscope$getRepository(), screen::packscope$getPackIcon);
    }

    @Inject(method = "populateLists", at = @At("TAIL"))
    private void packscope$afterPopulate(CallbackInfo ci) {
        if (packscope$menuState == null) return;
        PackSelectionModelAccessor model = (PackSelectionModelAccessor) ((PackSelectionScreenAccessor) this).packscope$getModel();
        packscope$menuState.update(model.packscope$getSelected());
    }

    @Nullable
    @Override
    public MenuState packscope$state() {
        return packscope$menuState;
    }
}
