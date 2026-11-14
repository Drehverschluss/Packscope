package com.drehverschluss.packscope.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code filter} section of a pack.mcmeta, evaluated like vanilla ({@code MultiPackResourceManager} +
 * {@code ResourceFilterSection}): a location is hidden if any entry matches its namespace and any entry matches its
 * path (regex "find", a missing pattern matches everything). Only affects packs below the filtering pack.
 */
public final class PackFilter {
    private final List<Pattern> namespaces = new ArrayList<>();
    private final List<Pattern> paths = new ArrayList<>();
    private boolean anyNamespace;
    private boolean anyPath;
    private final List<String> description = new ArrayList<>();

    private PackFilter() {}

    /**
     * Reads the filter from pack.mcmeta bytes. Returns null without filter.
     *
     * @throws RuntimeException if the file or the filter section is broken
     */
    @Nullable
    public static PackFilter parse(@Nullable byte[] packMeta) {
        if (packMeta == null) return null;
        JsonElement root = JsonParser.parseString(new String(packMeta, StandardCharsets.UTF_8));
        if (!root.isJsonObject() || !root.getAsJsonObject().has("filter")) return null;
        JsonObject filter = root.getAsJsonObject().getAsJsonObject("filter");
        JsonArray block = filter.getAsJsonArray("block");
        if (block == null) throw new IllegalArgumentException("filter without block list");
        PackFilter f = new PackFilter();
        for (JsonElement e : block) {
            JsonObject o = e.getAsJsonObject();
            String ns = o.has("namespace") ? o.get("namespace").getAsString() : null;
            String path = o.has("path") ? o.get("path").getAsString() : null;
            if (ns == null) f.anyNamespace = true; else f.namespaces.add(Pattern.compile(ns));
            if (path == null) f.anyPath = true; else f.paths.add(Pattern.compile(path));
            f.description.add((ns == null ? "*" : ns) + ":" + (path == null ? "*" : path));
        }
        return f.description.isEmpty() ? null : f;
    }

    public boolean hides(String location) {
        return matchesAny(anyNamespace, namespaces, Locations.namespace(location)) && matchesAny(anyPath, paths, Locations.path(location));
    }

    private static boolean matchesAny(boolean any, List<Pattern> patterns, String value) {
        if (any) return true;
        for (Pattern p : patterns) if (p.matcher(value).find()) return true;
        return false;
    }

    /** Human-readable patterns, {@code namespace-regex:path-regex}. */
    public List<String> description() {
        return List.copyOf(description);
    }
}
