package com.surprising.gateway.provider.content;

import com.surprising.gateway.provider.content.model.ContentLocale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ContentLocaleTest {
    @Test
    void allTwelveLanguagesHaveDistinctCodesAndNativeLabels() {
        assertThat(ContentLocale.supported()).hasSize(12);
        assertThat(ContentLocale.supported()).extracting(ContentLocale::code).doesNotHaveDuplicates();
        assertThat(ContentLocale.supported()).allSatisfy(locale -> {
            assertThat(locale.label()).isNotBlank();
            assertThat(ContentLocale.require(locale.code())).isEqualTo(locale);
        });
        assertThat(ContentLocale.supported().stream().filter(ContentLocale::rightToLeft).toList())
                .containsExactly(ContentLocale.ARABIC);
    }

    @Test
    void resolvesLanguageAndScriptWithoutTreatingLanguageAsJurisdiction() {
        assertThat(ContentLocale.require(null)).isEqualTo(ContentLocale.ENGLISH);
        assertThat(ContentLocale.require("en_GB")).isEqualTo(ContentLocale.ENGLISH);
        assertThat(ContentLocale.require("zh_hans")).isEqualTo(ContentLocale.SIMPLIFIED_CHINESE);
        assertThat(ContentLocale.require("zh-Hant-HK")).isEqualTo(ContentLocale.TRADITIONAL_CHINESE);
        assertThat(ContentLocale.require("zh-Hans-HK")).isEqualTo(ContentLocale.SIMPLIFIED_CHINESE);
        assertThat(ContentLocale.require("zh-TW")).isEqualTo(ContentLocale.TRADITIONAL_CHINESE);
        assertThat(ContentLocale.require("fr-CA")).isEqualTo(ContentLocale.FRENCH);
        assertThat(ContentLocale.require(" AR ")).isEqualTo(ContentLocale.ARABIC);
    }

    @Test
    void rejectsUnsupportedOrMalformedTagsInsteadOfStoringInaccessibleTranslations() {
        for (String tag : new String[]{"", "qaa", "en-US,en;q=0.9", "invalid%%", "../en", "en_", "<script>"})
            assertThatThrownBy(() -> ContentLocale.require(tag)).isInstanceOf(IllegalArgumentException.class);
    }
}
