package com.drehverschluss.packscope.mixin;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Whether a list shows its scrollbar (the icon moves left of it). */
@Mixin(AbstractSelectionList.class)
public interface AbstractSelectionListInvoker {
    @Invoker("scrollbarVisible")
    boolean packscope$scrollbarVisible();
}
