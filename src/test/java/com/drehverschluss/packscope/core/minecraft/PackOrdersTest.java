package com.drehverschluss.packscope.core.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;
import org.junit.jupiter.api.Test;

/** Load order from the repository selection: children of mod_resources, other hidden packs (Konzept 3.1). */
class PackOrdersTest {
    private static Pack pack(String id) {
        return new Pack(new PackLocationInfo(id, Component.literal(id), PackSource.DEFAULT, Optional.empty()),
            new FilePackResources.FileResourcesSupplier(Path.of(id.replace('/', '_') + ".zip")),
            new Pack.Metadata(Component.empty(), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), false),
            new PackSelectionConfig(false, Pack.Position.TOP, false));
    }

    private static List<String> ids(List<PackOrders.Entry> entries) {
        return entries.stream().map(e -> e.pack().getId() + (e.parentId() != null ? "<" + e.parentId() : "") + (e.hidden() ? "#hidden" : ""))
            .toList();
    }

    @Test
    void childrenReplaceTheirParentAndHiddenPacksStay() {
        Pack vanilla = pack("vanilla");
        Pack modA = pack("mod/a");
        Pack modB = pack("mod/b");
        Pack modResources = pack("mod_resources").withChildren(List.of(modA, modB));
        // children are stored hidden; the repository lists them right after their parent (bottom first)
        Pack childA = modResources.getChildren().get(0);
        Pack childB = modResources.getChildren().get(1);
        Pack low = pack("file/Low.zip");
        Pack secret = pack("generated/secret").hidden();
        Pack high = pack("file/High.zip");
        List<Pack> selectedBottomFirst = List.of(vanilla, modResources, childA, childB, low, secret, high);

        List<PackOrders.Entry> entries = PackOrders.entries(selectedBottomFirst);
        assertEquals(List.of("file/High.zip", "generated/secret#hidden", "file/Low.zip", "mod/b<mod_resources#hidden",
            "mod/a<mod_resources#hidden", "vanilla"), ids(entries));
        assertTrue(childA.isHidden());
        assertFalse(entries.get(0).hidden());
    }

    @Test
    void menuOrderGetsTheHiddenPacksBack() {
        Pack vanilla = pack("vanilla");
        Pack low = pack("file/Low.zip");
        Pack secret = pack("generated/secret").hidden();
        Pack high = pack("file/High.zip");
        Pack bottomSecret = pack("generated/bottom").hidden();
        List<Pack> repository = List.of(bottomSecret, vanilla, low, secret, high);
        // the pack screen (PackSelectionModel.selected) has no hidden packs, top first, and the user moved Low up
        List<Pack> menu = List.of(low, high, vanilla);
        List<Pack> full = PackOrders.completeMenuOrder(menu, repository);
        assertEquals(List.of("generated/secret", "file/Low.zip", "file/High.zip", "vanilla", "generated/bottom"),
            full.stream().map(Pack::getId).toList(), "secret stays directly above its neighbour below (Low), even after Low moved");
    }

    @Test
    void menuOrderGetsThePacksHiddenInTheProfileBack() {
        // Phase 4: the vanilla screen adds every selected pack that is in neither of its lists (hidden by mod code or in
        // a Packed Packs profile). The repository is stale while populateLists runs: a pack the user just disabled is
        // still selected there, but it is in the unselected list and must not come back.
        Pack vanilla = pack("vanilla");
        Pack modA = pack("mod/a");
        Pack modResources = pack("mod_resources").withChildren(List.of(modA));
        Pack child = modResources.getChildren().get(0);
        Pack low = pack("file/Low.zip");
        Pack profileHidden = pack("file/ProfileHidden.zip");
        Pack justDisabled = pack("file/JustDisabled.zip");
        Pack high = pack("file/High.zip");
        Pack modHidden = pack("generated/secret").hidden();
        List<Pack> repository = List.of(vanilla, modResources, child, low, profileHidden, justDisabled, high, modHidden);
        List<Pack> menu = List.of(high, low, modResources, vanilla);
        List<Pack> unselected = List.of(justDisabled, pack("file/Other.zip"));
        java.util.Set<Pack> inScreen = new java.util.HashSet<>(menu);
        inScreen.addAll(unselected);
        List<Pack> full = PackOrders.completeMenuOrder(menu, repository, p -> !inScreen.contains(p));
        assertEquals(List.of("generated/secret", "file/High.zip", "file/ProfileHidden.zip", "file/Low.zip", "mod_resources", "vanilla"),
            full.stream().map(Pack::getId).toList(), "profile-hidden above its neighbour Low, no child of mod_resources, no disabled pack");
        // the old rule (Packed Packs screen: only Pack.isHidden()) adds only the pack hidden by mod code
        assertEquals(List.of("generated/secret", "file/High.zip", "file/Low.zip", "mod_resources", "vanilla"),
            PackOrders.completeMenuOrder(menu, repository).stream().map(Pack::getId).toList());
    }
}
