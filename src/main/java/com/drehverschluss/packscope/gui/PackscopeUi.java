package com.drehverschluss.packscope.gui;

import com.drehverschluss.packscope.core.ScanResult;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * Entry of the UI: the platform (set once by the loader part), the small result cache shared by all pack screens of
 * the session, and the game language for numbers. Render thread only.
 */
public final class PackscopeUi {
    /** Results kept per order key (order + markings version + settings). */
    static final int CACHE_SIZE = 24;

    @Nullable
    private static UiPlatform platform;
    private static int session;

    /** Result for one order key, from the pack screen session {@code session}. */
    record Cached(ScanResult result, int session) {}

    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private PackscopeUi() {}

    public static void install(UiPlatform p) {
        platform = p;
    }

    @Nullable
    public static UiPlatform platform() {
        return platform;
    }

    /** Drops all cached results ({@code /packscope rescan}). */
    public static void clearCache() {
        CACHE.clear();
    }

    /** A new pack screen was opened: results of earlier screens are only shown until they are checked again. */
    static int newSession() {
        return ++session;
    }

    @Nullable
    static Cached cached(String key) {
        return CACHE.get(key);
    }

    static void put(String key, ScanResult result, int session) {
        CACHE.put(key, new Cached(result, session));
    }

    /** Selected game language ({@code en_us}, {@code de_de} ...) for the number format. */
    public static String language() {
        String l = Minecraft.getInstance().getLanguageManager().getSelected();
        return l == null ? "en_us" : l;
    }
}
