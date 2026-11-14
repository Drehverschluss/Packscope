package com.drehverschluss.packscope;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

// Common entry point. Also loaded on dedicated servers: never touch client classes from here.
@Mod(Packscope.MODID)
public class Packscope {
    public static final String MODID = "packscope";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Packscope(IEventBus modEventBus, ModContainer modContainer) {
    }
}
