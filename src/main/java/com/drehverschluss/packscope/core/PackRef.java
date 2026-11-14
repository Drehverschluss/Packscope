package com.drehverschluss.packscope.core;

import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Snapshot of one active pack, taken on the render thread. Everything the scan thread needs, without touching the
 * repository again.
 *
 * @param id       pack id ({@code file/Name.zip}, {@code mod/<modid>}, {@code vanilla}, ...)
 * @param title    display name
 * @param role     resource pack, mod or vanilla
 * @param kind     how the files are found
 * @param path     zip file or folder (absolute, normalized) for {@link ScanKind#ZIP}/{@link ScanKind#FOLDER}, else null
 * @param overlays active overlay folders, in pack.mcmeta order (a later overlay wins)
 * @param modId    mod id for {@link PackRole#MOD}, else null
 * @param modName  display name of the mod, else null
 * @param parentId pack this one was expanded from (e.g. {@code mod_resources}), else null
 * @param opener   pack API access for {@link ScanKind#LOOKUP}/{@link ScanKind#FALLBACK}, else null
 * @param hidden   selected but hidden from the pack screen ({@code Pack.isHidden()}, set by mod code), still loaded
 */
public record PackRef(String id, String title, PackRole role, ScanKind kind, @Nullable Path path, List<String> overlays,
                      @Nullable String modId, @Nullable String modName, @Nullable String parentId,
                      @Nullable PackContent.Opener opener, boolean hidden) {
    public PackRef {
        overlays = List.copyOf(overlays);
    }

    public PackRef(String id, String title, PackRole role, ScanKind kind, @Nullable Path path, List<String> overlays,
                   @Nullable String modId, @Nullable String modName, @Nullable String parentId, @Nullable PackContent.Opener opener) {
        this(id, title, role, kind, path, overlays, modId, modName, parentId, opener, false);
    }

    public boolean isResourcePack() {
        return role == PackRole.RESOURCE_PACK;
    }

    /** Name used for mod/vanilla info: mod name, "Minecraft" for vanilla, else the title. */
    public String ownerName() {
        if (role == PackRole.VANILLA) return "Minecraft";
        if (modName != null) return modName;
        return title;
    }

    /** Key used to group mod/vanilla info: mod id, "minecraft" for vanilla. */
    public String ownerKey() {
        if (role == PackRole.VANILLA) return "minecraft";
        if (modId != null) return modId;
        return id;
    }
}
