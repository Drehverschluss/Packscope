package com.drehverschluss.packscope.core.minecraft;

import com.drehverschluss.packscope.core.FileEntry;
import com.drehverschluss.packscope.core.Locations;
import com.drehverschluss.packscope.core.PackContent;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

/** {@link PackContent} on top of the game's {@link PackResources} (vanilla, mods, unknown pack types). */
final class PackResourcesContent implements PackContent {
    private static final PackType TYPE = PackType.CLIENT_RESOURCES;
    private final PackResources resources;

    PackResourcesContent(PackResources resources) {
        this.resources = resources;
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
        resources.close();
    }
}
