package com.drehverschluss.packscope.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the {@link PackIndex} of a pack (Konzept 6, Phase-1-Bericht 1). Runs on the scan thread; every zip file and
 * pack opened here is closed again before returning (Windows locks open files).
 */
public final class PackScanner {
    /** Folders below {@code assets/<namespace>/} tried by the fallback scan. */
    static final String[] KNOWN_FOLDERS = {"textures", "models", "blockstates", "lang", "sounds", "font", "atlases", "shaders",
        "particles", "texts", "optifine", "emf", "etf", "items", "equipment", "post_effect", "misc", "gui", "icons"};
    /** Files directly in {@code assets/<namespace>/}, which a folder listing through the pack API never finds. */
    static final String[] ROOT_FILES = {"sounds.json", "regional_compliancies.json", "gpu_warnlist.json"};

    private PackScanner() {}

    /** Scans a resource pack (zip, folder or fallback). Lookup packs go through {@link #lookup}. */
    public static PackIndex scan(PackRef ref, IndexCache cache) {
        return switch (ref.kind()) {
            case ZIP -> zip(ref, cache);
            case FOLDER -> folder(ref, cache);
            case FALLBACK, JAR -> fallback(ref, cache);
            case LOOKUP -> new PackIndex(ref.id(), ScanKind.LOOKUP, Map.of(), null, false, List.of(), 0);
        };
    }

    // ------------------------------------------------------------------------------------------------ zip

    static PackIndex zip(PackRef ref, IndexCache cache) {
        return zipAt(ref, ref.path(), "", ScanKind.ZIP, cache);
    }

    /**
     * Index of a pack inside a zip file: the pack root is {@code inner} ("" or a folder ending with "/", e.g. the folder
     * of a built-in pack in a mod jar). CRC and size come from the zip directory.
     */
    static PackIndex zipAt(PackRef ref, Path file, String inner, ScanKind kind, IndexCache cache) {
        long start = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        String key;
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            key = file + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis() + "|" + inner + "|" + ref.overlays();
        } catch (IOException e) {
            return failed(ref, "cannot read " + file + ": " + e.getMessage(), start);
        }
        PackIndex cached = cache.zip(key);
        if (cached != null) return cached.packId().equals(ref.id()) ? cached
            : new PackIndex(ref.id(), cached.kind(), cached.files(), cached.filter(), cached.approximate(), cached.warnings(), cached.scanNanos());

        List<String> prefixes = prefixes(ref.overlays());
        List<Map<String, FileEntry>> perPrefix = new ArrayList<>();
        for (int i = 0; i < prefixes.size(); i++) perPrefix.add(new HashMap<>());
        PackFilter filter = null;
        boolean sawInner = false;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.startsWith(inner)) continue;
                sawInner = true;
                name = name.substring(inner.length());
                for (int i = 0; i < prefixes.size(); i++) {
                    String base = assetsRoot(prefixes.get(i));
                    if (name.startsWith(base)) {
                        String loc = Locations.fromAssetPath(name.substring(base.length()));
                        if (loc != null) perPrefix.get(i).put(loc, new FileEntry(e.getSize(), e.getCrc(), prefixes.get(i)));
                    }
                }
            }
            ZipEntry meta = zip.getEntry(inner + "pack.mcmeta");
            if (meta != null) {
                try (InputStream in = zip.getInputStream(meta)) {
                    filter = PackFilter.parse(in.readAllBytes());
                } catch (RuntimeException ex) {
                    warnings.add("broken filter in pack.mcmeta: " + ex.getMessage());
                }
            }
            if (!inner.isEmpty() && !sawInner) return failed(ref, "folder " + inner + " not found in " + file, start);
        } catch (IOException e) {
            return failed(ref, "cannot open zip " + file + ": " + e.getMessage(), start);
        }
        PackIndex index = new PackIndex(ref.id(), kind, merge(perPrefix), filter, false, warnings, System.nanoTime() - start);
        cache.putZip(key, index);
        return index;
    }

    // ------------------------------------------------------------------------------------------------ folder

    static PackIndex folder(PackRef ref, IndexCache cache) {
        // a folder inside a mod jar: read the zip directory of the jar instead of unpacking every file (Phase 5C)
        PackContent.Location jar = JarPaths.locate(ref.path());
        if (jar != null) {
            PackIndex index = zipAt(ref, jar.path(), jar.prefix(), ScanKind.JAR, cache);
            if (!index.approximate()) return index;
        }
        return folderAt(ref, ref.path(), cache);
    }

    /** Index of a pack folder ({@code assets/} below {@code root}); also a folder inside a mod jar (union file system). */
    static PackIndex folderAt(PackRef ref, Path root, IndexCache cache) {
        long start = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        IndexCache.FolderCrc crcCache = cache.folder(root);
        boolean realFolder = root.getFileSystem() == FileSystems.getDefault();
        List<String> prefixes = prefixes(ref.overlays());
        List<Map<String, FileEntry>> perPrefix = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String prefix : prefixes) {
            Map<String, FileEntry> files = new HashMap<>();
            perPrefix.add(files);
            Path assets = prefix.isEmpty() ? root.resolve("assets") : root.resolve(prefix).resolve("assets");
            if (!Files.isDirectory(assets)) continue;
            try (Stream<Path> s = Files.walk(assets)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    if (!attrs.isRegularFile()) continue;
                    String loc = Locations.fromAssetPath(relative(assets, p));
                    if (loc == null) continue;
                    String rel = relative(root, p);
                    long size = attrs.size();
                    long modified = attrs.lastModifiedTime().toMillis();
                    long crc = crcCache.crc(rel, size, modified);
                    if (crc < 0) {
                        try {
                            crc = crc(Files.readAllBytes(p));
                            crcCache.put(rel, size, modified, crc);
                        } catch (IOException e) {
                            warnings.add("cannot read " + rel + ": " + e.getMessage());
                        }
                    }
                    seen.add(rel);
                    files.put(loc, new FileEntry(size, crc, prefix));
                }
            } catch (IOException | RuntimeException e) {
                warnings.add("cannot list " + assets + ": " + e.getMessage());
            }
        }
        PackFilter filter = null;
        Path meta = root.resolve("pack.mcmeta");
        if (Files.isRegularFile(meta)) {
            try {
                filter = PackFilter.parse(Files.readAllBytes(meta));
            } catch (IOException | RuntimeException e) {
                warnings.add("broken filter in pack.mcmeta: " + e.getMessage());
            }
        }
        if (realFolder) {
            crcCache.retain(seen);
            crcCache.save();
        }
        return new PackIndex(ref.id(), ScanKind.FOLDER, merge(perPrefix), filter, false, warnings, System.nanoTime() - start);
    }

    // ------------------------------------------------------------------------------------------------ fallback

    static PackIndex fallback(PackRef ref, IndexCache cache) {
        PackIndex cached = cache.fallback(ref.id());
        if (cached != null) return cached;
        // Phase 5C: many packs of unknown suppliers (built-in packs in mod jars, packs injected by mods) open as a plain
        // folder or zip pack: then scan exactly, the pack API is only used to find out where the files are
        Optional<PackContent.Location> where = cache.location(ref.id());
        if (where == null) {
            where = Optional.empty();
            if (ref.opener() != null) {
                try (PackContent content = ref.opener().open()) {
                    where = Optional.ofNullable(content.location());
                } catch (Exception ignored) {
                    // read again below through the fallback listing, which reports the problem
                }
            }
            cache.putLocation(ref.id(), where);
        }
        if (where.isPresent()) {
            PackContent.Location l = where.get();
            PackIndex exact = l.zip() ? zipAt(ref, l.path(), l.prefix(), l.inJar() ? ScanKind.JAR : ScanKind.ZIP, cache)
                : folderAt(ref, l.path(), cache);
            if (!exact.approximate()) {
                if (l.skip().isEmpty()) return exact;
                Map<String, FileEntry> served = new HashMap<>();
                exact.files().forEach((loc, e) -> {
                    if (!l.skips(loc)) served.put(loc, e);
                });
                return new PackIndex(exact.packId(), exact.kind(), served, exact.filter(), false, exact.warnings(), exact.scanNanos());
            }
        }
        long start = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        Map<String, FileEntry> files = Map.of();
        PackFilter filter = null;
        if (ref.opener() == null) {
            warnings.add("no access to the pack");
        } else {
            try (PackContent content = ref.opener().open()) {
                files = content.listAll(KNOWN_FOLDERS, ROOT_FILES);
                try {
                    filter = PackFilter.parse(content.packMeta());
                } catch (RuntimeException e) {
                    warnings.add("broken filter in pack.mcmeta: " + e.getMessage());
                }
            } catch (Exception e) {
                warnings.add("cannot read the pack: " + e.getMessage());
            }
        }
        warnings.add("approximate: only known folders (" + String.join(", ", KNOWN_FOLDERS) + ") and root files were listed");
        PackIndex index = new PackIndex(ref.id(), ScanKind.FALLBACK, files, filter, true, warnings, System.nanoTime() - start);
        cache.putFallback(ref.id(), index);
        return index;
    }

    // ------------------------------------------------------------------------------------------------ lookup

    /**
     * Index of a vanilla/mod pack restricted to the given locations: which of them the pack has. Results are cached per
     * pack for the session (mod jars do not change while the game runs).
     */
    public static PackIndex lookup(PackRef ref, Collection<String> locations, IndexCache cache) {
        long start = System.nanoTime();
        if (ref.injected()) cache.markDynamicLookup(ref.id());
        Map<String, Boolean> known = cache.lookup(ref.id());
        List<String> warnings = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String loc : locations) if (!known.containsKey(loc)) missing.add(loc);
        Optional<PackFilter> filter = cache.lookupFilter(ref.id());
        if ((!missing.isEmpty() || filter == null) && ref.opener() != null) {
            try (PackContent content = ref.opener().open()) {
                Set<String> namespaces = content.namespaces();
                for (String loc : missing) {
                    known.put(loc, namespaces.contains(Locations.namespace(loc)) && content.has(loc));
                }
                if (filter == null) {
                    try {
                        filter = Optional.ofNullable(PackFilter.parse(content.packMeta()));
                    } catch (RuntimeException e) {
                        warnings.add("broken filter in pack.mcmeta: " + e.getMessage());
                        filter = Optional.empty();
                    }
                    cache.putLookupFilter(ref.id(), filter);
                }
            } catch (Exception e) {
                warnings.add("cannot read the pack: " + e.getMessage());
            }
        }
        Map<String, FileEntry> files = new HashMap<>();
        for (String loc : locations) if (Boolean.TRUE.equals(known.get(loc))) files.put(loc, FileEntry.PRESENT);
        return new PackIndex(ref.id(), ScanKind.LOOKUP, files, filter == null ? null : filter.orElse(null), false, warnings,
            System.nanoTime() - start);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** "" for the main pack, then the overlays in pack.mcmeta order. */
    private static List<String> prefixes(List<String> overlays) {
        List<String> list = new ArrayList<>();
        list.add("");
        list.addAll(overlays);
        return list;
    }

    private static String assetsRoot(String prefix) {
        return prefix.isEmpty() ? "assets/" : prefix + "/assets/";
    }

    /** Main pack first, then each overlay on top (later overlay wins, like CompositePackResources). */
    private static Map<String, FileEntry> merge(List<Map<String, FileEntry>> perPrefix) {
        if (perPrefix.size() == 1) return perPrefix.get(0);
        Map<String, FileEntry> merged = new LinkedHashMap<>();
        for (Map<String, FileEntry> m : perPrefix) merged.putAll(m);
        return merged;
    }

    private static String relative(Path base, Path p) {
        Path rel = base.relativize(p);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) sb.append('/');
            sb.append(rel.getName(i));
        }
        return sb.toString();
    }

    static long crc(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return c.getValue();
    }

    private static PackIndex failed(PackRef ref, String warning, long start) {
        return new PackIndex(ref.id(), ref.kind(), Map.of(), null, true, List.of(warning), System.nanoTime() - start);
    }

    /** Reads the bytes of a location from an indexed zip/folder pack (inside the right overlay). */
    @Nullable
    static byte[] read(PackRef ref, FileEntry entry, String location, @Nullable ZipFile openZip) throws IOException {
        String inPack = (entry.prefix().isEmpty() ? "" : entry.prefix() + "/") + Locations.assetPath(location);
        if (ref.kind() == ScanKind.ZIP && openZip != null) {
            ZipEntry e = openZip.getEntry(inPack);
            if (e == null) return null;
            try (InputStream in = openZip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
        if (ref.kind() == ScanKind.FOLDER) {
            Path p = ref.path();
            for (String part : inPack.split("/")) p = p.resolve(part);
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        }
        return null;
    }
}
