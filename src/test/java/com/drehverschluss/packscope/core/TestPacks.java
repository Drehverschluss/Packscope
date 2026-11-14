package com.drehverschluss.packscope.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** The test packs from {@code src/test/resources/testpacks} (copy of Konzept/Test-Packs) in the README order. */
final class TestPacks {
    /** Top to bottom, as in Konzept/Test-Packs/README.md. */
    static final String[] ORDER = {"PS_Folder/PS_F_Top.zip", "PS_Folder/PS_F_Low", "PS_AtlasFont_2", "PS_AtlasFont_1.zip",
        "PS_ModTex.zip", "PS_Anim_High", "PS_Anim_Low.zip", "PS_Overlay.zip", "PS_Filter.zip", "PS_Sound_Replace",
        "PS_Sound_Add.zip", "PS_Lang_High", "PS_Lang_Low.zip", "PS_Lang3_High", "PS_Lang3_Low.zip", "PS_B_Top", "PS_E_CopyOnly.zip",
        "PS_C_Copy.zip", "PS_A_Base.zip", "PS_D_Hidden.zip"};

    private TestPacks() {}

    static Path root() {
        try {
            return Path.of(TestPacks.class.getResource("/testpacks").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static String id(String name) {
        return "file/" + name;
    }

    static PackRef pack(String name) {
        Path p = root().resolve(name);
        List<String> overlays = name.equals("PS_Overlay.zip") ? List.of("ov_active") : List.of();
        String title = name.substring(name.lastIndexOf('/') + 1);
        ScanKind kind = Files.isDirectory(p) ? ScanKind.FOLDER : ScanKind.ZIP;
        return new PackRef(id(name), title, PackRole.RESOURCE_PACK, kind, p.toAbsolutePath().normalize(), overlays, null, null, null, null);
    }

    /** Test packs, then mod resources (packed_packs), then vanilla - like the game with mod_resources above vanilla. */
    static PackOrder order(String... names) {
        List<PackRef> refs = new ArrayList<>();
        for (String n : names) refs.add(pack(n));
        refs.add(mod("mod/packed_packs", "packed_packs", "Packed Packs", Map.of("packed_packs:textures/gui/sprites/icon/edit.png", "x")));
        refs.add(vanilla());
        return new PackOrder(refs);
    }

    static PackOrder readmeOrder() {
        return order(ORDER);
    }

    static PackRef vanilla() {
        Map<String, String> files = new HashMap<>();
        files.put("minecraft:textures/item/diamond.png", "vanilla diamond");
        files.put("minecraft:textures/item/emerald.png", "vanilla emerald");
        files.put("minecraft:atlases/blocks.json", "{\"sources\":[]}");
        files.put("minecraft:font/default.json", "{\"providers\":[]}");
        return new PackRef("vanilla", "Default", PackRole.VANILLA, ScanKind.LOOKUP, null, List.of(), null, null, null, () -> new MapContent(files));
    }

    static PackRef mod(String id, String modId, String modName, Map<String, String> files) {
        return new PackRef(id, modName + ".jar", PackRole.MOD, ScanKind.LOOKUP, null, List.of(), modId, modName, "mod_resources",
            () -> new MapContent(files));
    }

    /** In-memory {@link PackContent} for vanilla/mod lookups. */
    static final class MapContent implements PackContent {
        private final Map<String, String> files;

        MapContent(Map<String, String> files) {
            this.files = files;
        }

        @Override
        public Set<String> namespaces() {
            return files.keySet().stream().map(Locations::namespace).collect(Collectors.toSet());
        }

        @Override
        public boolean has(String location) {
            return files.containsKey(location);
        }

        @Override
        public InputStream open(String location) {
            String s = files.get(location);
            return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public byte[] packMeta() {
            return null;
        }

        @Override
        public Map<String, FileEntry> listAll(String[] knownFolders, String[] rootFiles) {
            Map<String, FileEntry> out = new HashMap<>();
            files.forEach((k, v) -> out.put(k, new FileEntry(v.length(), PackScanner.crc(v.getBytes(StandardCharsets.UTF_8)), "")));
            return out;
        }

        @Override
        public void close() {
        }
    }
}
