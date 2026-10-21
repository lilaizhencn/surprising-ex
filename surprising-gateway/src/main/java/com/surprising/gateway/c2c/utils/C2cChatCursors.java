package com.surprising.gateway.c2c.utils;

/** HTTP/WS边界的十进制int64游标转换；不判断游标是否属于某会话或订单。 */
public final class C2cChatCursors {
    private C2cChatCursors() { }
    public static long parse(String value) {
        if(value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("消息游标无效");
        try {return Long.parseLong(value);}
        catch(NumberFormatException failure) {throw new IllegalArgumentException("消息游标超出范围");}
    }
}
