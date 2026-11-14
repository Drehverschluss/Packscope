package com.drehverschluss.packscope.core.minecraft;

import com.drehverschluss.packscope.core.InjectedPacks;
import com.drehverschluss.packscope.core.ModNames;
import com.drehverschluss.packscope.core.PackRef;
import com.drehverschluss.packscope.core.PackRole;
import com.drehverschluss.packscope.core.ScanKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the packs a mod put into the real resource stack ({@code ResourceManager.listPacks()}, the stack of the last
 * resource reload) that are not packs of the repository (Konzept 3.1, Phase 5C). Render thread only.
 */
public final class InjectedStack {
    /** Package of KubeJS; its packs except "[Before Mods]" and "[Last]" go directly above the highest zip pack. */
    static final String KUBEJS_PACKAGE = "dev.latvian.mods.kubejs.";

    private InjectedStack() {}

    /**
     * The injected packs of the current resource stack, bottom first, with their placement and a pack reference.
     * Empty if no mod injects packs.
     */
    public static List<InjectedPacks.Injected> read(PackRepository repository, ModNames mods) {
        List<PackResources> stack;
        try {
            stack = Minecraft.getInstance().getResourceManager().listPacks().toList();
        } catch (RuntimeException e) {
            return List.of();
        }
        Set<String> repositoryIds = new HashSet<>();
        for (Pack p : repository.getAvailablePacks()) {
            repositoryIds.add(p.getId());
            for (Pack c : p.getChildren()) repositoryIds.add(c.getId());
        }
        Set<String> modIds = new HashSet<>();
        Pack modResources = repository.getPack(PackOrders.MOD_RESOURCES);
        if (modResources != null) {
            modIds.add(modResources.getId());
            for (Pack c : modResources.getChildren()) modIds.add(c.getId());
        }
        List<InjectedPacks.StackPack> packs = new ArrayList<>(stack.size());
        Map<String, PackResources> byId = new HashMap<>();
        boolean any = false;
        for (PackResources r : stack) {
            String id = r.packId();
            if (!repositoryIds.contains(id)) {
                any = true;
                byId.put(id, r);
            }
            packs.add(new InjectedPacks.StackPack(id, r instanceof FilePackResources, isKubeJs(r)));
        }
        if (!any) return List.of();
        List<InjectedPacks.Injected> out = new ArrayList<>();
        for (InjectedPacks.Placement p : InjectedPacks.place(packs, repositoryIds, modIds)) {
            PackResources r = byId.get(p.id());
            if (r == null) continue;
            out.add(new InjectedPacks.Injected(ref(r, p.aboveMods(), mods), p));
        }
        return out;
    }

    static boolean isKubeJs(PackResources r) {
        if (r.getClass().getName().startsWith(KUBEJS_PACKAGE)) return true;
        // zip packs from kubejs/assets/*.zip are plain FilePackResources with KnownPack("kubejs", ...)
        return r.knownPackInfo().map(k -> "kubejs".equals(k.namespace())).orElse(false);
    }

    private static PackRef ref(PackResources r, boolean aboveMods, ModNames mods) {
        String id = r.packId();
        String title = r.location().title().getString();
        if (title.isBlank()) title = id;
        String modId = injector(r, mods);
        String modName = modId == null ? null : mods.modName(modId);
        if (aboveMods) {
            return new PackRef(id, title, PackRole.RESOURCE_PACK, ScanKind.FALLBACK, null, List.of(), null, null, null,
                () -> new PackResourcesContent(r, false), false, true, modName);
        }
        return new PackRef(id, title, PackRole.MOD, ScanKind.LOOKUP, null, List.of(), modId == null ? id : modId,
            modName == null ? title : modName, null, () -> new PackResourcesContent(r, false), false, true, modName);
    }

    /** Mod that put the pack there: by the class of the pack, else by the namespace of its known-pack info. */
    @Nullable
    private static String injector(PackResources r, ModNames mods) {
        String byClass = r.getClass().getName().startsWith("net.minecraft.") ? null : mods.modIdForClass(r.getClass());
        if (byClass != null) return byClass;
        return r.knownPackInfo().map(k -> k.namespace()).filter(ns -> !"minecraft".equals(ns) && !"neoforge".equals(ns))
            .orElse(null);
    }
}
