package com.drehverschluss.packscope.mixin;

import java.nio.file.Path;
import net.minecraft.server.packs.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Root folder of an opened folder pack (also a folder inside a mod jar, Phase 5C). */
@Mixin(PathPackResources.class)
public interface PathPackResourcesAccessor {
    @Accessor("root")
    Path packscope$getRoot();
}
