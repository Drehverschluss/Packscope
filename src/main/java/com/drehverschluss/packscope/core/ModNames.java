package com.drehverschluss.packscope.core;

import org.jetbrains.annotations.Nullable;

/**
 * Loader-specific knowledge about mod packs, provided by the loader part (port-friendly: on NeoForge the hidden
 * children of {@code mod_resources} are called {@code mod/<modid>}).
 */
public interface ModNames {
    /** Mod id of a mod resource pack, or null if the pack id does not name a mod. */
    @Nullable
    String modIdForPack(String packId);

    /** Display name of a mod, or the id itself. */
    String modName(String modId);

    ModNames NONE = new ModNames() {
        @Override
        public String modIdForPack(String packId) {
            return null;
        }

        @Override
        public String modName(String modId) {
            return modId;
        }
    };
}
