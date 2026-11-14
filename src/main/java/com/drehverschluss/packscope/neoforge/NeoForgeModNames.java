package com.drehverschluss.packscope.neoforge;

import com.drehverschluss.packscope.core.ModNames;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModContainer;

/** NeoForge rule: the hidden mod packs are called {@code mod/<modid>} (several mods of one jar joined with ","). */
public final class NeoForgeModNames implements ModNames {
    @Override
    public String modIdForPack(String packId) {
        if (!packId.startsWith("mod/")) return null;
        String ids = packId.substring(4);
        int comma = ids.indexOf(',');
        String first = comma < 0 ? ids : ids.substring(0, comma);
        return first.isEmpty() ? null : first;
    }

    @Override
    public String modName(String modId) {
        return ModList.get().getModContainerById(modId).map(ModContainer::getModInfo).map(i -> i.getDisplayName()).orElse(modId);
    }
}
