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

    @Override
    void close();

    /** Opens a {@link PackContent}. */
    @FunctionalInterface
    interface Opener {
        PackContent open() throws IOException;
    }
}
