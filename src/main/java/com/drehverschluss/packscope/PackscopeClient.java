package com.drehverschluss.packscope;

import com.drehverschluss.packscope.neoforge.PackscopeNeoForge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

// Client entry point. Not loaded on dedicated servers, so client classes are safe here.
@Mod(value = Packscope.MODID, dist = Dist.CLIENT)
public class PackscopeClient {
    public PackscopeClient(IEventBus modEventBus, ModContainer modContainer) {
        PackscopeNeoForge.init(modEventBus, modContainer);
    }
}
