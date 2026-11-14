package com.drehverschluss.packscope.mixin;

import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.packs.TransferableSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The pack screen a pack list belongs to. */
@Mixin(TransferableSelectionList.class)
public interface TransferableSelectionListAccessor {
    @Accessor("screen")
    PackSelectionScreen packscope$getScreen();
}
