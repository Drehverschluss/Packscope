package com.drehverschluss.packscope.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

/**
 * Reads single files straight from a pack (texture preview in the detail screen): zips through an own {@link ZipFile},
 * folders through the file system, vanilla/mod/unknown packs through the pack API. Everything is closed again. Call it
 * off the render thread.
 */
public final class PackFiles {
    private PackFiles() {}

    /**
     * Content of {@code location} ({@code namespace:path}) in the pack, as the game sees it (inside the right overlay),
     * or null if the pack does not have it.
     *
     * @param index index of the pack from the same evaluation ({@link ScanResult#indexes()})
     */
    @Nullable
    public static byte[] read(PackRef ref, PackIndex index, String location) throws IOException {
        FileEntry entry = index.files().get(location);
        if (entry == null) return null;
        switch (ref.kind()) {
            case ZIP -> {
                if (ref.path() == null) return null;
                try (ZipFile zip = new ZipFile(ref.path().toFile())) {
                    return PackScanner.read(ref, entry, location, zip);
                }
            }
            case FOLDER -> {
                return PackScanner.read(ref, entry, location, null);
            }
            default -> {
                if (ref.opener() == null) return null;
                try (PackContent c = ref.opener().open(); InputStream in = c.open(location)) {
                    return in == null ? null : in.readAllBytes();
                }
            }
        }
    }
}
