package com.surprising.aeron.protocol;

public record CoreOrderBookQuery(String symbol, int depth) {

    public static final int DEFAULT_DEPTH = 30;
    public static final int MAX_DEPTH = 100;

    public CoreOrderBookQuery {
        symbol = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (!validSymbol(symbol)) {
            throw new IllegalArgumentException("book symbol is required");
        }
        if (depth == 0) depth = DEFAULT_DEPTH;
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("invalid book depth");
        }
    }

    static boolean validSymbol(String value) {
        int length = value.length();
        if (length < 2 || length > 64 || !asciiAlphaNumeric(value.charAt(0))) return false;
        for (int index = 1; index < length; index++) {
            char character = value.charAt(index);
            if (!asciiAlphaNumeric(character) && character != '_' && character != '-') return false;
        }
        return true;
    }

    private static boolean asciiAlphaNumeric(char character) {
        return character >= 'A' && character <= 'Z' || character >= '0' && character <= '9';
    }
}
