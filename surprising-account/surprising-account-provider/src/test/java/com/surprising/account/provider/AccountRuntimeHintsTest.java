package com.surprising.account.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.provider.model.CachedPosition;
import com.surprising.account.provider.model.CachedPositionMargin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class AccountRuntimeHintsTest {

    @Test
    void registersNestedLedgerDeltaRecordForNativeJsonDeserialization() {
        RuntimeHints hints = new RuntimeHints();

        new AccountRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (Class<?> type : new Class<?>[]{
                AccountCommandTerminalResult.LedgerDelta.class,
                PerpetualAccountStateUpdatedEvent.Balance.class,
                PerpetualAccountStateUpdatedEvent.Position.class,
                CachedPosition.class,
                CachedPositionMargin.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)
                    .test(hints)).as("public constructors for %s", type.getName()).isTrue();
            assertThat(RuntimeHintsPredicates.reflection().onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                    .test(hints)).as("public methods for %s", type.getName()).isTrue();
        }
    }

    @Test
    void registersNotNullValidatorConstructorForNativeValidation() throws IOException {
        String resource = "META-INF/native-image/com.surprising/surprising-native-validation/"
                + "reachability-metadata.json";

        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(metadata).contains("org.hibernate.validator.internal.constraintvalidators.bv.NotNullValidator");
        }
    }
}
