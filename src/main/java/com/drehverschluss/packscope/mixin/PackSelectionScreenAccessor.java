package com.drehverschluss.packscope.mixin;

import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Model and pack icons of the pack screen. */
@Mixin(PackSelectionScreen.class)
public interface PackSelectionScreenAccessor {
    @Accessor("model")
    PackSelectionModel packscope$getModel();

    @Invoker("getPackIcon")
    ResourceLocation packscope$getPackIcon(Pack pack);
}
