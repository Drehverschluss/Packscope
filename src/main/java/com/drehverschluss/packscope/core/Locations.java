package com.drehverschluss.packscope.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Helpers for locations written as {@code namespace:path}. */
public final class Locations {
    private Locations() {}

    /**
     * Turns a path below {@code assets/} ({@code namespace/rest/of/path}) into {@code namespace:rest/of/path}, or null if
     * the game would never load it (invalid namespace or path, file directly in {@code assets/}).
     */
    @Nullable
    public static String fromAssetPath(String relative) {
        int slash = relative.indexOf('/');
        if (slash <= 0 || slash == relative.length() - 1) return null;
        String ns = relative.substring(0, slash);
        String path = relative.substring(slash + 1);
        if (!ResourceLocation.isValidNamespace(ns) || !ResourceLocation.isValidPath(path)) return null;
        return ns + ":" + path;
    }

    public static String namespace(String location) {
        return location.substring(0, location.indexOf(':'));
    }

    public static String path(String location) {
        return location.substring(location.indexOf(':') + 1);
    }

    /** Path of a location inside a pack: {@code assets/<namespace>/<path>}. */
    public static String assetPath(String location) {
        return "assets/" + namespace(location) + "/" + path(location);
    }

    /** Patterns from the {@code ignoredPaths} option. */
    public static final class Ignored {
        private final List<Pattern> full = new ArrayList<>();
        private final List<Pattern> pathOnly = new ArrayList<>();

        /**
         * {@code *} matches any characters (also {@code /}), {@code ?} one character. With a {@code :} the pattern is
         * matched against {@code namespace:path}, without it against the path only.
         */
        public Ignored(List<String> globs) {
            for (String g : globs) {
                if (g == null || g.isBlank()) continue;
                String glob = g.trim();
                Pattern p = Pattern.compile(globToRegex(glob));
                (glob.contains(":") ? full : pathOnly).add(p);
            }
        }

        public boolean isEmpty() {
            return full.isEmpty() && pathOnly.isEmpty();
        }

        public boolean matches(String location) {
            for (Pattern p : full) if (p.matcher(location).matches()) return true;
            if (!pathOnly.isEmpty()) {
                String path = path(location);
                for (Pattern p : pathOnly) if (p.matcher(path).matches()) return true;
            }
            return false;
        }

        static String globToRegex(String glob) {
            StringBuilder sb = new StringBuilder();
            for (char c : glob.toCharArray()) {
                switch (c) {
                    case '*' -> sb.append(".*");
                    case '?' -> sb.append('.');
                    default -> sb.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return sb.toString();
        }
    }
}
