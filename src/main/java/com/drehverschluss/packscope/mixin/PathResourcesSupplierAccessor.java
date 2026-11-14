package com.drehverschluss.packscope.mixin;

import java.nio.file.Path;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Root folder behind a folder pack (also mod jars, which live on their own file system). */
@Mixin(PathPackResources.PathResourcesSupplier.class)
public interface PathResourcesSupplierAccessor {
    @Accessor("content")
    Path packscope$getContent();
}
