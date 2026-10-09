package com.surprising.aeron.protocol;

/** Bounded ASCII correlation metadata. Empty only denotes an untraced stored frame. */
public final class TraceIds {
    public static final int MAX_LENGTH = 128;
    private TraceIds() {}
    public static String validate(String value) {
        if (value == null || value.length() > MAX_LENGTH) throw new ProtocolException("invalid trace id");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '.' || c == '_' || c == ':' || c == '-'))
                throw new ProtocolException("invalid trace id");
        }
        return value;
    }
}
