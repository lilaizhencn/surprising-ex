package com.surprising.aeron.protocol;

/** 由消息类型确定的语义分类，不另占协议字段。 */
public enum WireMessageKind {
    COMMAND,
    RESPONSE,
    QUERY
}
