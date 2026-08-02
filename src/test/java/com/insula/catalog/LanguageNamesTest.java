package com.insula.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Codes to words. Every case here is a real value from the live Kiwix feed. */
class LanguageNamesTest {

    @Test
    void theCommonCodesReadAsLanguages() {
        assertEquals("English", LanguageNames.one("eng"));
        assertEquals("Spanish", LanguageNames.one("spa"));
        assertEquals("German", LanguageNames.one("deu"));
    }

    @Test
    void codesTheTagLookupCannotResolveComeFromTheIsoTable() {
        // Locale.forLanguageTag hands these straight back unchanged; only the 639-3 → 639-1
        // table knows them.
        assertEquals("French", LanguageNames.one("fra"));
        assertEquals("Tibetan", LanguageNames.one("bod"));
        assertEquals("Chinese", LanguageNames.one("zho"));
    }

    @Test
    void codesWithNoTwoLetterFormComeFromTheTagLookup() {
        // These cannot be in the ISO table by construction — it is keyed off two-letter codes.
        assertEquals("Acehnese", LanguageNames.one("ace"));
        assertEquals("Low German", LanguageNames.one("nds"));
        assertEquals("Cebuano", LanguageNames.one("ceb"));
        assertEquals("Multiple languages", LanguageNames.one("mul"));
    }

    @Test
    void anUnknownCodeKeepsItsCodeRatherThanGuessing() {
        // A wrong name is worse than an honest abbreviation.
        assertEquals("ami", LanguageNames.one("ami"));
        assertEquals("tsz", LanguageNames.one("tsz"));
    }

    @Test
    void aShortListIsSpelledOut() {
        assertEquals("English, French", LanguageNames.display("eng,fra"));
        assertEquals("English, French, German", LanguageNames.display("eng, fra, deu"));
    }

    @Test
    void aLongListBecomesACount() {
        // One real entry carries over a hundred codes; spelling them out would bury the card.
        String many = "ara,bos,cat,ces,dan,deu,ell,eng,spa,fra,hin";
        assertEquals("11 languages", LanguageNames.display(many));
    }

    @Test
    void duplicatesInAListAreNotCountedTwice() {
        assertEquals("English, French", LanguageNames.display("eng,fra,eng"));
    }

    @Test
    void nothingInMeansNothingOut() {
        assertEquals("", LanguageNames.display(null));
        assertEquals("", LanguageNames.display("  "));
        assertEquals("", LanguageNames.one(null));
    }
}
