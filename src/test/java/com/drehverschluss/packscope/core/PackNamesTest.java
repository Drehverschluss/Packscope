package com.drehverschluss.packscope.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Base name rule for intended markings (Konzept 4.1), tested with the real ids of the dev instance. */
class PackNamesTest {
    /** Section name -> ids, from src/test/resources/real-pack-ids.txt. */
    private static Map<String, List<String>> realIds() throws IOException {
        Map<String, List<String>> out = new HashMap<>();
        List<String> current = null;
        try (InputStream in = PackNamesTest.class.getResourceAsStream("/real-pack-ids.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    current = new ArrayList<>();
                    out.put(line, current);
                } else {
                    current.add(line);
                }
            }
        }
        return out;
    }

    @Test
    void realIdsHaveDifferentBaseNames() throws IOException {
        List<String> ids = realIds().get("[defaultoptions]");
        assertEquals(56, ids.size());
        Map<String, String> seen = new HashMap<>();
        for (String id : ids) {
            String base = PackNames.baseName(id);
            if (base == null) continue;
            String before = seen.put(base, id);
            assertNull(before, "same base name '" + base + "' for " + before + " and " + id);
        }
        // and no id matches another one of the list
        for (String a : ids) {
            for (String b : ids) {
                if (!a.equals(b)) assertFalse(PackNames.matches(a, b), a + " matches " + b);
            }
        }
    }

    @Test
    void attributeIconsVersionsMatch() throws IOException {
        String defaults = realIds().get("[defaultoptions]").stream().filter(i -> i.contains("Attribute_Icons")).findFirst().orElseThrow();
        String options = realIds().get("[options]").stream().filter(i -> i.contains("Attribute_Icons")).findFirst().orElseThrow();
        String file = "file/Attribute_Icons-Description_Collection-2.3.0-1.21.1.zip";
        assertEquals("file/Attribute_Icons-Description_Collection-2.2.0-1.21.1.zip", defaults);
        assertEquals("file/Attribute_Icons+Description_Collection-2.3.0+1.21.1.zip", options);
        assertEquals("attribute icons description collection", PackNames.baseName(file));
        assertTrue(PackNames.matches(defaults, file));
        assertTrue(PackNames.matches(options, file));
        assertTrue(PackNames.matches(defaults, options));
    }

    @Test
    void newVersionsAndFoldersMatch() {
        assertTrue(PackNames.matches("file/Icons v.1.14.zip", "file/Icons v.1.15.zip"));
        assertTrue(PackNames.matches("file/Icons v.1.14.zip", "file/Icons/Icons v.1.15.zip"));
        assertTrue(PackNames.matches("file/Icons v.1.14.zip", "file/Icons v1.15"), "folder pack without .zip, v without dot");
        assertTrue(PackNames.matches("file/FreshAnimations_v1.11.2_1.20.zip", "file/FreshAnimations_v1.12_1.21.zip"));
        assertTrue(PackNames.matches("file/EatAnimation Quark Compat 1.20-26.x.zip", "file/EatAnimation Quark Compat 1.21-27.x.zip"));
        assertTrue(PackNames.matches("file/Biome Edition Traveler's Titles by ImEvolutiON V1.3.zip",
            "file/Biome Edition Traveler's Titles by ImEvolutiON V1.4.zip"), "upper case V");
    }

    @Test
    void packAndExtensionDoNotMatch() {
        assertFalse(PackNames.matches("file/FreshAnimations_v1.11.2_1.20.zip", "file/FA-All_Extensions-v1.10.1.zip"));
        assertFalse(PackNames.matches("file/FA-All_Extensions-v1.10.1.zip", "file/FA+Player-v1.1.zip"));
        assertFalse(PackNames.matches("file/Icons v.1.14.zip", "file/Icons - Armor  Food Remover v.1.1.zip"));
        assertFalse(PackNames.matches("file/Ashen_16x.zip", "file/Ashen_Falling_Leaves_Support_1.1.zip"));
        assertFalse(PackNames.matches("file/Biome Edition Traveler's Titles by ImEvolutiON V1.3.zip",
            "file/More Biome Edition Traveler's Titles by Drehverschluss v1.1.zip"));
        assertFalse(PackNames.matches("file/AL's Spiders Revamped+FA 1.3.zip", "file/AL's Scorpions  Crabs+FA.zip"));
    }

    @Test
    void numbersWithoutDotStay() {
        assertEquals("ashen 16x", PackNames.baseName("file/Ashen_16x.zip"));
        assertEquals("modded omelet 120 171", PackNames.baseName("file/Modded Omelet [120] [171].zip"));
        assertEquals("quarkfacompat v3 final", PackNames.baseName("file/QuarkFACompat_v3_final.zip"));
        assertEquals("better lanterns mc", PackNames.baseName("file/Better+Lanterns+v1.2(mc-1.21).zip"));
        assertFalse(PackNames.matches("file/Ashen_16x.zip", "file/Ashen_32x.zip"), "resolution stays");
    }

    @Test
    void otherIdsOnlyExact() {
        assertNull(PackNames.baseName("mod/createfood:resourcepacks/minecraft_overrides"));
        assertNull(PackNames.baseName("vanilla"));
        assertTrue(PackNames.matches("moonlight:merged_pack", "moonlight:merged_pack"));
        assertFalse(PackNames.matches("mod/accessorify:resourcepacks/1_21_1/rp", "mod/accessorify:resourcepacks/1_21_2/rp"));
    }

    @Test
    void regexEntries() {
        assertTrue(PackNames.matches("regex:file/Icons v\\.1\\.1\\d\\.zip", "file/Icons v.1.15.zip"));
        assertFalse(PackNames.matches("regex:file/Icons v\\.1\\.1\\d\\.zip", "file/Icons v.2.0.zip"));
        assertTrue(PackNames.matches("regex:mod/createfood:.*", "mod/createfood:resourcepacks/create_overrides"));
        assertFalse(PackNames.matches("regex:[broken", "file/x.zip"), "broken pattern matches nothing");
    }
}
