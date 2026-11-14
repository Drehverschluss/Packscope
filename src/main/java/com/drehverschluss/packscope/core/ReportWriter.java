package com.drehverschluss.packscope.core;

import com.drehverschluss.packscope.core.ScanResult.FileConflict;
import com.drehverschluss.packscope.core.ScanResult.FilteredFile;
import com.drehverschluss.packscope.core.ScanResult.LangConflict;
import com.drehverschluss.packscope.core.ScanResult.LangLoser;
import com.drehverschluss.packscope.core.ScanResult.ModOverride;
import com.drehverschluss.packscope.core.ScanResult.OverrideType;
import com.drehverschluss.packscope.core.ScanResult.PackSummary;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Writes {@code packscope-report.md} (readable) and {@code packscope-report.json} (for tools) (Konzept 8). */
public final class ReportWriter {
    public static final String MD_NAME = "packscope-report.md";
    public static final String JSON_NAME = "packscope-report.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private ReportWriter() {}

    /** Writes both files into {@code dir} and returns the path of the Markdown file. */
    public static Path write(ScanResult result, PackscopeSettings settings, String versions, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path md = dir.resolve(MD_NAME);
        writeAtomic(md, markdown(result, settings, versions));
        writeAtomic(dir.resolve(JSON_NAME), GSON.toJson(json(result, settings, versions)));
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
        Map<String, String> names = names(r);
        StringBuilder sb = new StringBuilder();
        List<PackSummary> rps = r.packs().stream().filter(p -> p.role() == PackRole.RESOURCE_PACK).toList();
        sb.append("# Packscope report\n\n");
        sb.append("- Generated: ").append(TIME.format(r.created())).append('\n');
        sb.append("- ").append(versions).append('\n');
        sb.append("- Active packs: ").append(rps.size()).append(" resource packs, ")
            .append(r.packs().size() - rps.size()).append(" mod/vanilla packs\n");
        long fileCount = r.fileConflicts().stream().filter(c -> !c.losers().isEmpty() || !c.dead().isEmpty()).count();
        sb.append("- Conflicts: ").append(r.conflictCount()).append(" (files ").append(fileCount).append(", language keys ")
            .append(r.langConflicts().size()).append(", replaced sound events ").append(r.soundReplacements().size()).append(")\n");
        List<PackSummary> covered = r.coveredPacks();
        sb.append("- Completely covered packs: ").append(covered.isEmpty() ? "none"
            : covered.stream().map(p -> p.title()).collect(Collectors.joining(", "))).append('\n');
        sb.append("- Scan: ").append(ms(r.scanNanos())).append(" ms, evaluation: ").append(ms(r.resolveNanos())).append(" ms\n");
        sb.append("- Settings: ignoreIdentical=").append(settings.ignoreIdentical()).append(", ignoredPaths=")
            .append(settings.ignoredPaths()).append("\n\n");

        sb.append("## Load order (top = highest priority)\n\n");
        sb.append("| # | Pack | Id | Type | Scan | Files | Status | Wins | Loses | Notes |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        int n = 1;
        for (PackSummary p : r.packs()) {
            sb.append("| ").append(n++).append(" | ").append(cell(p.title())).append(" | `").append(cell(p.id())).append("` | ")
                .append(roleLabel(p)).append(" | ").append(p.kind().id()).append(p.approximate() ? " (approximate)" : "").append(" | ")
                .append(p.fileCount()).append(" | ")
                .append(p.role() == PackRole.RESOURCE_PACK ? p.status().label() : "–").append(" | ")
                .append(p.role() == PackRole.RESOURCE_PACK ? p.wins() : "").append(" | ")
                .append(p.role() == PackRole.RESOURCE_PACK ? p.losses() : "").append(" | ")
                .append(cell(notes(p))).append(" |\n");
        }
        sb.append('\n');

        if (!covered.isEmpty()) {
            sb.append("## Completely covered packs\n\n");
            for (PackSummary p : covered) {
                sb.append("- **").append(p.title()).append("** – nothing of this pack is used. Overridden by: ")
                    .append(counts(p.losesTo(), names)).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Pack against pack\n\n");
        boolean any = false;
        for (PackSummary p : rps) {
            if (p.winsAgainst().isEmpty() && p.losesTo().isEmpty()) continue;
            any = true;
            sb.append("- **").append(p.title()).append("**");
            if (!p.winsAgainst().isEmpty()) sb.append(" – overrides ").append(counts(p.winsAgainst(), names));
            if (!p.losesTo().isEmpty()) sb.append(" – overridden by ").append(counts(p.losesTo(), names));
            sb.append('\n');
        }
        sb.append(any ? "\n" : "No conflicts between resource packs.\n\n");

        sb.append("## File conflicts\n\n");
        List<FileConflict> real = r.fileConflicts().stream().filter(c -> !c.losers().isEmpty() || !c.dead().isEmpty()).toList();
        if (real.isEmpty()) sb.append("None.\n");
        for (FileConflict c : real) {
            sb.append("- `").append(c.location()).append("` – **").append(names.get(c.winner())).append("**");
            if (!c.losers().isEmpty()) sb.append(" over ").append(list(c.losers(), names));
            if (!c.dead().isEmpty()) sb.append("; .mcmeta without effect in ").append(list(c.dead(), names));
            if (!c.harmless().isEmpty()) sb.append("; identical in ").append(list(c.harmless(), names));
            if (c.note() != null) sb.append(" _(").append(c.note()).append(")_");
            sb.append('\n');
        }
        sb.append('\n');

        sb.append("## Language key conflicts\n\n");
        if (r.langConflicts().isEmpty()) sb.append("None.\n");
        for (LangConflict c : r.langConflicts()) {
            sb.append("- `").append(c.location()).append("` `").append(c.key()).append("` – **").append(names.get(c.winner()))
                .append("** \"").append(shorten(c.winnerText())).append("\" over ");
            List<String> l = new ArrayList<>();
            for (LangLoser x : c.losers()) l.add(names.get(x.pack()) + " \"" + shorten(x.text()) + "\"");
            sb.append(String.join(", ", l)).append('\n');
        }
        sb.append('\n');

        sb.append("## Replaced sound events\n\n");
        if (r.soundReplacements().isEmpty()) sb.append("None.\n");
        for (SoundReplacement s : r.soundReplacements()) {
            sb.append("- `").append(s.event()).append("` – **").append(names.get(s.pack())).append("** replaces the sounds of ")
                .append(list(s.replacedPacks(), names)).append(" (silenced: ").append(String.join(", ", s.lostSounds())).append(")\n");
        }
        sb.append('\n');

        sb.append("## Hidden by filters\n\n");
        if (r.filtered().isEmpty()) sb.append("None.\n");
        for (FilteredFile f : r.filtered()) {
            sb.append("- `").append(f.location()).append("` – hidden by the filter of **").append(names.get(f.filterPack()))
                .append("** in ").append(list(f.hiddenPacks(), names)).append('\n');
        }
        sb.append('\n');

        sb.append("## Harmless duplicates (identical files)\n\n");
        List<FileConflict> dups = r.fileConflicts().stream().filter(c -> !c.harmless().isEmpty()).toList();
        if (dups.isEmpty()) sb.append(settings.ignoreIdentical() ? "None.\n" : "Not checked (ignoreIdentical is off).\n");
        for (FileConflict c : dups) {
            sb.append("- `").append(c.location()).append("` – used from **").append(names.get(c.winner())).append("**, identical in ")
                .append(list(c.harmless(), names)).append('\n');
        }
        sb.append('\n');

        modSection(sb, r, settings, names);

        if (!r.warnings().isEmpty()) {
            sb.append("## Warnings\n\n");
            for (String w : r.warnings()) sb.append("- ").append(w).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void modSection(StringBuilder sb, ScanResult r, PackscopeSettings settings, Map<String, String> names) {
        sb.append("## Replaces mod and vanilla resources (info)\n\n");
        sb.append("Normal for resource packs, not a conflict. Counted per file, language key or sound event.\n\n");
        // pack -> owner -> type -> count
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

    private static Map<String, String> names(ScanResult r) {
        Map<String, String> names = new HashMap<>();
        for (PackSummary p : r.packs()) names.put(p.id(), p.title());
        return names;
    }

    private static String notes(PackSummary p) {
        List<String> n = new ArrayList<>();
        if (p.role() == PackRole.RESOURCE_PACK) {
            if (p.harmless() > 0) n.add(p.harmless() + " identical");
            if (p.hiddenByFilter() > 0) n.add(p.hiddenByFilter() + " hidden by filter");
            if (p.hidesOthers() > 0) n.add("hides " + p.hidesOthers() + " (filter)");
            if (p.dead() > 0) n.add(p.dead() + " .mcmeta without effect");
            if (p.replacedSoundEvents() > 0) n.add("replaces " + p.replacedSoundEvents() + " sound events");
            if (p.lostSoundEvents() > 0) n.add(p.lostSoundEvents() + " sound events replaced");
            if (p.modOverrides() > 0) n.add("replaces " + p.modOverrides() + " mod/vanilla items");
            if (p.lostToMods() > 0) n.add(p.lostToMods() + " overridden by mods");
        } else if (p.parentId() != null) {
            n.add("part of " + p.parentId());
        }
        return String.join(", ", n);
    }

    private static String roleLabel(PackSummary p) {
        return switch (p.role()) {
            case RESOURCE_PACK -> "resource pack";
            case MOD -> "mod" + (p.modId() != null ? " `" + p.modId() + "`" : "");
            case VANILLA -> "vanilla";
        };
    }

    private static String counts(Map<String, Integer> m, Map<String, String> names) {
        return m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
            .map(e -> names.getOrDefault(e.getKey(), e.getKey()) + " (" + e.getValue() + ")").collect(Collectors.joining(", "));
    }

    private static String list(List<String> ids, Map<String, String> names) {
        return ids.stream().map(i -> names.getOrDefault(i, i)).collect(Collectors.joining(", "));
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
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
        JsonObject root = new JsonObject();
        root.addProperty("generated", r.created().toString());
        root.addProperty("versions", versions);
        root.addProperty("scanMillis", r.scanNanos() / 1_000_000.0);
        root.addProperty("evaluationMillis", r.resolveNanos() / 1_000_000.0);
        root.addProperty("conflicts", r.conflictCount());
        JsonObject s = new JsonObject();
        s.addProperty("ignoreIdentical", settings.ignoreIdentical());
        s.add("ignoredPaths", strings(settings.ignoredPaths()));
        root.add("settings", s);

        JsonArray order = new JsonArray();
        for (PackSummary p : r.packs()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id());
            o.addProperty("title", p.title());
            o.addProperty("role", p.role().name().toLowerCase(Locale.ROOT));
            o.addProperty("scan", p.kind().id());
            o.addProperty("approximate", p.approximate());
            o.addProperty("files", p.fileCount());
            if (p.modId() != null) o.addProperty("modId", p.modId());
            if (p.parentId() != null) o.addProperty("parent", p.parentId());
            if (p.role() == PackRole.RESOURCE_PACK) {
                o.addProperty("status", p.status().id());
                o.addProperty("wins", p.wins());
                o.addProperty("losses", p.losses());
                o.addProperty("deadMcmeta", p.dead());
                o.addProperty("harmless", p.harmless());
                o.addProperty("hiddenByFilter", p.hiddenByFilter());
                o.addProperty("hidesOthers", p.hidesOthers());
                o.addProperty("replacedSoundEvents", p.replacedSoundEvents());
                o.addProperty("lostSoundEvents", p.lostSoundEvents());
                o.addProperty("lostToMods", p.lostToMods());
                o.addProperty("modOverrides", p.modOverrides());
                o.add("winsAgainst", counts(p.winsAgainst()));
                o.add("losesTo", counts(p.losesTo()));
                o.add("modOverridesByMod", counts(p.modOverridesByOwner()));
            }
            order.add(o);
        }
        root.add("order", order);

        JsonArray files = new JsonArray();
        for (FileConflict c : r.fileConflicts()) {
            JsonObject o = new JsonObject();
            o.addProperty("location", c.location());
            o.addProperty("kind", c.kind().id());
            o.addProperty("winner", c.winner());
            o.add("losers", strings(c.losers()));
            o.add("harmless", strings(c.harmless()));
            o.add("deadMcmeta", strings(c.dead()));
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
            sounds.add(o);
        }
        root.add("soundReplacements", sounds);

        JsonArray filtered = new JsonArray();
        for (FilteredFile f : r.filtered()) {
            JsonObject o = new JsonObject();
            o.addProperty("location", f.location());
            o.addProperty("filterPack", f.filterPack());
            o.add("hiddenPacks", strings(f.hiddenPacks()));
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
