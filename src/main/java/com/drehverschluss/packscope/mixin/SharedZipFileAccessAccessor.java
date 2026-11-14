package com.drehverschluss.packscope.mixin;

import java.io.File;
import net.minecraft.server.packs.FilePackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The zip file behind {@link FilePackResources}. */
@Mixin(FilePackResources.SharedZipFileAccess.class)
public interface SharedZipFileAccessAccessor {
    @Accessor("file")
    File packscope$getFile();
}
