package com.drehverschluss.packscope.mixin;

import java.io.File;
import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Zip file behind a zip pack. */
@Mixin(FilePackResources.FileResourcesSupplier.class)
public interface FileResourcesSupplierAccessor {
    @Accessor("content")
    File packscope$getContent();
}
