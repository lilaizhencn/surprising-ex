package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CoreMessageType {
    PROBE_INCREMENT(1, WireMessageKind.COMMAND),
    VERIFY_STATE_HASH(2, WireMessageKind.COMMAND),
    ADJUST_BALANCE(10, WireMessageKind.COMMAND),
    PLACE_ORDER(11, WireMessageKind.COMMAND),
    CANCEL_ORDER(12, WireMessageKind.COMMAND),
    REPLACE_ORDER(13, WireMessageKind.COMMAND),
    AMEND_ORDER(14, WireMessageKind.COMMAND),
    UPSERT_INSTRUMENT(20, WireMessageKind.COMMAND),
    APPLY_MARK_PRICE(21, WireMessageKind.COMMAND),
    APPLY_FUNDING(22, WireMessageKind.COMMAND),
    SETTLE_INSTRUMENT(23, WireMessageKind.COMMAND),
    EXECUTE_LIQUIDATION(24, WireMessageKind.COMMAND),
    RESOLVE_LIQUIDATION(25, WireMessageKind.COMMAND),
    CONTINUE_RISK_SCAN(26, WireMessageKind.COMMAND),
    ACK_EXPORT(27, WireMessageKind.COMMAND),
    UPDATE_POSITION_MODE(28, WireMessageKind.COMMAND),
    ADJUST_POSITION_MARGIN(29, WireMessageKind.COMMAND),
    ADJUST_INSURANCE_FUND(30, WireMessageKind.COMMAND),
    EXECUTE_ADL(31, WireMessageKind.COMMAND),
    UPDATE_LEVERAGE(32, WireMessageKind.COMMAND),
    UPSERT_ALGO_ORDER(33, WireMessageKind.COMMAND),
    UPDATE_CANCEL_ALL_AFTER(34, WireMessageKind.COMMAND),
    PLACE_TRIGGER_ORDER(35, WireMessageKind.COMMAND),
    CANCEL_TRIGGER_ORDER(36, WireMessageKind.COMMAND),
    CLAIM_TRIGGER_ORDER(37, WireMessageKind.COMMAND),
    COMPLETE_TRIGGER_ORDER(38, WireMessageKind.COMMAND),
    UPDATE_TRIGGER_TRAILING(39, WireMessageKind.COMMAND),
    EXPIRE_TRIGGER_ORDER(40, WireMessageKind.COMMAND),
    RETRY_TRIGGER_ORDER(41, WireMessageKind.COMMAND),
    EXECUTE_TRIGGER_ORDER(42, WireMessageKind.COMMAND),
    STATE_HASH_QUERY(100, WireMessageKind.QUERY),
    BUSINESS_STATE_HASH_QUERY(101, WireMessageKind.QUERY),
    USER_STATE_HASH_QUERY(102, WireMessageKind.QUERY),
    ORDER_STATE_HASH_QUERY(103, WireMessageKind.QUERY),
    USER_STATE_QUERY(104, WireMessageKind.QUERY),
    ORDER_STATE_QUERY(105, WireMessageKind.QUERY),
    EXPORT_BATCH_QUERY(106, WireMessageKind.QUERY),
    EXPORT_STATUS_QUERY(107, WireMessageKind.QUERY),
    CLIENT_ORDER_STATE_QUERY(108, WireMessageKind.QUERY),
    TREASURY_STATE_QUERY(109, WireMessageKind.QUERY),
    ADL_CANDIDATE_QUERY(110, WireMessageKind.QUERY),
    RISK_STATE_QUERY(111, WireMessageKind.QUERY),
    OPEN_INTEREST_QUERY(112, WireMessageKind.QUERY),
    ALGO_ORDER_QUERY(113, WireMessageKind.QUERY),
    CANCEL_ALL_AFTER_QUERY(114, WireMessageKind.QUERY),
    ORDER_PREFLIGHT_QUERY(115, WireMessageKind.QUERY),
    BOOK_STATE_QUERY(116, WireMessageKind.QUERY),
    LIQUIDATION_WORK_QUERY(117, WireMessageKind.QUERY),
    USER_OPEN_ORDERS_QUERY(118, WireMessageKind.QUERY),
    TRIGGER_ORDER_QUERY(119, WireMessageKind.QUERY),
    USER_OPEN_TRIGGER_ORDERS_QUERY(120, WireMessageKind.QUERY),
    FUNDING_PROGRESS_QUERY(121, WireMessageKind.QUERY),
    SETTLEMENT_PROGRESS_QUERY(122, WireMessageKind.QUERY),
    COMMAND_RESULT(200, WireMessageKind.RESPONSE),
    STATE_HASH_RESULT(201, WireMessageKind.RESPONSE),
    USER_STATE_RESULT(202, WireMessageKind.RESPONSE),
    ORDER_STATE_RESULT(203, WireMessageKind.RESPONSE),
    TREASURY_STATE_RESULT(204, WireMessageKind.RESPONSE),
    ADL_CANDIDATE_RESULT(205, WireMessageKind.RESPONSE),
    RISK_STATE_RESULT(206, WireMessageKind.RESPONSE),
    OPEN_INTEREST_RESULT(207, WireMessageKind.RESPONSE),
    ALGO_ORDER_RESULT(208, WireMessageKind.RESPONSE),
    CANCEL_ALL_AFTER_RESULT(209, WireMessageKind.RESPONSE),
    ORDER_PREFLIGHT_RESULT(210, WireMessageKind.RESPONSE),
    BOOK_STATE_RESULT(211, WireMessageKind.RESPONSE),
    LIQUIDATION_WORK_RESULT(212, WireMessageKind.RESPONSE),
    USER_OPEN_ORDERS_RESULT(213, WireMessageKind.RESPONSE),
    TRIGGER_ORDER_RESULT(214, WireMessageKind.RESPONSE),
    USER_OPEN_TRIGGER_ORDERS_RESULT(215, WireMessageKind.RESPONSE),
    FUNDING_PROGRESS_RESULT(216, WireMessageKind.RESPONSE),
    SETTLEMENT_PROGRESS_RESULT(217, WireMessageKind.RESPONSE),
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
