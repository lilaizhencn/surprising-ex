package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CoreMessageType {
    PROBE_INCREMENT(1, WireMessageKind.COMMAND),
    VERIFY_STATE_HASH(2, WireMessageKind.COMMAND),
    ADJUST_BALANCE(10, WireMessageKind.COMMAND),
    PLACE_ORDER(11, WireMessageKind.COMMAND),
    CANCEL_ORDER(12, WireMessageKind.COMMAND),
    REPLACE_ORDER(13, WireMessageKind.COMMAND),
    UPSERT_INSTRUMENT(20, WireMessageKind.COMMAND),
    APPLY_MARK_PRICE(21, WireMessageKind.COMMAND),
    APPLY_FUNDING(22, WireMessageKind.COMMAND),
    SETTLE_INSTRUMENT(23, WireMessageKind.COMMAND),
    EXECUTE_LIQUIDATION(24, WireMessageKind.COMMAND),
    RESOLVE_LIQUIDATION(25, WireMessageKind.COMMAND),
    CONTINUE_RISK_SCAN(26, WireMessageKind.COMMAND),
    STATE_HASH_QUERY(100, WireMessageKind.QUERY),
    BUSINESS_STATE_HASH_QUERY(101, WireMessageKind.QUERY),
    USER_STATE_HASH_QUERY(102, WireMessageKind.QUERY),
    ORDER_STATE_HASH_QUERY(103, WireMessageKind.QUERY),
    USER_STATE_QUERY(104, WireMessageKind.QUERY),
    ORDER_STATE_QUERY(105, WireMessageKind.QUERY),
    COMMAND_RESULT(200, WireMessageKind.RESPONSE),
    STATE_HASH_RESULT(201, WireMessageKind.RESPONSE),
    USER_STATE_RESULT(202, WireMessageKind.RESPONSE),
    ORDER_STATE_RESULT(203, WireMessageKind.RESPONSE),
    CORE_EVENT(300, WireMessageKind.EXPORT_EVENT);

    private final int wireCode;
    private final WireMessageKind kind;

    CoreMessageType(int wireCode, WireMessageKind kind) {
        this.wireCode = wireCode;
        this.kind = kind;
    }

    public int wireCode() {
        return wireCode;
    }

    public WireMessageKind kind() {
        return kind;
    }

    public static CoreMessageType fromWireCode(int wireCode) {
        return Arrays.stream(values())
                .filter(value -> value.wireCode == wireCode)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported message type: " + wireCode));
    }
}
