package com.drehverschluss.packscope.core.minecraft;

import com.drehverschluss.packscope.core.ModNames;
import com.drehverschluss.packscope.core.PackContent;
import com.drehverschluss.packscope.core.PackOrder;
import com.drehverschluss.packscope.core.PackRef;
import com.drehverschluss.packscope.core.PackRole;
import com.drehverschluss.packscope.core.ScanKind;
import com.drehverschluss.packscope.mixin.FileResourcesSupplierAccessor;
import com.drehverschluss.packscope.mixin.PackAccessor;
import com.drehverschluss.packscope.mixin.PathResourcesSupplierAccessor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the {@link PackOrder} snapshot from the game's packs. Call on the render thread only: it reads the
 * repository and the pack objects, which Packed Packs may replace from a background thread.
 */
public final class PackOrders {
    private PackOrders() {}

    /** The applied selection of the repository (what the game currently uses), top pack first. */
    public static PackOrder fromRepository(PackRepository repository, ModNames mods) {
        List<Pack> topFirst = new ArrayList<>();
        for (Pack p : repository.getSelectedPacks()) {
            if (!p.isHidden()) topFirst.addFirst(p);
        }
        return fromTopFirst(topFirst, mods);
    }

    /**
     * Expands packs with children (on NeoForge {@code mod_resources}) into their children at their place. In the load
     * order the children sit directly above their (empty) parent, the last child highest.
     */
    public static PackOrder fromTopFirst(List<Pack> topFirst, ModNames mods) {
        List<PackRef> refs = new ArrayList<>();
        for (Pack pack : topFirst) {
            List<Pack> children = pack.getChildren();
            if (children.isEmpty()) {
                refs.add(ref(pack, null, mods));
            } else {
                for (int i = children.size() - 1; i >= 0; i--) refs.add(ref(children.get(i), pack.getId(), mods));
            }
        }
        return new PackOrder(refs);
    }

    static PackRef ref(Pack pack, @Nullable String parentId, ModNames mods) {
        String id = pack.getId();
        String title = pack.getTitle().getString();
        PackAccessor acc = (PackAccessor) pack;
        List<String> overlays = acc.packscope$getMetadata().overlays();
        PackContent.Opener opener = () -> new PackResourcesContent(pack.open());
        if (parentId != null) {
            String modId = mods.modIdForPack(id);
            return new PackRef(id, title, PackRole.MOD, ScanKind.LOOKUP, null, overlays, modId,
                modId == null ? null : mods.modName(modId), parentId, opener);
        }
        if (BuiltInPackSource.VANILLA_ID.equals(id)) {
            return new PackRef(id, title, PackRole.VANILLA, ScanKind.LOOKUP, null, overlays, null, null, null, opener);
        }
        Pack.ResourcesSupplier supplier = acc.packscope$getResources();
        if (supplier instanceof FilePackResources.FileResourcesSupplier) {
            Path path = ((FileResourcesSupplierAccessor) supplier).packscope$getContent().toPath().toAbsolutePath().normalize();
            return new PackRef(id, title, PackRole.RESOURCE_PACK, ScanKind.ZIP, path, overlays, null, null, null, opener);
        }
        if (supplier instanceof PathPackResources.PathResourcesSupplier) {
            Path path = ((PathResourcesSupplierAccessor) supplier).packscope$getContent().toAbsolutePath().normalize();
            return new PackRef(id, title, PackRole.RESOURCE_PACK, ScanKind.FOLDER, path, overlays, null, null, null, opener);
        }
        return new PackRef(id, title, PackRole.RESOURCE_PACK, ScanKind.FALLBACK, null, overlays, null, null, null, opener);
    }
}
