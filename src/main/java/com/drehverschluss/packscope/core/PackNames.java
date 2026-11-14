package com.drehverschluss.packscope.core;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jetbrains.annotations.Nullable;

/**
 * Matching of pack ids for the intended markings (Konzept 4.1): same id, same base name, or a {@code regex:} entry.
 *
 * <p>Base name (only for {@code file/...} ids): last path part (Packed Packs folders do not matter), without {@code .zip},
 * lower case, version numbers removed ({@code v1.14}, {@code v.1.14}, {@code 2.3.0}, {@code 1.21.1}, {@code 26.x}),
 * every run of characters other than letters and digits turned into one space. Numbers without a dot ({@code 16x},
 * {@code [120]}, {@code v3}) stay. Other ids ({@code mod/...}, {@code vanilla}, ...) have no base name and match exactly.
 */
public final class PackNames {
    /** A number with at least one ".part" (digits or x), optionally led by "v" / "v.". Not inside a longer word. */
    private static final Pattern VERSION = Pattern.compile("(?<![a-z0-9])v?\\.?\\d+(?:\\.(?:\\d+|x))+(?![0-9])");
    private static final Pattern SEPARATORS = Pattern.compile("[^a-z0-9]+");
    public static final String REGEX_PREFIX = "regex:";

    private PackNames() {}

    /** Base name of a {@code file/...} pack id, or null for other ids. */
    @Nullable
    public static String baseName(String id) {
        if (!id.startsWith("file/")) return null;
        String name = id.substring(id.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) name = name.substring(0, name.length() - 4);
        name = VERSION.matcher(name).replaceAll(" ");
        name = SEPARATORS.matcher(name).replaceAll(" ").trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * Whether the id written in a marking ({@code entry}) means the active pack {@code packId}.
     * Exact id, same base name, or {@code regex:<pattern>} matching the whole id.
     */
    public static boolean matches(String entry, String packId) {
        if (entry.equals(packId)) return true;
        if (entry.startsWith(REGEX_PREFIX)) {
            try {
                return Pattern.compile(entry.substring(REGEX_PREFIX.length())).matcher(packId).matches();
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
        String a = baseName(entry);
        return a != null && a.equals(baseName(packId));
    }
}
