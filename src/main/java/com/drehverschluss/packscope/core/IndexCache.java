package com.drehverschluss.packscope.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches of the scan (Konzept 6):
 * <ul>
 *     <li>zip packs: whole index in memory for the session, key = absolute path + size + last modified + overlays</li>
 *     <li>folder packs: CRC per file (relative path + size + last modified), in memory and on disk in
 *     {@code packscope/cache/} (only folders on the normal file system, not mod jars)</li>
 *     <li>lookup packs (vanilla, mods): "has this file" per location, memory only</li>
 *     <li>fallback packs: whole index in memory, key = pack id, dropped with every resource reload</li>
 *     <li>built-in packs in mod jars: like zip packs, key = jar path + size + last modified + folder in the jar + overlays</li>
 * </ul>
 * Only used from the scan thread.
 */
public final class IndexCache {
    private static final Logger LOG = LoggerFactory.getLogger("Packscope");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path cacheDir;
    private final Map<String, PackIndex> zipIndexes = new ConcurrentHashMap<>();
    private final Map<String, PackIndex> fallbackIndexes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Boolean>> lookups = new ConcurrentHashMap<>();
    private final Map<String, Optional<PackFilter>> lookupFilters = new ConcurrentHashMap<>();
    private final Map<Path, FolderCrc> folders = new ConcurrentHashMap<>();
    /** Where a pack of an unknown supplier really lies ({@link PackContent#location()}), per pack id; empty = nowhere. */
    private final Map<String, Optional<PackContent.Location>> locations = new ConcurrentHashMap<>();
    /** Lookup packs whose content can change with every reload (injected by mods). */
    private final Set<String> dynamicLookups = ConcurrentHashMap.newKeySet();
    private boolean diskEnabled = true;

    /** @param cacheDir {@code <game>/packscope/cache}, may be null (no disk cache, e.g. in tests) */
    public IndexCache(@Nullable Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public void setDiskEnabled(boolean enabled) {
        this.diskEnabled = enabled;
    }

    @Nullable
    PackIndex zip(String key) {
        return zipIndexes.get(key);
    }

    void putZip(String key, PackIndex index) {
        zipIndexes.put(key, index);
    }

    @Nullable
    PackIndex fallback(String packId) {
        return fallbackIndexes.get(packId);
    }

    void putFallback(String packId, PackIndex index) {
        fallbackIndexes.put(packId, index);
    }

    /** Location of a pack, null = not asked yet. */
    @Nullable
    Optional<PackContent.Location> location(String packId) {
        return locations.get(packId);
    }

    void putLocation(String packId, Optional<PackContent.Location> location) {
        locations.put(packId, location);
    }

    void markDynamicLookup(String packId) {
        dynamicLookups.add(packId);
    }

    /**
     * Drops what can change with a resource reload: fallback indexes (dynamic packs such as {@code moonlight:merged_pack}
     * are built anew), the locations of packs with unknown suppliers and the lookups of injected packs (Phase 5C).
     */
    public void clearDynamic() {
        fallbackIndexes.clear();
        locations.clear();
        for (String id : dynamicLookups) {
            lookups.remove(id);
            lookupFilters.remove(id);
        }
        dynamicLookups.clear();
    }

    Map<String, Boolean> lookup(String packId) {
        return lookups.computeIfAbsent(packId, k -> new ConcurrentHashMap<>());
    }

    /** Parsed language files of resource packs ({@code id|location|size|crc}). */
    final Map<String, Map<String, String>> langFiles = new ConcurrentHashMap<>();
    /** Parsed sounds.json files ({@code id|location|size|crc}, lookup packs {@code id|location}). */
    final Map<String, Map<String, JsonMergers.SoundEvent>> soundFiles = new ConcurrentHashMap<>();
    /** Number of keys a resource pack language file shares with a mod/vanilla one ({@code id|location|size|crc|lookupId}). */
    final Map<String, Integer> langOverlap = new ConcurrentHashMap<>();

    /** Filter of a lookup pack: null = not read yet, empty = no filter. */
    @Nullable
    Optional<PackFilter> lookupFilter(String packId) {
        return lookupFilters.get(packId);
    }

    void putLookupFilter(String packId, Optional<PackFilter> filter) {
        lookupFilters.put(packId, filter);
    }

    /** CRC store of one folder pack (loaded from disk on first use). */
    FolderCrc folder(Path root) {
        return folders.computeIfAbsent(root, r -> FolderCrc.load(r, diskFile(r)));
    }

    /** Drops everything, also the files on disk ({@code /packscope rescan}). */
    public void clear() {
        zipIndexes.clear();
        fallbackIndexes.clear();
        locations.clear();
        dynamicLookups.clear();
        lookups.clear();
        lookupFilters.clear();
        folders.clear();
        langFiles.clear();
        soundFiles.clear();
        langOverlap.clear();
        if (cacheDir != null && Files.isDirectory(cacheDir)) {
            try (Stream<Path> s = Files.list(cacheDir)) {
                for (Path p : s.filter(p -> p.getFileName().toString().startsWith("folder-")).toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException e) {
                LOG.warn("Packscope: could not clear the cache in {}", cacheDir, e);
            }
        }
    }

    @Nullable
    private Path diskFile(Path root) {
        if (!diskEnabled || cacheDir == null || root.getFileSystem() != FileSystems.getDefault()) return null;
        String name = root.getFileName() == null ? "root" : root.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 40) name = name.substring(0, 40);
        return cacheDir.resolve("folder-" + name + "-" + Integer.toHexString(root.toString().hashCode()) + ".json");
    }

    /** Relative path inside the folder -> size, last modified, CRC. */
    static final class FolderCrc {
        private final Path root;
        @Nullable
        private final Path file;
        private final Map<String, long[]> entries = new HashMap<>();
        private boolean dirty;

        private FolderCrc(Path root, @Nullable Path file) {
            this.root = root;
            this.file = file;
        }

        static FolderCrc load(Path root, @Nullable Path file) {
            FolderCrc c = new FolderCrc(root, file);
            if (file != null && Files.isRegularFile(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                    if (root.toString().equals(o.get("path").getAsString())) {
                        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("files").entrySet()) {
                            JsonArray a = e.getValue().getAsJsonArray();
                            c.entries.put(e.getKey(), new long[]{a.get(0).getAsLong(), a.get(1).getAsLong(), a.get(2).getAsLong()});
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Packscope: ignoring broken cache file {}", file, e);
                }
            }
            return c;
        }

        synchronized long crc(String relative, long size, long modified) {
            long[] v = entries.get(relative);
            return v != null && v[0] == size && v[1] == modified ? v[2] : -1;
        }

        synchronized void put(String relative, long size, long modified, long crc) {
            entries.put(relative, new long[]{size, modified, crc});
            dirty = true;
        }

        /** Keeps only the given files (removed files drop out of the cache). */
        synchronized void retain(java.util.Set<String> present) {
            if (entries.keySet().retainAll(present)) dirty = true;
        }

        synchronized void save() {
            if (!dirty || file == null) return;
            JsonObject o = new JsonObject();
            o.addProperty("path", root.toString());
            JsonObject files = new JsonObject();
            entries.forEach((k, v) -> {
                JsonArray a = new JsonArray();
                a.add(v[0]);
                a.add(v[1]);
                a.add(v[2]);
                files.add(k, a);
            });
            o.add("files", files);
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    GSON.toJson(o, w);
                }
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                dirty = false;
            } catch (IOException e) {
                LOG.warn("Packscope: could not write cache file {}", file, e);
            }
        }
    }
}
