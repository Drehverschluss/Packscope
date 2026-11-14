package com.drehverschluss.packscope.core.minecraft;

import com.drehverschluss.packscope.core.InjectedPacks;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
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
    /** NeoForge pack that holds the mod packs as children ({@code mod/<modid>}). Only its children are mods (Phase 5C). */
    public static final String MOD_RESOURCES = "mod_resources";

    private PackOrders() {}

    /** One entry of the load order, top first: the pack, the pack it was expanded from (or null), hidden flag. */
    public record Entry(Pack pack, @Nullable String parentId, boolean hidden) {}

    /**
     * The applied selection of the repository (what the game currently uses), top pack first.
     *
     * @param languages language files that count (see {@link #loadedLanguages()}), empty = all
     */
    public static PackOrder fromRepository(PackRepository repository, ModNames mods, List<String> languages) {
        List<PackRef> refs = new ArrayList<>();
        for (Entry e : entries(repository.getSelectedPacks())) refs.add(ref(e.pack(), e.parentId(), e.hidden(), mods));
        // packs injected by mods sit where the real stack has them (Phase 5C)
        return new PackOrder(InjectedPacks.insert(refs, InjectedStack.read(repository, mods)), languages);
    }

    /**
     * Load order from the repository selection ({@code bottom first}, as {@code PackRepository.getSelectedPacks()}):
     * <ul>
     *     <li>packs with children (NeoForge {@code mod_resources}, also packs such as {@code eatinganimations_compat})
     *     are replaced by their children at their place (in the load order the children sit directly above their empty
     *     parent, the last child highest). Only the children of {@code mod_resources} are mods, hidden from the pack
     *     screen; children of other packs are resource packs that belong to their parent (Phase 5C);</li>
     *     <li>other hidden packs ({@code Pack.isHidden()}, only possible through mod code) are still loaded by the game
     *     ({@code openAllSelected}) and stay in, marked as hidden.</li>
     * </ul>
     * Pure function on the pack objects, testable without mixins.
     */
    public static List<Entry> entries(Collection<Pack> selectedBottomFirst) {
        Set<Pack> children = new HashSet<>();
        for (Pack p : selectedBottomFirst) children.addAll(p.getChildren());
        List<Entry> topFirst = new ArrayList<>();
        for (Pack p : selectedBottomFirst) {
            if (children.contains(p)) continue; // expanded through its parent
            List<Entry> part = new ArrayList<>();
            if (p.getChildren().isEmpty()) {
                part.add(new Entry(p, null, p.isHidden()));
            } else {
                boolean mods = MOD_RESOURCES.equals(p.getId());
                for (int i = p.getChildren().size() - 1; i >= 0; i--) part.add(new Entry(p.getChildren().get(i), p.getId(), mods));
            }
            topFirst.addAll(0, part);
        }
        return topFirst;
    }

    /**
     * Load order from a pack screen list (top first, without hidden packs, e.g. {@code PackSelectionModel.selected}).
     * Packs with children are expanded; hidden packs of the repository that are not children are inserted where they
     * sit in the repository selection (directly above the next visible pack below them), see {@link #completeMenuOrder}.
     */
    public static PackOrder fromTopFirst(List<Pack> topFirst, ModNames mods, List<String> languages) {
        return fromTopFirst(topFirst, Set.of(), mods, languages);
    }

    /**
     * Like {@link #fromTopFirst(List, ModNames, List)}; packs in {@code hiddenIds} (no entry in the pack screen, e.g.
     * hidden in a Packed Packs profile) are marked as hidden like the packs hidden by mod code.
     */
    public static PackOrder fromTopFirst(List<Pack> topFirst, Set<String> hiddenIds, ModNames mods, List<String> languages) {
        return fromTopFirst(topFirst, hiddenIds, mods, languages, List.of());
    }

    /**
     * Like {@link #fromTopFirst(List, Set, ModNames, List)}, with the packs injected by mods put where they belong for
     * this order ({@link InjectedPacks#insert}, Phase 5C).
     */
    public static PackOrder fromTopFirst(List<Pack> topFirst, Set<String> hiddenIds, ModNames mods, List<String> languages,
                                         List<InjectedPacks.Injected> injected) {
        List<PackRef> refs = new ArrayList<>();
        for (Pack pack : topFirst) {
            List<Pack> ch = pack.getChildren();
            if (ch.isEmpty()) {
                refs.add(ref(pack, null, pack.isHidden() || hiddenIds.contains(pack.getId()), mods));
            } else {
                boolean modPacks = MOD_RESOURCES.equals(pack.getId());
                for (int i = ch.size() - 1; i >= 0; i--) refs.add(ref(ch.get(i), pack.getId(), modPacks, mods));
            }
        }
        return new PackOrder(InjectedPacks.insert(refs, injected), languages);
    }

    /**
     * Adds the hidden packs of the repository selection (which have no entry in the pack screen) to a pack screen order
     * (top first): each hidden pack goes directly above the nearest visible pack that lies below it in the repository.
     * Hidden packs below every visible pack go to the bottom. Children of other packs are not added (expanded later).
     */
    public static List<Pack> completeMenuOrder(List<Pack> menuTopFirst, Collection<Pack> repositoryBottomFirst) {
        return completeMenuOrder(menuTopFirst, repositoryBottomFirst, Pack::isHidden);
    }

    /**
     * Adds every pack of the repository selection that {@code missing} accepts and that is not in the screen order yet
     * (top first), with the neighbour rule of {@link #completeMenuOrder(List, Collection)}: directly above the nearest
     * pack below it in the repository that is in the order; at the bottom if there is none. Children of other packs are
     * never added (expanded later). The vanilla screen accepts every pack that is in neither of its lists (hidden by mod
     * code or in a Packed Packs profile, Phase 4); the Packed Packs screen only {@code Pack.isHidden()} packs, because
     * its repository selection is the applied one, not the one in the screen.
     */
    public static List<Pack> completeMenuOrder(List<Pack> menuTopFirst, Collection<Pack> repositoryBottomFirst,
                                               Predicate<Pack> missing) {
        Set<Pack> children = new HashSet<>();
        for (Pack p : repositoryBottomFirst) children.addAll(p.getChildren());
        List<Pack> result = new ArrayList<>(menuTopFirst);
        List<Pack> repo = new ArrayList<>(repositoryBottomFirst);
        for (int i = 0; i < repo.size(); i++) {
            Pack p = repo.get(i);
            if (children.contains(p) || result.contains(p) || !missing.test(p)) continue;
            int insertAt = result.size();
            for (int j = i - 1; j >= 0; j--) {
                int pos = result.indexOf(repo.get(j));
                if (pos >= 0) {
                    insertAt = pos;
                    break;
                }
            }
            result.add(insertAt, p);
        }
        return result;
    }

    /**
     * Language files the game loads: {@code en_us} and the selected language (like {@code LanguageManager}: the
     * selected one only if it exists). Render thread.
     */
    public static List<String> loadedLanguages() {
        Minecraft mc = Minecraft.getInstance();
        String selected = mc.getLanguageManager().getSelected();
        if (selected == null || selected.equals("en_us") || mc.getLanguageManager().getLanguage(selected) == null) {
            return List.of("en_us");
        }
        return List.of("en_us", selected);
    }

    static PackRef ref(Pack pack, @Nullable String parentId, boolean hidden, ModNames mods) {
        String id = pack.getId();
        String title = pack.getTitle().getString();
        PackAccessor acc = (PackAccessor) pack;
        List<String> overlays = acc.packscope$getMetadata().overlays();
        PackContent.Opener opener = () -> new PackResourcesContent(pack.open());
        if (MOD_RESOURCES.equals(parentId)) {
            String modId = mods.modIdForPack(id);
            return new PackRef(id, title, PackRole.MOD, ScanKind.LOOKUP, null, overlays, modId,
                modId == null ? null : mods.modName(modId), parentId, opener, true);
        }
        if (BuiltInPackSource.VANILLA_ID.equals(id)) {
            return new PackRef(id, title, PackRole.VANILLA, ScanKind.LOOKUP, null, overlays, null, null, null, opener, hidden);
        }
        Pack.ResourcesSupplier supplier = acc.packscope$getResources();
        if (supplier instanceof FilePackResources.FileResourcesSupplier) {
            Path path = ((FileResourcesSupplierAccessor) supplier).packscope$getContent().toPath().toAbsolutePath().normalize();
            return new PackRef(id, title, PackRole.RESOURCE_PACK, ScanKind.ZIP, path, overlays, null, null, parentId, opener, hidden);
        }
        if (supplier instanceof PathPackResources.PathResourcesSupplier) {
            Path path = ((PathResourcesSupplierAccessor) supplier).packscope$getContent().toAbsolutePath().normalize();
            return new PackRef(id, title, PackRole.RESOURCE_PACK, ScanKind.FOLDER, path, overlays, null, null, parentId, opener, hidden);
        }
        // other suppliers (built-in packs of mods, dynamic packs): the scan thread opens the pack and scans it exactly if it
        // turns out to be a folder or zip (also inside a mod jar), else through the fallback listing (Phase 5C)
        return new PackRef(id, title, PackRole.RESOURCE_PACK, ScanKind.FALLBACK, null, overlays, null, null, parentId, opener, hidden);
    }
}
