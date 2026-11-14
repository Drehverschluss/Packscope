package com.drehverschluss.packscope.mixin;

import java.util.List;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Current order in the pack screen ({@code selected}, index 0 = top), the other list and the repository (resource or data packs). */
@Mixin(PackSelectionModel.class)
public interface PackSelectionModelAccessor {
    @Accessor("selected")
    List<Pack> packscope$getSelected();

    @Accessor("unselected")
    List<Pack> packscope$getUnselected();

    @Accessor("repository")
    PackRepository packscope$getRepository();
}
