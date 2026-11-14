package com.drehverschluss.packscope.core;

/** How the files of a pack are found. */
public enum ScanKind {
    /** Own {@link java.util.zip.ZipFile}, CRC and size from the zip directory. */
    ZIP,
    /** {@link java.nio.file.Files#walk} on the folder (also mod jars), CRC computed (cached on disk for real folders). */
    FOLDER,
    /** No full index: only asked per path whether the pack has a file (vanilla, mods). */
    LOOKUP,
    /** Unknown pack type: listing of known folders through the pack API. Approximate. */
    FALLBACK;

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
