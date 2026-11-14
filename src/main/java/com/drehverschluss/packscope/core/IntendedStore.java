package com.drehverschluss.packscope.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The intended markings in {@code config/packscope-intended.json} (Konzept 4.1). Reads and writes the file; all
 * methods are synchronized. The render thread changes markings, an evaluation works on a {@link #snapshot()}.
 *
 * <p>Format: {@code {"version": 1, "intended": [{"winner": id, "loser": id, "note": text}, ...]}}. Unknown fields (top
 * level and per entry) and entries without winner/loser are kept and written back. A broken file never crashes the game:
 * the markings are ignored with a warning, and before the first overwrite a copy {@code .broken} is made.
 */
public final class IntendedStore {
    private static final Logger LOG = LoggerFactory.getLogger("Packscope");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final int FORMAT_VERSION = 1;

    private final Path file;
    private List<IntendedMarking> markings = new ArrayList<>();
    private List<JsonObject> invalidEntries = new ArrayList<>();
    private JsonObject topExtra = new JsonObject();
    private List<String> warnings = List.of();
    private long version;
    private long stampSize = Long.MIN_VALUE;
    private long stampModified = Long.MIN_VALUE;
    private boolean broken;
    private IntendedSet snapshot = IntendedSet.EMPTY;

    public IntendedStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Immutable view for one evaluation. */
    public synchronized IntendedSet snapshot() {
        return snapshot;
    }

    public synchronized List<IntendedMarking> markings() {
        return List.copyOf(markings);
    }

    /** Reads the file (start, {@code /packscope rescan}). */
    public synchronized void load() {
        markings = new ArrayList<>();
        invalidEntries = new ArrayList<>();
        topExtra = new JsonObject();
        broken = false;
        List<String> w = new ArrayList<>();
        long[] stamp = stamp();
        stampSize = stamp[0];
        stampModified = stamp[1];
        if (stamp[0] >= 0) {
            try {
                JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!root.isJsonObject()) throw new IllegalStateException("not a JSON object");
                JsonObject o = root.getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    if (!e.getKey().equals("version") && !e.getKey().equals("intended")) topExtra.add(e.getKey(), e.getValue());
                }
                if (o.has("version") && o.get("version").getAsInt() != FORMAT_VERSION) {
                    w.add(file.getFileName() + ": unknown version " + o.get("version") + ", read as version " + FORMAT_VERSION);
                }
                if (o.has("intended")) {
                    for (JsonElement e : o.getAsJsonArray("intended")) readEntry(e, w);
                }
            } catch (Exception e) {
                broken = true;
                markings = new ArrayList<>();
                invalidEntries = new ArrayList<>();
                topExtra = new JsonObject();
                w.add(file.getFileName() + " is broken (" + e.getMessage() + "): intended markings are ignored");
                LOG.warn("Packscope: {} is broken, intended markings are ignored", file, e);
            }
        }
        warnings = List.copyOf(w);
        changed();
    }

    private void readEntry(JsonElement e, List<String> w) {
        if (!e.isJsonObject()) {
            w.add(file.getFileName() + ": ignored entry " + e);
            JsonObject keep = new JsonObject();
            keep.add("value", e);
            invalidEntries.add(keep);
            return;
        }
        JsonObject entry = e.getAsJsonObject();
        String winner = string(entry, "winner");
        String loser = string(entry, "loser");
        if (winner == null || loser == null || winner.isBlank() || loser.isBlank()) {
            w.add(file.getFileName() + ": entry without winner/loser ignored: " + entry);
            invalidEntries.add(entry.deepCopy());
            return;
        }
        JsonObject extra = new JsonObject();
        for (Map.Entry<String, JsonElement> f : entry.entrySet()) {
            if (!f.getKey().equals("winner") && !f.getKey().equals("loser") && !f.getKey().equals("note")) extra.add(f.getKey(), f.getValue());
        }
        markings.add(new IntendedMarking(winner, loser, string(entry, "note"), extra));
    }

    @Nullable
    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    /** Reads the file again if size or last-modified changed (opening the pack screen). Returns true if it was read. */
    public synchronized boolean reloadIfChanged() {
        long[] stamp = stamp();
        if (stamp[0] == stampSize && stamp[1] == stampModified) return false;
        load();
        return true;
    }

    /**
     * Marks "{@code winnerId} over {@code loserId}" as intended (exact ids). Replaces a marking that says the opposite.
     * Returns false if it was already marked (then only a given note is updated).
     */
    public synchronized boolean mark(String winnerId, String loserId, @Nullable String note) {
        boolean changed = addMarking(winnerId, loserId, note);
        if (changed) save();
        return changed;
    }

    private boolean addMarking(String winnerId, String loserId, @Nullable String note) {
        boolean changed = markings.removeIf(m -> m.reverses(winnerId, loserId));
        for (int i = 0; i < markings.size(); i++) {
            IntendedMarking m = markings.get(i);
            if (m.covers(winnerId, loserId)) {
                if (note != null && !note.equals(m.note())) {
                    markings.set(i, new IntendedMarking(m.winner(), m.loser(), note, m.extra()));
                    changed = true;
                }
                return changed;
            }
        }
        markings.add(new IntendedMarking(winnerId, loserId, note));
        return true;
    }

    /** Removes every marking that says "{@code winnerId} over {@code loserId}". */
    public synchronized boolean unmark(String winnerId, String loserId) {
        boolean changed = markings.removeIf(m -> m.covers(winnerId, loserId));
        if (changed) save();
        return changed;
    }

    /**
     * Marks every not yet intended conflict pair of {@code packId} in its current direction (the pack over the packs it
     * overrides, the packs above it over the pack). Returns the number of new markings.
     */
    public synchronized int markAllOf(String packId, ScanResult result) {
        int added = 0;
        for (ScanResult.PairConflict p : result.pairs()) {
            if (p.intended() || !(p.upper().equals(packId) || p.lower().equals(packId))) continue;
            if (addMarking(p.upper(), p.lower(), null)) added++;
        }
        if (added > 0) save();
        return added;
    }

    /** Removes every marking that names {@code packId} as winner or loser. Returns the number removed. */
    public synchronized int unmarkAllOf(String packId) {
        int before = markings.size();
        markings.removeIf(m -> PackNames.matches(m.winner(), packId) || PackNames.matches(m.loser(), packId));
        int removed = before - markings.size();
        if (removed > 0) save();
        return removed;
    }

    /** Writes the file: temp file first, then replace. Sorted, indented, unknown fields kept. */
    private void save() {
        markings.sort(Comparator.comparing((IntendedMarking m) -> m.winner().toLowerCase(Locale.ROOT))
            .thenComparing(m -> m.loser().toLowerCase(Locale.ROOT)));
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonArray list = new JsonArray();
        for (IntendedMarking m : markings) {
            JsonObject o = new JsonObject();
            o.addProperty("winner", m.winner());
            o.addProperty("loser", m.loser());
            if (m.note() != null) o.addProperty("note", m.note());
            for (Map.Entry<String, JsonElement> e : m.extra().entrySet()) o.add(e.getKey(), e.getValue());
            list.add(o);
        }
        for (JsonObject raw : invalidEntries) list.add(raw.has("value") && raw.size() == 1 ? raw.get("value") : raw);
        root.add("intended", list);
        for (Map.Entry<String, JsonElement> e : topExtra.entrySet()) root.add(e.getKey(), e.getValue());
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (broken && Files.isRegularFile(file)) {
                Path backup = file.resolveSibling(file.getFileName() + ".broken");
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                LOG.warn("Packscope: kept the broken {} as {}", file.getFileName(), backup.getFileName());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
                w.write("\n");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            broken = false;
            warnings = List.of();
            long[] stamp = stamp();
            stampSize = stamp[0];
            stampModified = stamp[1];
        } catch (IOException e) {
            LOG.warn("Packscope: could not write {}", file, e);
            List<String> w = new ArrayList<>(warnings);
            w.add("could not write " + file.getFileName() + ": " + e.getMessage());
            warnings = List.copyOf(w);
        }
        changed();
    }

    private void changed() {
        version++;
        snapshot = new IntendedSet(markings, version, warnings);
    }

    /** Size and last-modified of the file, -1/-1 if missing. */
    private long[] stamp() {
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            return new long[]{a.size(), a.lastModifiedTime().toMillis()};
        } catch (IOException e) {
            return new long[]{-1, -1};
        }
    }
}
