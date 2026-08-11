package com.surprising.edge.provider;

import com.surprising.websocket.api.model.WsClientCommand;
import com.surprising.websocket.api.model.WsServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeRuntimeHintsTest {

    @Test
    void registersWebSocketRecordAccessorsForNativeJsonSerialization() {
        RuntimeHints hints = new RuntimeHints();

        new EdgeRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(WsClientCommand.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)
                .test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(WsClientCommand.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                .test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(WsServerMessage.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)
                .test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(WsServerMessage.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                .test(hints)).isTrue();
    }
}
