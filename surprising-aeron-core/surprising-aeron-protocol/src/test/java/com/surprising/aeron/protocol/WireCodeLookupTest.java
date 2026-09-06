package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class WireCodeLookupTest {
    @Test
    void preservesEveryPublishedCodeAndRejectsHolesAndOutOfRangeCodes() {
        for (var value : CoreMessageType.values())
            assertThat(CoreMessageType.fromWireCode(value.wireCode())).isSameAs(value);
        for (var value : CommandSource.values())
            assertThat(CommandSource.fromWireCode(value.wireCode())).isSameAs(value);
        for (var value : ResponseStatus.values())
            assertThat(ResponseStatus.fromWireCode(value.wireCode())).isSameAs(value);
        for (var value : WireMessageKind.values())
            assertThat(WireMessageKind.fromWireCode(value.wireCode())).isSameAs(value);
        for (var value : ProductLine.values())
            assertThat(ProductLineWireCode.decode(ProductLineWireCode.encode(value))).isSameAs(value);
        for (int code : new int[]{Integer.MIN_VALUE, -1, 0, 301, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> CoreMessageType.fromWireCode(code)).isInstanceOf(ProtocolException.class);
            assertThatThrownBy(() -> CommandSource.fromWireCode(code)).isInstanceOf(ProtocolException.class);
            assertThatThrownBy(() -> ResponseStatus.fromWireCode(code)).isInstanceOf(ProtocolException.class);
            assertThatThrownBy(() -> WireMessageKind.fromWireCode(code)).isInstanceOf(ProtocolException.class);
            assertThatThrownBy(() -> ProductLineWireCode.decode(code)).isInstanceOf(ProtocolException.class);
        }
        for (int code = 1; code <= 300; code++) {
            int candidate = code;
            boolean assigned = java.util.Arrays.stream(CoreMessageType.values())
                    .anyMatch(value -> value.wireCode() == candidate);
            if (!assigned) assertThatThrownBy(() -> CoreMessageType.fromWireCode(candidate))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessage("unsupported message type: " + candidate);
        }
    }
}
