package io.descoped.lds.api.search;

import java.util.Locale;
import java.util.Set;

/**
 * The language a text of a document is searched in: the words of a text in a language are matched by their stem in
 * that language ("husene" is found by "hus"), and a text in no language that LDS knows is matched word for word.
 */
public enum Language {

    /**
     * No language that LDS knows: matched word for word.
     */
    NONE(Set.of()),
    /**
     * Norwegian, bokmål and nynorsk: {@code no}, {@code nb}, {@code nn}.
     */
    NORWEGIAN(Set.of("no", "nb", "nn")),
    /**
     * Swedish: {@code sv}.
     */
    SWEDISH(Set.of("sv")),
    /**
     * English: {@code en}.
     */
    ENGLISH(Set.of("en")),
    /**
     * German: {@code de}.
     */
    GERMAN(Set.of("de")),
    /**
     * French: {@code fr}.
     */
    FRENCH(Set.of("fr"));

    private final Set<String> codes;

    Language(Set<String> codes) {
        this.codes = codes;
    }

    /**
     * The language of a language code as a document writes it: the code of the language, in any case, alone or with
     * a region ({@code nb}, {@code NB}, {@code nb-NO}, {@code en_GB}).
     *
     * @param code the code, or null
     * @return the language, or {@link #NONE} when the code is null or of another language
     */
    public static Language of(String code) {
        if (code == null) {
            return NONE;
        }
        String language = code.strip().toLowerCase(Locale.ROOT);
        int region = indexOfRegion(language);
        if (region >= 0) {
            language = language.substring(0, region);
        }
        for (Language candidate : values()) {
            if (candidate.codes.contains(language)) {
                return candidate;
            }
        }
        return NONE;
    }

    private static int indexOfRegion(String code) {
        int dash = code.indexOf('-');
        int underscore = code.indexOf('_');
        if (dash < 0) {
            return underscore;
        }
        return underscore < 0 ? dash : Math.min(dash, underscore);
    }
}
