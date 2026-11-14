package com.drehverschluss.packscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drehverschluss.packscope.core.InjectedPacks.Anchor;
import com.drehverschluss.packscope.core.InjectedPacks.Injected;
import com.drehverschluss.packscope.core.InjectedPacks.Placement;
import com.drehverschluss.packscope.core.InjectedPacks.StackPack;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 5C: packs injected by mods, children of other packs, built-in packs in mod jars, grouped warnings. */
class InjectedAndJarTest {
    @TempDir
    Path tmp;

    // ------------------------------------------------------------------------------------------------ injected packs

    /** The real stack of the pack in 5B, shortened (bottom first), with KubeJS like in its ClientAssetPacks.inject. */
    private static List<StackPack> kubeJsStack() {
        return List.of(
            new StackPack("vanilla", false, false),
            new StackPack("KubeJS Virtual Resource Pack [Before Mods, assets]", false, true),
            new StackPack("generated/fragmentum_layer", false, false),
            new StackPack("mod_resources", false, false),
            new StackPack("mod/neoforge", false, false),
            new StackPack("mod/kubejs", false, false),
            new StackPack("file/Ashen_16x.zip", true, false),
            new StackPack("file/FreshColonists-v3.0.zip", true, false),
            new StackPack("KubeJS Virtual Resource Pack [Internal, assets]", false, true),
            new StackPack("KubeJS Virtual Resource Pack [After Mods, assets]", false, true),
            new StackPack("KubeJS File Resource Pack [assets]", false, true),
            new StackPack("mod/runelessrpg:resourcepacks/spell_infinity_text", false, false),
            new StackPack("quark-emote-pack", false, false),
            new StackPack("KubeJS Virtual Resource Pack [Last, assets]", false, true));
    }

    private static final Set<String> REPO = Set.of("vanilla", "generated/fragmentum_layer", "mod_resources", "mod/neoforge", "mod/kubejs",
        "file/Ashen_16x.zip", "file/FreshColonists-v3.0.zip", "mod/runelessrpg:resourcepacks/spell_infinity_text", "quark-emote-pack");
    private static final Set<String> MODS = Set.of("mod_resources", "mod/neoforge", "mod/kubejs");

    @Test
    void injectedPacksAreFoundWithTheirPlace() {
        List<Placement> p = InjectedPacks.place(kubeJsStack(), REPO, MODS);
        assertEquals(5, p.size(), "only the packs that are not in the repository");
        assertEquals(Anchor.ABOVE_VANILLA, p.get(0).anchor());
        assertFalse(p.get(0).aboveMods(), "[Before Mods] lies below the mods: mod/vanilla info");
        assertEquals(Anchor.ABOVE_TOP_ZIP, p.get(1).anchor(), "KubeJS puts [Internal] above the highest zip pack");
        assertEquals(Anchor.ABOVE_TOP_ZIP, p.get(3).anchor());
        assertTrue(p.get(3).aboveMods(), "the kubejs/assets pack is rated like a resource pack");
        assertEquals(Anchor.TOP, p.get(4).anchor(), "[Last] always on top");
        assertEquals(List.of("file/FreshColonists-v3.0.zip", "file/Ashen_16x.zip", "mod/kubejs", "mod/neoforge", "mod_resources",
            "generated/fragmentum_layer", "vanilla"), p.get(1).below());
    }

    @Test
    void unknownInjectedPackKeepsItsNeighbour() {
        List<StackPack> stack = new ArrayList<>(List.of(new StackPack("vanilla", false, false), new StackPack("mod_resources", false, false),
            new StackPack("file/Low.zip", true, false), new StackPack("someone:extra", false, false), new StackPack("file/High.zip", true, false)));
        List<Placement> p = InjectedPacks.place(stack, Set.of("vanilla", "mod_resources", "file/Low.zip", "file/High.zip"), Set.of("mod_resources"));
        assertEquals(Anchor.NEIGHBOUR, p.get(0).anchor(), "not a known rule: neighbour rule of Phase 3A");
        assertTrue(p.get(0).aboveMods());
    }

    private static PackRef rp(String id, ScanKind kind) {
        return new PackRef(id, id, PackRole.RESOURCE_PACK, kind, null, List.of(), null, null, null, null);
    }

    private static PackRef injected(String id) {
        return new PackRef(id, id, PackRole.RESOURCE_PACK, ScanKind.FALLBACK, null, List.of(), null, null, null, null, false, true, "KubeJS");
    }

    private static List<String> ids(List<PackRef> refs) {
        return refs.stream().map(PackRef::id).toList();
    }

    @Test
    void injectedPacksFollowTheirRuleWhenTheOrderChanges() {
        List<Placement> places = InjectedPacks.place(kubeJsStack(), REPO, MODS);
        List<Injected> inj = new ArrayList<>();
        for (Placement p : places) {
            inj.add(new Injected(p.aboveMods() ? injected(p.id())
                : new PackRef(p.id(), p.id(), PackRole.MOD, ScanKind.LOOKUP, null, List.of(), "kubejs", "KubeJS", null, null, false, true, "KubeJS"), p));
        }
        PackRef vanilla = new PackRef("vanilla", "Default", PackRole.VANILLA, ScanKind.LOOKUP, null, List.of(), null, null, null, null);
        PackRef ashen = rp("file/Ashen_16x.zip", ScanKind.ZIP);
        PackRef fc = rp("file/FreshColonists-v3.0.zip", ScanKind.ZIP);
        PackRef rune = rp("mod/runelessrpg:resourcepacks/spell_infinity_text", ScanKind.FALLBACK);
        PackRef folder = rp("file/MyFolder", ScanKind.FOLDER);
        PackRef mod = new PackRef("mod/kubejs", "KubeJS", PackRole.MOD, ScanKind.LOOKUP, null, List.of(), "kubejs", "KubeJS", "mod_resources", null);

        // order of the real stack (top first)
        List<PackRef> real = InjectedPacks.insert(List.of(rune, fc, ashen, mod, vanilla), inj);
        assertEquals(List.of("KubeJS Virtual Resource Pack [Last, assets]", "mod/runelessrpg:resourcepacks/spell_infinity_text",
            "KubeJS File Resource Pack [assets]", "KubeJS Virtual Resource Pack [After Mods, assets]",
            "KubeJS Virtual Resource Pack [Internal, assets]", "file/FreshColonists-v3.0.zip", "file/Ashen_16x.zip", "mod/kubejs",
            "KubeJS Virtual Resource Pack [Before Mods, assets]", "vanilla"), ids(real), "same as the real stack");

        // the user moves Ashen to the top in the pack screen: KubeJS follows the highest zip pack
        List<PackRef> moved = InjectedPacks.insert(List.of(ashen, folder, rune, fc, mod, vanilla), inj);
        assertEquals(List.of("KubeJS Virtual Resource Pack [Last, assets]", "KubeJS File Resource Pack [assets]",
            "KubeJS Virtual Resource Pack [After Mods, assets]", "KubeJS Virtual Resource Pack [Internal, assets]", "file/Ashen_16x.zip",
            "file/MyFolder", "mod/runelessrpg:resourcepacks/spell_infinity_text", "file/FreshColonists-v3.0.zip", "mod/kubejs",
            "KubeJS Virtual Resource Pack [Before Mods, assets]", "vanilla"), ids(moved));

        // no zip pack at all: like KubeJS (findAfterModsIndex = end of the list) on top
        List<PackRef> noZip = InjectedPacks.insert(List.of(folder, mod, vanilla), inj);
        assertEquals("KubeJS Virtual Resource Pack [Last, assets]", noZip.get(0).id());
        assertEquals("KubeJS File Resource Pack [assets]", noZip.get(1).id());
    }

    @Test
    void injectedPackIsRatedAndNamed() throws IOException {
        // kubejs/assets as a folder pack above PS_B_Top: overrides its diamond
        Path kubejs = tmp.resolve("kubejs");
        Path tex = kubejs.resolve("assets/minecraft/textures/item");
        Files.createDirectories(tex);
        Files.writeString(tex.resolve("diamond.png"), "kubejs diamond");
        PackRef k = new PackRef("KubeJS File Resource Pack [assets]", "KubeJS File Resource Pack [assets]", PackRole.RESOURCE_PACK,
            ScanKind.FOLDER, kubejs, List.of(), null, null, null, null, false, true, "KubeJS");
        List<PackRef> refs = new ArrayList<>(List.of(k));
        refs.addAll(TestPacks.readmeOrder().packs());
        ScanResult r = PackEvaluator.evaluate(new PackOrder(refs), new IndexCache(null), PackscopeSettings.DEFAULTS);
        ScanResult.PackSummary s = r.summary(k.id());
        assertTrue(s.injected());
        assertEquals("KubeJS", s.injectedBy());
        assertEquals(PackStatus.WINS, s.status());
        assertEquals(PackStatus.WINS_AND_LOSES, r.summary(TestPacks.id("PS_B_Top")).status(), "B now loses its diamond");
        String md = ReportWriter.markdown(r, PackscopeSettings.DEFAULTS, "test", List.of());
        assertTrue(md.contains("resource pack (injected)") && md.contains("injected (not in the pack screen) by KubeJS"), "report names it");
        // marking by exact id works
        ScanResult m = PackEvaluator.evaluate(new PackOrder(refs), new IndexCache(null), PackscopeSettings.DEFAULTS,
            IntendedSet.of(new IntendedMarking(k.id(), TestPacks.id("PS_B_Top"), null)));
        assertTrue(m.pair(k.id(), TestPacks.id("PS_B_Top")).intended(), "pair with B marked");
        assertEquals(PackStatus.WINS, m.summary(k.id()).status(), "still overrides A and D (pairwise, not marked)");
    }

    // ------------------------------------------------------------------------------------------------ children of other packs

    @Test
    void childrenOfOtherPacksAreResourcePacks() {
        // eatinganimations_compat-like: its children are resource packs of their own, only mod_resources' children are mods
        PackRef child = new PackRef(TestPacks.id("PS_B_Top"), "child", PackRole.RESOURCE_PACK, ScanKind.FOLDER,
            TestPacks.pack("PS_B_Top").path(), List.of(), null, null, "eatinganimations_compat", null);
        List<PackRef> refs = new ArrayList<>();
        refs.add(child);
        for (PackRef p : TestPacks.readmeOrder().packs()) if (!p.id().equals(child.id())) refs.add(p);
        ScanResult r = PackEvaluator.evaluate(new PackOrder(refs), new IndexCache(null), PackscopeSettings.DEFAULTS);
        ScanResult.PackSummary s = r.summary(child.id());
        assertEquals(PackRole.RESOURCE_PACK, s.role());
        assertEquals(PackStatus.WINS, s.status());
        assertEquals("eatinganimations_compat", s.parentId());
        String md = ReportWriter.markdown(r, PackscopeSettings.DEFAULTS, "test", List.of());
        assertTrue(md.contains("part of eatinganimations_compat"));
    }

    // ------------------------------------------------------------------------------------------------ packs in mod jars

    private Path jar(String name, Map<String, String> files) throws IOException {
        Path p = tmp.resolve(name);
        try (OutputStream out = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return p;
    }

    /** A pack the scanner only knows through the pack API, which turns out to be a folder in a mod jar. */
    private static PackRef builtIn(String id, Path jar, String inner) {
        return new PackRef(id, id, PackRole.RESOURCE_PACK, ScanKind.FALLBACK, null, List.of(), null, null, null,
            () -> new LocatedContent(new PackContent.Location(jar, inner, true, true)));
    }

    /** Pack API view of a pack that only tells where its files are (the scanner reads them itself). */
    private record LocatedContent(PackContent.Location where) implements PackContent {
        @Override
        public Set<String> namespaces() {
            return Set.of();
        }

        @Override
        public boolean has(String location) {
            return false;
        }

        @Override
        public java.io.InputStream open(String location) {
            return null;
        }

        @Override
        public byte[] packMeta() {
            return null;
        }

        @Override
        public Map<String, FileEntry> listAll(String[] knownFolders, String[] rootFiles) {
            return Map.of();
        }

        @Override
        public Location location() {
            return where;
        }

        @Override
        public void close() {}
    }

    @Test
    void builtInPackInAModJarIsScannedExactly() throws IOException {
        Path jar = jar("createfood.jar", Map.of(
            "com/example/Food.class", "x",
            "resourcepacks/minecraft_overrides/pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"x\"}}",
            "resourcepacks/minecraft_overrides/assets/minecraft/textures/item/cake.png", "create cake",
            "resourcepacks/minecraft_overrides/assets/minecraft/textures/item/pumpkin_pie.png", "create pie",
            "resourcepacks/create_overrides/assets/create/textures/item/x.png", "other pack"));
        PackRef food = builtIn("mod/createfood:resourcepacks/minecraft_overrides", jar, "resourcepacks/minecraft_overrides/");
        Path ashen = tmp.resolve("ashen");
        Files.createDirectories(ashen.resolve("assets/minecraft/textures/item"));
        Files.writeString(ashen.resolve("assets/minecraft/textures/item/cake.png"), "ashen cake");
        PackRef a = new PackRef("file/Ashen", "Ashen", PackRole.RESOURCE_PACK, ScanKind.FOLDER, ashen, List.of(), null, null, null, null);
        IndexCache cache = new IndexCache(null);
        ScanResult r = PackEvaluator.evaluate(new PackOrder(List.of(food, a)), cache, PackscopeSettings.DEFAULTS);
        PackIndex idx = r.indexes().get(food.id());
        assertEquals(ScanKind.JAR, idx.kind());
        assertFalse(idx.approximate(), "exact, not the fallback");
        assertEquals(Set.of("minecraft:textures/item/cake.png", "minecraft:textures/item/pumpkin_pie.png"), idx.files().keySet(),
            "only the files of this pack folder");
        assertEquals(PackStatus.WINS, r.summary(food.id()).status());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("approximate")), r.warnings().toString());
        // second evaluation: the zip directory comes from the cache (same object)
        ScanResult again = PackEvaluator.evaluate(new PackOrder(List.of(food, a)), cache, PackscopeSettings.DEFAULTS);
        assertTrue(again.indexes().get(food.id()) == idx, "cached by jar path + size + date + folder");
        // a wrong folder falls back to the pack API listing
        PackRef wrong = builtIn("mod/x:resourcepacks/missing", jar, "resourcepacks/missing/");
        ScanResult w = PackEvaluator.evaluate(new PackOrder(List.of(wrong)), new IndexCache(null), PackscopeSettings.DEFAULTS);
        assertTrue(w.indexes().get(wrong.id()).approximate());
    }

    @Test
    void foldersThePackDoesNotServeAreLeftOut() throws IOException {
        // KubeJS: kubejs/assets/<ns>/lang/ is not served by the folder pack (merged into "[Internal]" instead)
        Path kubejs = tmp.resolve("kjs");
        Files.createDirectories(kubejs.resolve("assets/t/lang"));
        Files.createDirectories(kubejs.resolve("assets/t/textures"));
        Files.writeString(kubejs.resolve("assets/t/lang/en_us.json"), "{\"k\":\"v\"}");
        Files.writeString(kubejs.resolve("assets/t/textures/a.png"), "a");
        PackRef ref = new PackRef("KubeJS File Resource Pack [assets]", "k", PackRole.RESOURCE_PACK, ScanKind.FALLBACK, null, List.of(),
            null, null, null, () -> new LocatedContent(new PackContent.Location(kubejs, "", false, false, List.of("lang/"))), false, true, "KubeJS");
        PackIndex idx = PackScanner.scan(ref, new IndexCache(null));
        assertEquals(Set.of("t:textures/a.png"), idx.files().keySet());
        assertFalse(idx.approximate());
    }

    @Test
    void jarPathHelpers() {
        assertEquals("resourcepacks/minecraft_overrides/", JarPaths.inner("/resourcepacks/minecraft_overrides"));
        assertEquals("", JarPaths.inner("/"));
        assertEquals("union:/D:/mods/accessorify-2.4.0-beta.5+1.21.1-neoforge.jar#41!/resourcepacks/1_21_1/rp",
            JarPaths.percentDecode("union:/D:/mods/accessorify-2.4.0-beta.5+1.21.1-neoforge.jar%2341!/resourcepacks/1_21_1/rp"),
            "'+' in jar names stays");
    }

    // ------------------------------------------------------------------------------------------------ warnings

    @Test
    void sameWarningsAreGrouped() {
        String approx = "approximate: only known folders (textures, models) and root files were listed";
        List<String> w = List.of("intended: unknown field x", "quark-emote-pack: " + approx, "mod/createfood:resourcepacks/a: " + approx,
            "moonlight:merged_pack: " + approx, "file/A.zip: broken language file x");
        List<String> g = ReportWriter.groupWarnings(w, List.of("quark-emote-pack", "mod/createfood:resourcepacks/a", "moonlight:merged_pack",
            "moonlight", "file/A.zip"));
        assertEquals(List.of("intended: unknown field x",
            "approximate scan (fallback, only known folders (textures, models) and root files were listed): 3 packs \u2013 quark-emote-pack, "
                + "mod/createfood:resourcepacks/a, moonlight:merged_pack",
            "file/A.zip: broken language file x"), g);
    }
}
