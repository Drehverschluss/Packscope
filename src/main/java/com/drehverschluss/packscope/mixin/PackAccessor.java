package com.drehverschluss.packscope.mixin;

import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the supplier (file/folder of the pack) and the metadata (active overlays) of a pack. */
@Mixin(Pack.class)
public interface PackAccessor {
    @Accessor("resources")
    Pack.ResourcesSupplier packscope$getResources();

    @Accessor("metadata")
    Pack.Metadata packscope$getMetadata();
}
