package com.drehverschluss.packscope.core;

import static com.drehverschluss.packscope.core.TestPacks.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Only the languages the game loads count (en_us + selected language), allLanguages counts all (Konzept 3). */
class LanguagesTest {
    private static Set<String> lang3Languages(ScanResult r) {
        return r.langConflicts().stream().filter(c -> c.key().equals("packscope.test.lang3")).map(ScanResult.LangConflict::language)
            .collect(Collectors.toSet());
    }

    @Test
    void englishGameCountsOnlyEnglish() {
        PackOrder order = TestPacks.readmeOrder().withLanguages(List.of("en_us"));
        ScanResult r = PackEvaluator.evaluate(order, new IndexCache(null), PackscopeSettings.DEFAULTS);
        assertEquals(Set.of("en_us"), lang3Languages(r));
        assertEquals(1, r.summary(id("PS_Lang3_Low.zip")).losses());
    }

    @Test
    void germanGameCountsEnglishAndGerman() {
        PackOrder order = TestPacks.readmeOrder().withLanguages(List.of("en_us", "de_de"));
        ScanResult r = PackEvaluator.evaluate(order, new IndexCache(null), PackscopeSettings.DEFAULTS);
        assertEquals(Set.of("en_us", "de_de"), lang3Languages(r));
        assertEquals(2, r.summary(id("PS_Lang3_Low.zip")).losses());
    }

    @Test
    void allLanguages() {
        ScanResult r = PackEvaluator.evaluate(TestPacks.readmeOrder(), new IndexCache(null), PackscopeSettings.DEFAULTS.withAllLanguages(true));
        assertEquals(Set.of("en_us", "de_de", "fr_fr"), lang3Languages(r));
        assertEquals(3, r.summary(id("PS_Lang3_Low.zip")).losses());
    }

    @Test
    void languagesArePartOfTheKey() {
        PackOrder base = TestPacks.readmeOrder();
        assertNotEquals(base.withLanguages(List.of("en_us")).key(), base.withLanguages(List.of("en_us", "de_de")).key());
    }

    @Test
    void notLoadedLanguageFilesDoNotCount() {
        // a pack with only a fr_fr file in an English game: nothing to evaluate, no conflict
        PackRef high = TestPacks.pack("PS_Lang3_High");
        PackRef low = TestPacks.pack("PS_Lang3_Low.zip");
        PackOrder order = new PackOrder(List.of(high, low), List.of("en_us"));
        ScanResult r = PackEvaluator.evaluate(order, new IndexCache(null), PackscopeSettings.DEFAULTS);
        assertEquals(1, r.langConflicts().size());
        assertEquals(PackStatus.LOSES, r.summary(low.id()).status());
    }
}
