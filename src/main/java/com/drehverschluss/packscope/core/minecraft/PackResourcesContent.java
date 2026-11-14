package com.drehverschluss.packscope.core.minecraft;

import com.drehverschluss.packscope.core.FileEntry;
import com.drehverschluss.packscope.core.Locations;
import com.drehverschluss.packscope.core.PackContent;
import com.drehverschluss.packscope.core.JarPaths;
import com.drehverschluss.packscope.mixin.FilePackResourcesAccessor;
import com.drehverschluss.packscope.mixin.PathPackResourcesAccessor;
import com.drehverschluss.packscope.mixin.SharedZipFileAccessAccessor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

/** {@link PackContent} on top of the game's {@link PackResources} (vanilla, mods, unknown pack types). */
final class PackResourcesContent implements PackContent {
    private static final PackType TYPE = PackType.CLIENT_RESOURCES;
    /** Class of the KubeJS folder pack ({@code kubejs/assets}), Phase 5C. */
    static final String KUBEJS_FILE_PACK = "dev.latvian.mods.kubejs.script.data.KubeFileResourcePack";
    private final PackResources resources;
    private final boolean owned;

    /** A pack opened for Packscope: closed again in {@link #close()}. */
    PackResourcesContent(PackResources resources) {
        this(resources, true);
    }

    /**
     * @param owned false for a pack of the running resource manager (injected packs): never closed by Packscope
     */
    PackResourcesContent(PackResources resources, boolean owned) {
        this.resources = resources;
        this.owned = owned;
    }

    @Nullable
    @Override
    public Location location() {
        if (resources instanceof PathPackResources) {
            Path root = ((PathPackResourcesAccessor) resources).packscope$getRoot();
            if (root.getFileSystem() == FileSystems.getDefault()) return new Location(root.toAbsolutePath().normalize(), "", false, false);
            Location jar = JarPaths.locate(root);
            return jar != null ? jar : new Location(root, "", false, false);
        }
        if (resources instanceof FilePackResources) {
            FilePackResourcesAccessor acc = (FilePackResourcesAccessor) resources;
            File file = ((SharedZipFileAccessAccessor) acc.packscope$getZipFileAccess()).packscope$getFile();
            String prefix = acc.packscope$getPrefix();
            String inner = prefix == null || prefix.isEmpty() ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
            return new Location(file.toPath().toAbsolutePath().normalize(), inner, true, false);
        }
        if (KUBEJS_FILE_PACK.equals(resources.getClass().getName())) {
            // KubeJS serves <game>/kubejs/assets as a pack (KubeJSPaths.ASSETS); the pack root is <game>/kubejs. Language
            // files there are not served by this pack (KubeFileResourcePack.skipFile) but merged into "[Internal]"
            Path kubejs = Minecraft.getInstance().gameDirectory.toPath().resolve("kubejs").toAbsolutePath().normalize();
            if (Files.isDirectory(kubejs.resolve("assets"))) return new Location(kubejs, "", false, false, java.util.List.of("lang/"));
        }
        return null;
    }

    @Override
    public Set<String> namespaces() {
        return resources.getNamespaces(TYPE);
    }

    @Override
    public boolean has(String location) {
        ResourceLocation rl = ResourceLocation.tryBuild(Locations.namespace(location), Locations.path(location));
        return rl != null && resources.getResource(TYPE, rl) != null;
    }

    @Nullable
    @Override
    public InputStream open(String location) throws IOException {
        ResourceLocation rl = ResourceLocation.tryBuild(Locations.namespace(location), Locations.path(location));
        IoSupplier<InputStream> io = rl == null ? null : resources.getResource(TYPE, rl);
        return io == null ? null : io.get();
    }

    @Nullable
    @Override
    public byte[] packMeta() throws IOException {
        IoSupplier<InputStream> io = resources.getRootResource(PackResources.PACK_META);
        if (io == null) return null;
        try (InputStream in = io.get()) {
            return in.readAllBytes();
        }
    }

    @Override
    public Map<String, FileEntry> listAll(String[] knownFolders, String[] rootFiles) {
        Map<String, FileEntry> out = new HashMap<>();
        for (String ns : namespaces()) {
            for (String folder : knownFolders) {
                resources.listResources(TYPE, ns, folder, (rl, io) -> out.put(rl.toString(), entry(io)));
            }
            for (String file : rootFiles) {
                ResourceLocation rl = ResourceLocation.tryBuild(ns, file);
                IoSupplier<InputStream> io = rl == null ? null : resources.getResource(TYPE, rl);
                if (io != null) out.put(rl.toString(), entry(io));
            }
        }
        return out;
    }

    private static FileEntry entry(IoSupplier<InputStream> io) {
        try (InputStream in = io.get()) {
            byte[] b = in.readAllBytes();
            CRC32 c = new CRC32();
            c.update(b);
            return new FileEntry(b.length, c.getValue(), "");
        } catch (IOException e) {
            return FileEntry.PRESENT;
        }
    }

    @Override
    public void close() {
        if (owned) resources.close();
    }
}
