package com.drehverschluss.packscope.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Access to a pack through the game's own pack API, for packs that are not indexed directly (vanilla, mods, unknown
 * pack types). Opened on the scan thread and always closed again.
 */
public interface PackContent extends AutoCloseable {
    /** Namespaces below {@code assets/}. */
    Set<String> namespaces();

    /** Whether the pack has the file {@code namespace:path}. */
    boolean has(String location);

    /** Opens {@code namespace:path}, or null if missing. */
    @Nullable
    InputStream open(String location) throws IOException;

    /** Content of {@code pack.mcmeta} in the pack root, or null. */
    @Nullable
    byte[] packMeta() throws IOException;

    /** Best-effort listing of all files (fallback scan). CRC is computed from the content. */
    Map<String, FileEntry> listAll(String[] knownFolders, String[] rootFiles) throws IOException;

    /**
     * Where the files of the pack really are, if that can be told from its pack type (Phase 5C): a folder, a zip file,
     * or a folder inside a mod jar. Null = unknown (dynamic packs), only the fallback scan is possible.
     */
    @Nullable
    default Location location() {
        return null;
    }

    /**
     * Files of a pack on disk.
     *
     * @param path   folder (pack root, {@code assets/} below it) or zip/jar file
     * @param prefix for zip/jar: folder of the pack inside the file ("" = root, else ending with "/")
     * @param zip    whether {@code path} is a zip/jar file
     * @param inJar  a built-in pack inside a mod jar (shown as scan kind "jar")
     * @param skip   folders below {@code assets/<namespace>/} the pack does not serve although they are there (e.g.
     *               {@code lang/} for the KubeJS folder pack, which hands language files to another pack)
     */
    record Location(java.nio.file.Path path, String prefix, boolean zip, boolean inJar, java.util.List<String> skip) {
        public Location(java.nio.file.Path path, String prefix, boolean zip, boolean inJar) {
            this(path, prefix, zip, inJar, java.util.List.of());
        }

        /** Whether {@code namespace:path} is one of the skipped folders. */
        public boolean skips(String location) {
            if (skip.isEmpty()) return false;
            String p = Locations.path(location);
            for (String s : skip) if (p.startsWith(s)) return true;
            return false;
        }
    }

    @Override
    void close();

    /** Opens a {@link PackContent}. */
    @FunctionalInterface
    interface Opener {
        PackContent open() throws IOException;
    }
}
