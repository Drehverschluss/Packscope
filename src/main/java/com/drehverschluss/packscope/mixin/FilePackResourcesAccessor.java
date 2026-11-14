package com.drehverschluss.packscope.mixin;

import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Zip file and folder inside it of an opened zip pack (packs injected by mods, Phase 5C). */
@Mixin(FilePackResources.class)
public interface FilePackResourcesAccessor {
    @Accessor("zipFileAccess")
    FilePackResources.SharedZipFileAccess packscope$getZipFileAccess();

    @Accessor("prefix")
    String packscope$getPrefix();
}
