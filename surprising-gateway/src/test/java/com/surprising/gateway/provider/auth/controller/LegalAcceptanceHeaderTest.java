package com.surprising.gateway.provider.auth.controller;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.model.LegalModels.Acceptance;
import org.junit.jupiter.api.Test;

class LegalAcceptanceHeaderTest {
    @Test void preservesExactInt64RevisionsAndNormalizesTheReviewedLanguage() {
        assertThat(LegalAcceptanceHeader.parse("9007199254740993:en,9223372036854775807:zh_hans"))
                .containsExactly(new Acceptance(9007199254740993L,"en-US"),new Acceptance(Long.MAX_VALUE,"zh-CN"));
        assertThat(LegalAcceptanceHeader.parse(null)).isEmpty();
    }
    @Test void refusesAmbiguousMalformedOrOversizedAcknowledgements() {
        for(String value:new String[]{"0:en-US","01:en-US","1:","1:xx","1:en-US,1:zh-CN","1:en-US,","9223372036854775808:en-US","1:en-US".repeat(100),"1:en-US:extra"})
            assertThatThrownBy(()->LegalAcceptanceHeader.parse(value)).as(value).isInstanceOf(LegalProblem.class);
    }
}
