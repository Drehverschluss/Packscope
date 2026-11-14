package com.drehverschluss.packscope.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

/**
 * Reads file contents during one evaluation (language files, sounds.json). Opens each zip/pack at most once and
 * closes everything in {@link #close()}.
 */
final class ContentSource implements AutoCloseable {
    private final List<PackRef> packs;
    private final List<PackIndex> indexes;
    private final Map<Integer, ZipFile> zips = new HashMap<>();
    private final Map<Integer, PackContent> contents = new HashMap<>();

    ContentSource(List<PackRef> packs, List<PackIndex> indexes) {
        this.packs = packs;
        this.indexes = indexes;
    }

    /** Content of a location in pack {@code i}, or null if it cannot be read. */
    @Nullable
    byte[] read(int i, String location) throws IOException {
        PackRef ref = packs.get(i);
        FileEntry entry = indexes.get(i).files().get(location);
        if (entry == null) return null;
        switch (ref.kind()) {
            case ZIP -> {
                ZipFile zip = zips.get(i);
                if (zip == null) {
                    zip = new ZipFile(ref.path().toFile());
                    zips.put(i, zip);
                }
                return PackScanner.read(ref, entry, location, zip);
            }
            case FOLDER -> {
                return PackScanner.read(ref, entry, location, null);
            }
            default -> {
                if (ref.opener() == null) return null;
                PackContent c = contents.get(i);
                if (c == null) {
                    c = ref.opener().open();
                    contents.put(i, c);
                }
                try (InputStream in = c.open(location)) {
                    return in == null ? null : in.readAllBytes();
                }
            }
        }
    }

    @Override
    public void close() {
        for (ZipFile z : zips.values()) {
            try {
                z.close();
            } catch (IOException ignored) {
            }
        }
        for (PackContent c : contents.values()) {
            try {
                c.close();
            } catch (RuntimeException ignored) {
            }
        }
        zips.clear();
        contents.clear();
    }
}
