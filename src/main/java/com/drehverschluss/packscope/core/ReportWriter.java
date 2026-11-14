package com.drehverschluss.packscope.core;

import com.drehverschluss.packscope.core.ScanResult.FileConflict;
import com.drehverschluss.packscope.core.ScanResult.FilteredFile;
import com.drehverschluss.packscope.core.ScanResult.LangConflict;
import com.drehverschluss.packscope.core.ScanResult.LangLoser;
import com.drehverschluss.packscope.core.ScanResult.MarkingState;
import com.drehverschluss.packscope.core.ScanResult.ModOverride;
import com.drehverschluss.packscope.core.ScanResult.OverrideType;
import com.drehverschluss.packscope.core.ScanResult.PackSummary;
import com.drehverschluss.packscope.core.ScanResult.PairConflict;
import com.drehverschluss.packscope.core.ScanResult.SoundReplacement;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Writes {@code packscope-report.md} (readable) and {@code packscope-report.json} (complete, for tools) (Konzept 8). */
public final class ReportWriter {
    public static final String MD_NAME = "packscope-report.md";
    public static final String JSON_NAME = "packscope-report.json";
    /** Language keys listed with texts per file and pack pair in the .md; the rest only in the .json. */
    static final int LANG_KEYS_SHOWN = 50;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private ReportWriter() {}

    /** Writes both files into {@code dir} and returns the path of the Markdown file. */
    public static Path write(ScanResult result, PackscopeSettings settings, String versions, Path dir) throws IOException {
        return write(result, settings, versions, List.of(), dir);
    }

    /**
     * Writes both files into {@code dir} and returns the path of the Markdown file.
     *
     * @param notes extra lines for the head of the report, e.g. that the order from the pack screen is not applied yet
     */
    public static Path write(ScanResult result, PackscopeSettings settings, String versions, List<String> notes, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path md = dir.resolve(MD_NAME);
        writeAtomic(md, markdown(result, settings, versions, notes));
        writeAtomic(dir.resolve(JSON_NAME), GSON.toJson(json(result, settings, versions, notes)));
        return md;
    }

    private static void writeAtomic(Path target, String text) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(text);
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------------------------------------ markdown

    public static String markdown(ScanResult r, PackscopeSettings settings, String versions) {
        return markdown(r, settings, versions, List.of());
    }

    /** @param notes extra lines for the head of the report */
    public static String markdown(ScanResult r, PackscopeSettings settings, String versions, List<String> notes) {
        Map<String, String> names = names(r);
        StringBuilder sb = new StringBuilder();
        List<PackSummary> rps = r.packs().stream().filter(p -> p.role() == PackRole.RESOURCE_PACK).toList();
        sb.append("# Packscope report\n\n");
        for (String note : notes) sb.append("> **Note:** ").append(note).append("\n\n");
        sb.append("- Generated: ").append(TIME.format(r.created())).append('\n');
        sb.append("- ").append(versions).append('\n');
        sb.append("- Active packs: ").append(rps.size()).append(" resource packs, ")
            .append(r.packs().size() - rps.size()).append(" mod/vanilla packs\n");
        long fileCount = r.fileConflicts().stream().filter(c -> !c.losers().isEmpty() || !c.dead().isEmpty()).count();
        sb.append("- Conflicts: ").append(r.conflictCount()).append(" (intended: ").append(r.intendedCount()).append(") – files ")
            .append(fileCount).append(", language keys ").append(r.langConflicts().size()).append(", replaced sound events ")
            .append(r.soundReplacements().size()).append('\n');
        List<PackSummary> covered = r.coveredPacks();
        sb.append("- Completely covered packs: ").append(covered.isEmpty() ? "none"
            : covered.stream().map(PackSummary::title).collect(Collectors.joining(", "))).append('\n');
        List<PackSummary> against = rps.stream().filter(PackSummary::againstMarking).toList();
        if (!against.isEmpty()) {
            sb.append("- Against marking: ").append(against.stream().map(PackSummary::title).collect(Collectors.joining(", "))).append('\n');
        }
        sb.append("- Languages: ").append(r.order().languages().isEmpty() ? "all" : String.join(", ", r.order().languages())).append('\n');
        sb.append("- Scan: ").append(ms(r.scanNanos())).append(" ms, evaluation: ").append(ms(r.resolveNanos())).append(" ms\n");
        sb.append("- Settings: ignoreIdentical=").append(settings.ignoreIdentical()).append(", ignoredPaths=")
            .append(settings.ignoredPaths()).append(", allLanguages=").append(settings.allLanguages()).append("\n\n");

        sb.append("## Load order (top = highest priority)\n\n");
        sb.append("| # | Pack | Id | Type | Scan | Files | Status | Wins | Loses | Notes |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        int n = 1;
        for (PackSummary p : r.packs()) {
            boolean rp = p.role() == PackRole.RESOURCE_PACK;
            sb.append("| ").append(n++).append(" | ").append(cell(p.title())).append(" | `").append(cell(p.id())).append("` | ")
                .append(roleLabel(p)).append(" | ").append(p.kind().id()).append(p.approximate() ? " (approximate)" : "").append(" | ")
                .append(p.fileCount()).append(" | ")
                .append(rp ? p.status().label() : "–").append(" | ")
                .append(rp ? p.wins() : "").append(" | ")
                .append(rp ? p.losses() : "").append(" | ")
                .append(cell(notes(p, names))).append(" |\n");
        }
        sb.append('\n');

        if (!covered.isEmpty()) {
            sb.append("## Completely covered packs\n\n");
            for (PackSummary p : covered) {
                sb.append("- **").append(p.title()).append("** – nothing of this pack reaches the game. ");
                if (p.onlyCopies()) {
                    sb.append("Only copies of ").append(top(p.copiesOf(), names, 3)).append('\n');
                } else {
                    List<String> parts = new ArrayList<>();
                    if (!p.losesTo().isEmpty()) parts.add("overridden by " + top(p.losesTo(), names, 3));
                    if (!p.copiesOf().isEmpty()) parts.add("copies of " + top(p.copiesOf(), names, 3));
                    if (p.hiddenByFilter() > 0) parts.add(p.hiddenByFilter() + " hidden by filters");
                    if (p.dead() > 0) parts.add(p.dead() + " .mcmeta without effect");
                    if (p.lostToMods() > 0) parts.add(p.lostToMods() + " overridden by mods");
                    sb.append(capitalize(String.join(", ", parts))).append('\n');
                }
            }
            sb.append('\n');
        }

        sb.append("## Pack against pack\n\n");
        List<PairConflict> open = r.pairs().stream().filter(p -> !p.intended()).toList();
        if (open.isEmpty()) sb.append("No conflicts between resource packs outside the intended pairs.\n");
        for (PairConflict p : open) {
            sb.append("- **").append(names.get(p.upper())).append("** over **").append(names.get(p.lower())).append("**: ")
                .append(pairCounts(p));
            if (p.againstMarking()) sb.append(" – **against marking** (intended is the other way round)");
            sb.append('\n');
        }
        sb.append('\n');

        markingSection(sb, r, names);

        sb.append("## File conflicts\n\n");
        List<FileConflict> real = r.fileConflicts().stream().filter(c -> (!c.losers().isEmpty() || !c.dead().isEmpty()) && !c.intended()).toList();
        if (real.isEmpty()) sb.append("None outside the intended pairs.\n");
        for (FileConflict c : real) {
            sb.append("- `").append(c.location()).append("` – **").append(names.get(c.winner())).append("**");
            if (!c.losers().isEmpty()) sb.append(" over ").append(list(c.losers(), names));
            if (!c.dead().isEmpty()) sb.append("; .mcmeta without effect in ").append(list(c.dead(), names));
            if (!c.harmless().isEmpty()) sb.append("; identical in ").append(list(c.harmless(), names));
            if (c.note() != null) sb.append(" _(").append(c.note()).append(")_");
            sb.append('\n');
        }
        sb.append('\n');

        langSection(sb, r, names);

        sb.append("## Replaced sound events\n\n");
        List<SoundReplacement> sounds = r.soundReplacements().stream().filter(s -> !s.intended()).toList();
        if (sounds.isEmpty()) sb.append("None outside the intended pairs.\n");
        for (SoundReplacement s : sounds) {
            sb.append("- `").append(s.event()).append("` – **").append(names.get(s.pack())).append("** replaces the sounds of ")
                .append(list(s.replacedPacks(), names)).append(" (silenced: ").append(String.join(", ", s.lostSounds())).append(")\n");
        }
        sb.append('\n');

        sb.append("## Hidden by filters\n\n");
        List<FilteredFile> filtered = r.filtered().stream().filter(f -> !f.intended()).toList();
        if (filtered.isEmpty()) sb.append("None outside the intended pairs.\n");
        for (FilteredFile f : filtered) {
            sb.append("- `").append(f.location()).append("` – hidden by the filter of **").append(names.get(f.filterPack()))
                .append("** in ").append(list(f.hiddenPacks(), names)).append('\n');
        }
        sb.append('\n');

        sb.append("## Harmless duplicates (identical files)\n\n");
        List<FileConflict> dups = r.fileConflicts().stream().filter(c -> !c.harmless().isEmpty()).toList();
        if (dups.isEmpty()) sb.append(settings.ignoreIdentical() ? "None.\n" : "Not checked (ignoreIdentical is off).\n");
        Map<String, Integer> dupPairs = new LinkedHashMap<>();
        for (FileConflict c : dups) {
            for (String h : c.harmless()) dupPairs.merge(names.get(h) + "\u0000" + names.get(c.winner()), 1, Integer::sum);
        }
        dupPairs.forEach((k, v) -> {
            String[] parts = k.split("\u0000");
            sb.append("- **").append(parts[0]).append("** has ").append(v).append(" file").append(v == 1 ? "" : "s")
                .append(" identical to **").append(parts[1]).append("**\n");
        });
        if (!dups.isEmpty() && dups.size() <= 200) {
            sb.append('\n');
            for (FileConflict c : dups) {
                sb.append("  - `").append(c.location()).append("` – used from ").append(names.get(c.winner())).append(", identical in ")
                    .append(list(c.harmless(), names)).append('\n');
            }
        } else if (!dups.isEmpty()) {
            sb.append("\nFull list in ").append(JSON_NAME).append(".\n");
        }
        sb.append('\n');

        modSection(sb, r, settings, names);

        if (!r.warnings().isEmpty()) {
            sb.append("## Warnings\n\n");
            List<String> ids = r.order().packs().stream().map(PackRef::id).toList();
            for (String w : groupWarnings(r.warnings(), ids)) sb.append("- ").append(w).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void markingSection(StringBuilder sb, ScanResult r, Map<String, String> names) {
        sb.append("## Intended (marked as wanted)\n\n");
        List<PairConflict> intended = r.pairs().stream().filter(PairConflict::intended).toList();
        if (intended.isEmpty() && r.markings().isEmpty()) {
            sb.append("No markings (`config/packscope-intended.json`).\n\n");
            return;
        }
        for (PairConflict p : intended) {
            sb.append("- **").append(names.get(p.upper())).append("** over **").append(names.get(p.lower())).append("**: ")
                .append(pairCounts(p)).append('\n');
        }
        if (!r.markings().isEmpty()) {
            sb.append("\n| Marking | State | Conflicts | Note |\n|---|---|---|---|\n");
            for (MarkingState m : r.markings()) {
                sb.append("| `").append(cell(m.marking().winner())).append("` over `").append(cell(m.marking().loser())).append("` | ")
                    .append(stateLabel(m)).append(" | ").append(m.items()).append(" | ")
                    .append(cell(m.marking().note() == null ? "" : m.marking().note())).append(" |\n");
            }
        }
        sb.append('\n');
    }

    private static String stateLabel(ScanResult.MarkingState m) {
        return switch (m.status()) {
            case WORKS -> "works";
            case REVERSED -> "**reversed**";
            case PACK_NOT_ACTIVE -> "pack not active";
            case NO_CONFLICT -> "no conflict any more";
        };
    }

    private static void langSection(StringBuilder sb, ScanResult r, Map<String, String> names) {
        sb.append("## Language key conflicts\n\n");
        // group by file + pack pair (winner, loser)
        Map<String, List<String[]>> groups = new LinkedHashMap<>();
        for (LangConflict c : r.langConflicts()) {
            if (c.intended()) continue;
            for (LangLoser l : c.losers()) {
                groups.computeIfAbsent(c.location() + "\u0000" + c.winner() + "\u0000" + l.pack(), k -> new ArrayList<>())
                    .add(new String[]{c.key(), c.winnerText(), l.text()});
            }
        }
        if (groups.isEmpty()) sb.append("None outside the intended pairs.\n");
        groups.forEach((k, keys) -> {
            String[] g = k.split("\u0000");
            sb.append("### `").append(g[0]).append("` – **").append(names.get(g[1])).append("** over **").append(names.get(g[2]))
                .append("** (").append(keys.size()).append(keys.size() == 1 ? " key" : " keys").append(")\n\n");
            int shown = Math.min(LANG_KEYS_SHOWN, keys.size());
            for (int i = 0; i < shown; i++) {
                String[] e = keys.get(i);
                sb.append("- `").append(e[0]).append("`: \"").append(shorten(e[1])).append("\" over \"").append(shorten(e[2])).append("\"\n");
            }
            if (keys.size() > shown) {
                sb.append("- … ").append(keys.size() - shown).append(" more (full list in ").append(JSON_NAME).append(")\n");
            }
            sb.append('\n');
        });
        if (groups.isEmpty()) sb.append('\n');
    }

    private static void modSection(StringBuilder sb, ScanResult r, PackscopeSettings settings, Map<String, String> names) {
        sb.append("## Replaces mod and vanilla resources (info)\n\n");
        sb.append("Normal for resource packs, not a conflict. Counted per file, language key or sound event.\n\n");
        Map<String, Map<String, int[]>> table = new TreeMap<>();
        Map<String, String> ownerNames = new HashMap<>();
        List<ModOverride> reversed = new ArrayList<>();
        for (ModOverride m : r.modOverrides()) {
            if (m.reversed()) {
                reversed.add(m);
                continue;
            }
            ownerNames.put(m.ownerKey(), m.ownerName());
            table.computeIfAbsent(m.pack(), k -> new TreeMap<>()).computeIfAbsent(m.ownerKey(), k -> new int[3])[m.type().ordinal()] += m.count();
        }
        if (table.isEmpty()) {
            sb.append("None.\n\n");
        } else {
            sb.append("| Pack | Mod | Files | Language keys | Sound events |\n|---|---|---|---|---|\n");
            for (PackSummary p : r.packs()) {
                Map<String, int[]> owners = table.get(p.id());
                if (owners == null) continue;
                for (Map.Entry<String, int[]> e : owners.entrySet()) {
                    int[] c = e.getValue();
                    sb.append("| ").append(cell(p.title())).append(" | ").append(cell(ownerNames.get(e.getKey()))).append(" (`")
                        .append(e.getKey()).append("`) | ").append(c[0]).append(" | ").append(c[1]).append(" | ").append(c[2]).append(" |\n");
                }
            }
            sb.append('\n');
        }
        if (!reversed.isEmpty()) {
            sb.append("### Overridden by mod or vanilla resources (pack placed below them)\n\n");
            for (ModOverride m : reversed) {
                sb.append("- `").append(m.location()).append("` of **").append(names.get(m.pack())).append("** loses to ")
                    .append(m.ownerName()).append(" (").append(typeLabel(m)).append(")\n");
            }
            sb.append('\n');
        }
        if (settings.reportListModOverrides() && !table.isEmpty()) {
            sb.append("### Full list\n\n");
            for (ModOverride m : r.modOverrides()) {
                if (m.reversed()) continue;
                sb.append("- ").append(names.get(m.pack())).append(": `").append(m.location()).append("` (").append(m.ownerName())
                    .append(", ").append(typeLabel(m)).append(")\n");
            }
            sb.append('\n');
        } else if (!table.isEmpty()) {
            sb.append("Full list: set `reportListModOverrides = true` in `config/packscope-client.toml`.\n\n");
        }
    }

    private static String typeLabel(ModOverride m) {
        return switch (m.type()) {
            case FILE -> "file";
            case LANG_KEY -> m.count() + " language key" + (m.count() == 1 ? "" : "s");
            case SOUND_EVENT -> "sound event";
        };
    }

    private static String pairCounts(PairConflict p) {
        List<String> parts = new ArrayList<>();
        if (p.files() > 0) parts.add(p.files() + (p.files() == 1 ? " file" : " files"));
        if (p.langKeys() > 0) parts.add(p.langKeys() + (p.langKeys() == 1 ? " language key" : " language keys"));
        if (p.soundEvents() > 0) parts.add(p.soundEvents() + (p.soundEvents() == 1 ? " replaced sound event" : " replaced sound events"));
        if (p.filtered() > 0) parts.add(p.filtered() + " hidden by filter");
        return String.join(", ", parts);
    }

    private static Map<String, String> names(ScanResult r) {
        Map<String, String> names = new HashMap<>();
        for (PackSummary p : r.packs()) names.put(p.id(), p.title());
        return names;
    }

    private static String notes(PackSummary p, Map<String, String> names) {
        List<String> n = new ArrayList<>();
        if (p.role() == PackRole.RESOURCE_PACK) {
            if (p.hidden()) n.add("hidden (not in the pack screen)");
            if (p.injected()) n.add("injected (not in the pack screen)" + (p.injectedBy() != null ? " by " + p.injectedBy() : ""));
            if (p.parentId() != null) n.add("part of " + p.parentId());
            if (p.onlyCopies()) n.add("only copies of " + top(p.copiesOf(), names, 3));
            else if (p.copies() > 0) n.add(p.copies() + " identical");
            if (p.againstMarking()) n.add("against marking");
            if (p.intendedWins() + p.intendedLosses() > 0) {
                n.add("intended: " + p.intendedWins() + " wins, " + p.intendedLosses() + " losses");
            }
            if (p.hiddenByFilter() > 0) n.add(p.hiddenByFilter() + " hidden by filter");
            if (p.hidesOthers() > 0) n.add("hides " + p.hidesOthers() + " (filter)");
            if (p.dead() > 0) n.add(p.dead() + " .mcmeta without effect");
            if (p.replacedSoundEvents() > 0) n.add("replaces " + p.replacedSoundEvents() + " sound events");
            if (p.intendedSoundEvents() > 0) n.add("replaces " + p.intendedSoundEvents() + " sound events (intended)");
            if (p.lostSoundEvents() > 0) n.add(p.lostSoundEvents() + " sound events replaced");
            if (p.modOverrides() > 0) n.add("replaces " + p.modOverrides() + " mod/vanilla items");
            if (p.lostToMods() > 0) n.add(p.lostToMods() + " overridden by mods");
        } else if (p.injected()) {
            n.add("injected (not in the pack screen)" + (p.injectedBy() != null ? " by " + p.injectedBy() : ""));
        } else if (p.parentId() != null) {
            n.add("part of " + p.parentId());
        }
        return String.join(", ", n);
    }

    private static String roleLabel(PackSummary p) {
        return switch (p.role()) {
            case RESOURCE_PACK -> p.injected() ? "resource pack (injected)" : p.hidden() ? "resource pack (hidden)" : "resource pack";
            case MOD -> "mod" + (p.modId() != null ? " `" + p.modId() + "`" : "");
            case VANILLA -> "vanilla";
        };
    }

    /**
     * Warnings of the report, one line per kind (Phase 5C): warnings are "{@code <pack id>: <message>}"; the same message
     * of several packs becomes "{@code <message>: N packs – a, b, c}". The fallback-scan note gets a short label.
     */
    static List<String> groupWarnings(List<String> warnings, Collection<String> packIds) {
        List<String> ids = new ArrayList<>(packIds);
        ids.sort((a, b) -> b.length() - a.length()); // longest first: ids may contain ": "
        Map<String, List<String>> byMessage = new LinkedHashMap<>();
        List<String> other = new ArrayList<>();
        for (String w : warnings) {
            String pack = null;
            for (String id : ids) {
                if (w.startsWith(id + ": ")) {
                    pack = id;
                    break;
                }
            }
            if (pack == null) {
                other.add(w);
                continue;
            }
            byMessage.computeIfAbsent(w.substring(pack.length() + 2), k -> new ArrayList<>()).add(pack);
        }
        List<String> out = new ArrayList<>(other);
        for (Map.Entry<String, List<String>> e : byMessage.entrySet()) {
            String msg = e.getKey();
            List<String> packs = e.getValue();
            if (packs.size() == 1) {
                out.add(packs.get(0) + ": " + msg);
                continue;
            }
            String label = msg.startsWith("approximate: ") ? "approximate scan (fallback, " + msg.substring("approximate: ".length()) + ")" : msg;
            out.add(label + ": " + packs.size() + " packs – " + String.join(", ", packs));
        }
        return out;
    }

    /** The {@code max} largest entries, "+ N more packs" for the rest. */
    private static String top(Map<String, Integer> m, Map<String, String> names, int max) {
        List<Map.Entry<String, Integer>> sorted = m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).toList();
        String shown = sorted.stream().limit(max).map(e -> names.getOrDefault(e.getKey(), e.getKey()) + " (" + e.getValue() + ")")
            .collect(Collectors.joining(", "));
        return sorted.size() > max ? shown + " + " + (sorted.size() - max) + " more packs" : shown;
    }

    private static String list(List<String> ids, Map<String, String> names) {
        return ids.stream().map(i -> names.getOrDefault(i, i)).collect(Collectors.joining(", "));
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".";
    }

    private static String shorten(String s) {
        String t = s.replace("\n", "\\n");
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    // ------------------------------------------------------------------------------------------------ json

    public static JsonObject json(ScanResult r, PackscopeSettings settings, String versions) {
        return json(r, settings, versions, List.of());
    }

    /** @param notes extra lines for the head of the report ({@code "notes"}) */
    public static JsonObject json(ScanResult r, PackscopeSettings settings, String versions, List<String> notes) {
        JsonObject root = new JsonObject();
        root.addProperty("generated", r.created().toString());
        root.addProperty("versions", versions);
        if (!notes.isEmpty()) root.add("notes", strings(notes));
        root.addProperty("scanMillis", r.scanNanos() / 1_000_000.0);
        root.addProperty("evaluationMillis", r.resolveNanos() / 1_000_000.0);
        root.addProperty("conflicts", r.conflictCount());
        root.addProperty("intendedConflicts", r.intendedCount());
        root.add("languages", strings(r.order().languages()));
        JsonObject s = new JsonObject();
        s.addProperty("ignoreIdentical", settings.ignoreIdentical());
        s.add("ignoredPaths", strings(settings.ignoredPaths()));
        s.addProperty("allLanguages", settings.allLanguages());
        root.add("settings", s);

        JsonArray order = new JsonArray();
        for (PackSummary p : r.packs()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id());
            o.addProperty("title", p.title());
            o.addProperty("role", p.role().name().toLowerCase(Locale.ROOT));
            o.addProperty("scan", p.kind().id());
            o.addProperty("approximate", p.approximate());
            o.addProperty("hidden", p.hidden());
            o.addProperty("injected", p.injected());
            if (p.injectedBy() != null) o.addProperty("injectedBy", p.injectedBy());
            o.addProperty("files", p.fileCount());
            if (p.modId() != null) o.addProperty("modId", p.modId());
            if (p.parentId() != null) o.addProperty("parent", p.parentId());
            if (p.role() == PackRole.RESOURCE_PACK) {
                o.addProperty("status", p.status().id());
                o.addProperty("wins", p.wins());
                o.addProperty("losses", p.losses());
                o.addProperty("intendedWins", p.intendedWins());
                o.addProperty("intendedLosses", p.intendedLosses());
                o.addProperty("againstMarking", p.againstMarking());
                o.addProperty("deadMcmeta", p.dead());
                o.addProperty("copies", p.copies());
                o.addProperty("onlyCopies", p.onlyCopies());
                o.addProperty("hiddenByFilter", p.hiddenByFilter());
                o.addProperty("hidesOthers", p.hidesOthers());
                o.addProperty("replacedSoundEvents", p.replacedSoundEvents());
                o.addProperty("intendedSoundEvents", p.intendedSoundEvents());
                o.addProperty("lostSoundEvents", p.lostSoundEvents());
                o.addProperty("lostToMods", p.lostToMods());
                o.addProperty("modOverrides", p.modOverrides());
                o.add("winsAgainst", counts(p.winsAgainst()));
                o.add("losesTo", counts(p.losesTo()));
                o.add("copiesOf", counts(p.copiesOf()));
                o.add("modOverridesByMod", counts(p.modOverridesByOwner()));
            }
            order.add(o);
        }
        root.add("order", order);

        JsonArray pairs = new JsonArray();
        for (PairConflict p : r.pairs()) {
            JsonObject o = new JsonObject();
            o.addProperty("upper", p.upper());
            o.addProperty("lower", p.lower());
            o.addProperty("files", p.files());
            o.addProperty("langKeys", p.langKeys());
            o.addProperty("soundEvents", p.soundEvents());
            o.addProperty("filtered", p.filtered());
            o.addProperty("intended", p.intended());
            o.addProperty("againstMarking", p.againstMarking());
            pairs.add(o);
        }
        root.add("pairs", pairs);

        JsonArray markings = new JsonArray();
        for (MarkingState m : r.markings()) {
            JsonObject o = new JsonObject();
            o.addProperty("winner", m.marking().winner());
            o.addProperty("loser", m.marking().loser());
            if (m.marking().note() != null) o.addProperty("note", m.marking().note());
            o.addProperty("state", m.status().id());
            if (m.winnerPack() != null) o.addProperty("winnerPack", m.winnerPack());
            if (m.loserPack() != null) o.addProperty("loserPack", m.loserPack());
            o.addProperty("conflicts", m.items());
            markings.add(o);
        }
        root.add("markings", markings);

        JsonArray files = new JsonArray();
        for (FileConflict c : r.fileConflicts()) {
            JsonObject o = new JsonObject();
            o.addProperty("location", c.location());
            o.addProperty("kind", c.kind().id());
            o.addProperty("winner", c.winner());
            o.add("losers", strings(c.losers()));
            o.add("harmless", strings(c.harmless()));
            o.add("deadMcmeta", strings(c.dead()));
            o.addProperty("intended", c.intended());
            if (c.note() != null) o.addProperty("note", c.note());
            files.add(o);
        }
        root.add("fileConflicts", files);

        JsonArray lang = new JsonArray();
        for (LangConflict c : r.langConflicts()) {
            JsonObject o = new JsonObject();
            o.addProperty("location", c.location());
            o.addProperty("language", c.language());
            o.addProperty("key", c.key());
            o.addProperty("winner", c.winner());
            o.addProperty("winnerText", c.winnerText());
            JsonArray losers = new JsonArray();
            for (LangLoser l : c.losers()) {
                JsonObject x = new JsonObject();
                x.addProperty("pack", l.pack());
                x.addProperty("text", l.text());
                losers.add(x);
            }
            o.add("losers", losers);
            o.addProperty("intended", c.intended());
            lang.add(o);
        }
        root.add("langConflicts", lang);

        JsonArray sounds = new JsonArray();
        for (SoundReplacement x : r.soundReplacements()) {
            JsonObject o = new JsonObject();
            o.addProperty("event", x.event());
            o.addProperty("pack", x.pack());
            o.add("replacedPacks", strings(x.replacedPacks()));
            o.add("lostSounds", strings(x.lostSounds()));
            o.addProperty("intended", x.intended());
            sounds.add(o);
        }
        root.add("soundReplacements", sounds);

        JsonArray filtered = new JsonArray();
        for (FilteredFile f : r.filtered()) {
            JsonObject o = new JsonObject();
            o.addProperty("location", f.location());
            o.addProperty("filterPack", f.filterPack());
            o.add("hiddenPacks", strings(f.hiddenPacks()));
            o.addProperty("intended", f.intended());
            filtered.add(o);
        }
        root.add("filtered", filtered);

        JsonObject mods = new JsonObject();
        Map<String, Map<String, int[]>> table = new TreeMap<>();
        for (ModOverride m : r.modOverrides()) {
            if (m.reversed()) continue;
            table.computeIfAbsent(m.pack(), k -> new TreeMap<>()).computeIfAbsent(m.ownerKey(), k -> new int[3])[m.type().ordinal()] += m.count();
        }
        table.forEach((pack, owners) -> {
            JsonObject po = new JsonObject();
            owners.forEach((owner, c) -> {
                JsonObject oo = new JsonObject();
                oo.addProperty("files", c[OverrideType.FILE.ordinal()]);
                oo.addProperty("langKeys", c[OverrideType.LANG_KEY.ordinal()]);
                oo.addProperty("soundEvents", c[OverrideType.SOUND_EVENT.ordinal()]);
                po.add(owner, oo);
            });
            mods.add(pack, po);
        });
        root.add("modOverrides", mods);
        JsonArray modList = new JsonArray();
        for (ModOverride m : r.modOverrides()) {
            if (!m.reversed() && !settings.reportListModOverrides()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("pack", m.pack());
            o.addProperty("location", m.location());
            o.addProperty("target", m.target());
            o.addProperty("mod", m.ownerKey());
            o.addProperty("type", m.type().name().toLowerCase(Locale.ROOT));
            o.addProperty("reversed", m.reversed());
            o.addProperty("count", m.count());
            modList.add(o);
        }
        root.add("modOverrideList", modList);
        root.add("warnings", strings(r.warnings()));
        return root;
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    private static JsonObject counts(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }
}
