package com.drehverschluss.packscope.core;

/**
 * One file of a pack.
 *
 * @param size   uncompressed size in bytes, -1 if unknown (lookup packs)
 * @param crc    CRC32 of the content, -1 if unknown
 * @param prefix folder inside the pack the file comes from: "" for the main pack, otherwise the overlay folder
 */
public record FileEntry(long size, long crc, String prefix) {
    public static final FileEntry PRESENT = new FileEntry(-1, -1, "");

    public boolean known() {
        return size >= 0 && crc >= 0;
    }

    /** Byte-identical as far as size and CRC32 can tell. */
    public boolean sameContent(FileEntry other) {
        return other != null && known() && other.known() && size == other.size && crc == other.crc;
    }
}
