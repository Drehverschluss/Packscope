package com.drehverschluss.packscope.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsers for the files the game merges by content: language files and sounds.json. */
public final class JsonMergers {
    private JsonMergers() {}

    /**
     * Reads a language file like vanilla {@code Language.loadFromJson}: every entry of the top-level object is a key;
     * strings are taken as they are, NeoForge component arrays (and anything else) as their JSON text.
     *
     * @throws RuntimeException if the JSON is broken
     */
    public static Map<String, String> lang(byte[] data) {
        JsonObject root = JsonParser.parseString(text(data)).getAsJsonObject();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonElement v = e.getValue();
            out.put(e.getKey(), v.isJsonPrimitive() ? v.getAsString() : v.toString());
        }
        return out;
    }

    /** One event of a sounds.json. */
    public record SoundEvent(boolean replace, List<String> sounds) {}

    /**
     * Reads a sounds.json like vanilla {@code SoundManager}: event name -> {@code replace} flag and sound names
     * (strings or objects with {@code name}).
     *
     * @throws RuntimeException if the JSON is broken
     */
    public static Map<String, SoundEvent> sounds(byte[] data) {
        JsonObject root = JsonParser.parseString(text(data)).getAsJsonObject();
        Map<String, SoundEvent> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonObject ev = e.getValue().getAsJsonObject();
            boolean replace = ev.has("replace") && ev.get("replace").getAsBoolean();
            List<String> sounds = new ArrayList<>();
            if (ev.has("sounds")) {
                for (JsonElement s : ev.getAsJsonArray("sounds")) {
                    if (s.isJsonPrimitive()) sounds.add(s.getAsString());
                    else if (s.isJsonObject() && s.getAsJsonObject().has("name")) sounds.add(s.getAsJsonObject().get("name").getAsString());
                }
            }
            out.put(e.getKey(), new SoundEvent(replace, sounds));
        }
        return out;
    }

    private static String text(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }
}
