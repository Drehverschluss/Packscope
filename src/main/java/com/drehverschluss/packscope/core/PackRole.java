package com.drehverschluss.packscope.core;

/** What a pack in the load order is, as far as conflicts are concerned. */
public enum PackRole {
    /** A normal resource pack (also built-in packs like Programmer Art and visible packs added by mods). */
    RESOURCE_PACK,
    /** Hidden mod resources (on NeoForge the children {@code mod/<modid>} of {@code mod_resources}). Info only. */
    MOD,
    /** The vanilla resources. Info only. */
    VANILLA
}
