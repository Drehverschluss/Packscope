package com.drehverschluss.packscope.core;

import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A folder inside a mod jar (Phase 5C): mods are loaded through their own file system (on NeoForge the union file
 * system of the mod file), and built-in packs of mods are folders on it. Packscope then reads the zip directory of the
 * jar instead of walking the folder (CRC and size without unpacking). Plain Java: the jar is asked from the file system
 * ({@code getPrimaryPath()}) or taken from the URI ({@code union:/<jar>#<n>!/<folder>}).
 */
public final class JarPaths {
    private static final Pattern UNION_URI = Pattern.compile("^union:(.*?)#\\d+!(/.*)?$");

    private JarPaths() {}

    /** Jar and folder inside it ("" or ending with "/") for a folder on a mod jar file system, else null. */
    @Nullable
    public static PackContent.Location locate(Path folder) {
        FileSystem fs = folder.getFileSystem();
        if (fs == FileSystems.getDefault()) return null;
        Path jar = primaryPath(fs);
        if (jar == null) jar = fromUri(folder);
        if (jar == null || !Files.isRegularFile(jar)) return null;
        String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".jar") && !name.endsWith(".zip")) return null;
        return new PackContent.Location(jar, inner(folder.toString()), true, true);
    }

    /** "/resourcepacks/x" → "resourcepacks/x/", "/" → "". */
    static String inner(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return "";
        return p.endsWith("/") ? p : p + "/";
    }

    @Nullable
    private static Path primaryPath(FileSystem fs) {
        try {
            Method m = fs.getClass().getMethod("getPrimaryPath");
            Object p = m.invoke(fs);
            return p instanceof Path path && path.getFileSystem() == FileSystems.getDefault() ? path : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Decodes %XX only ('+' stays, jar names often contain it). */
    static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '%' && i + 2 < b.length) {
                int hi = Character.digit(b[i + 1], 16);
                int lo = Character.digit(b[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 2;
                    continue;
                }
            }
            out.write(b[i]);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Nullable
    static Path fromUri(Path folder) {
        try {
            String uri = percentDecode(folder.toUri().toString());
            Matcher m = UNION_URI.matcher(uri);
            if (!m.matches()) return null;
            String jar = m.group(1);
            if (jar.matches("^/[A-Za-z]:/.*")) jar = jar.substring(1);
            return Path.of(jar);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
