package com.surprising.price.index.client;

import com.surprising.price.index.config.IndexPriceProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** 外部现货/指数行情多交易所 JSON 报文解析与合约名匹配器（无状态）。 */
final class ExternalSpotTickerParser {

    private ExternalSpotTickerParser() {
    }

    record ParsedTicker(BigDecimal price, BigDecimal bidPrice, BigDecimal askPrice,
                        Instant sourceTime, String instrument) {
    }

    static boolean matchesWebSocketAncestry(IndexPriceProperties.SourceConfig source, String parser, JsonNode root) {
        String expected = source.getSourceSymbol();
        return switch (parser.toUpperCase(Locale.ROOT)) {
            case "OKX_INDEX_TICKER" -> "index-tickers".equals(root.path("arg").path("channel").asString())
                    && sameProtocolInstrument(root.path("arg").path("instId").asString(null), expected)
                    && sameProtocolInstrument(root.path("data").path(0).path("instId").asString(null), expected)
                    && sameProtocolInstrument(root.path("data").path(0).path("instId").asString(null),
                    root.path("arg").path("instId").asString(null));
            case "OKX_TICKER" -> matchesOptionalInstrument(root.path("data").path(0), "instId", expected);
            case "BINANCE_BOOK_TICKER" -> {
                JsonNode ticker = root.has("data") ? root.path("data") : root;
                String actual = firstText(ticker, "symbol", "s");
                yield actual == null || sameInstrument(actual, expected);
            }
            case "COINBASE_TICKER" -> "ticker".equals(root.path("type").asString())
                    && matchesOptionalInstrument(root, "product_id", expected);
            case "KRAKEN_TICKER" -> "ticker".equals(root.path("channel").asString())
                    && matchesOptionalInstrument(root.path("data").path(0), "symbol", expected);
            case "BYBIT_TICKER" -> expected != null && !expected.isBlank()
                    && ("tickers." + expected).equals(root.path("topic").asString());
            default -> true;
        };
    }

    static ParsedTicker parseTicker(String parser, JsonNode root, int scale) {
        return switch (parser.toUpperCase(Locale.ROOT)) {
            case "BINANCE_BOOK_TICKER" -> parseBinanceBookTicker(root, scale);
            case "OKX_TICKER" -> parseOkxTicker(root, scale);
            case "OKX_INDEX_TICKER" -> parseOkxIndexTicker(root);
            case "BYBIT_TICKER" -> parseBybitTicker(root, scale);
            case "COINBASE_TICKER" -> parseCoinbaseTicker(root, scale);
            case "KRAKEN_TICKER" -> parseKrakenTicker(root, scale);
            default -> throw new IllegalArgumentException("Unsupported parser: " + parser);
        };
    }

    static boolean sameInstrument(String left, String right) {
        return normalizeInstrument(left).equals(normalizeInstrument(right));
    }

    private static boolean matchesOptionalInstrument(JsonNode ticker, String field, String expected) {
        String actual = firstText(ticker, field);
        return actual == null || sameInstrument(actual, expected);
    }

    private static ParsedTicker parseBinanceBookTicker(JsonNode root, int scale) {
        JsonNode ticker = root.has("data") ? root.path("data") : root;
        BigDecimal bid = firstDecimal(ticker, "bidPrice", "b");
        BigDecimal ask = firstDecimal(ticker, "askPrice", "a");
        Instant sourceTime = ticker.hasNonNull("E") ? Instant.ofEpochMilli(ticker.path("E").asLong()) : Instant.now();
        return new ParsedTicker(midOrLast(bid, ask, null, scale), bid, ask, sourceTime,
                firstText(ticker, "symbol", "s"));
    }

    private static ParsedTicker parseOkxTicker(JsonNode root, int scale) {
        JsonNode ticker = root.path("data").path(0);
        BigDecimal bid = decimal(ticker, "bidPx");
        BigDecimal ask = decimal(ticker, "askPx");
        BigDecimal last = decimal(ticker, "last");
        Instant sourceTime = epochMillis(ticker.path("ts").asString(null));
        return new ParsedTicker(midOrLast(bid, ask, last, scale), bid, ask, sourceTime, firstText(ticker, "instId"));
    }

    private static ParsedTicker parseOkxIndexTicker(JsonNode root) {
        JsonNode ticker = root.path("data").path(0);
        BigDecimal indexPrice = decimal(ticker, "idxPx");
        Instant sourceTime = epochMillis(ticker.path("ts").asString(null));
        if (!positive(indexPrice)) {
            throw new IllegalArgumentException("OKX index ticker has no valid idxPx");
        }
        return new ParsedTicker(indexPrice, null, null, sourceTime, firstText(ticker, "instId"));
    }

    private static ParsedTicker parseBybitTicker(JsonNode root, int scale) {
        JsonNode ticker = root.path("data");
        if (ticker.isArray()) {
            ticker = ticker.path(0);
        }
        if (ticker.isMissingNode() || ticker.isNull() || ticker.isEmpty()) {
            ticker = root.path("result").path("list").path(0);
        }
        BigDecimal bid = decimal(ticker, "bid1Price");
        BigDecimal ask = decimal(ticker, "ask1Price");
        BigDecimal last = decimal(ticker, "lastPrice");
        Instant sourceTime = root.hasNonNull("ts")
                ? Instant.ofEpochMilli(root.path("ts").asLong())
                : Instant.now();
        return new ParsedTicker(midOrLast(bid, ask, last, scale), bid, ask, sourceTime, firstText(ticker, "symbol"));
    }

    private static ParsedTicker parseCoinbaseTicker(JsonNode root, int scale) {
        BigDecimal bid = firstDecimal(root, "bid", "best_bid");
        BigDecimal ask = firstDecimal(root, "ask", "best_ask");
        BigDecimal price = decimal(root, "price");
        Instant sourceTime = root.hasNonNull("time") ? Instant.parse(root.get("time").asString()) : Instant.now();
        return new ParsedTicker(midOrLast(bid, ask, price, scale), bid, ask, sourceTime, firstText(root, "product_id"));
    }

    private static ParsedTicker parseKrakenTicker(JsonNode root, int scale) {
        JsonNode data = root.path("data");
        if (data.isArray() && !data.isEmpty()) {
            JsonNode ticker = data.path(0);
            BigDecimal bid = decimal(ticker, "bid");
            BigDecimal ask = decimal(ticker, "ask");
            BigDecimal last = decimal(ticker, "last");
            return new ParsedTicker(midOrLast(bid, ask, last, scale), bid, ask,
                    Instant.parse(ticker.path("timestamp").asString()), firstText(ticker, "symbol"));
        }

        JsonNode result = root.path("result");
        if (result.propertyNames().isEmpty()) {
            throw new IllegalArgumentException("Kraken response has no ticker result");
        }
        String instrument = result.propertyNames().iterator().next();
        JsonNode ticker = result.path(instrument);
        BigDecimal bid = decimal(ticker.path("b"), 0);
        BigDecimal ask = decimal(ticker.path("a"), 0);
        BigDecimal last = decimal(ticker.path("c"), 0);
        return new ParsedTicker(midOrLast(bid, ask, last, scale), bid, ask, Instant.now(), instrument);
    }

    private static BigDecimal midOrLast(BigDecimal bid, BigDecimal ask, BigDecimal last, int scale) {
        if (positive(bid) && positive(ask)) {
            return bid.add(ask).divide(BigDecimal.valueOf(2), scale, RoundingMode.HALF_UP);
        }
        if (positive(last)) {
            return last;
        }
        throw new IllegalArgumentException("ticker has no valid bid/ask/last price");
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal firstDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal value = decimal(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return decimalValue(node.path(field));
    }

    private static BigDecimal decimal(JsonNode node, int index) {
        return decimalValue(node.path(index));
    }

    private static BigDecimal decimalValue(JsonNode value) {
        if (value.isArray()) {
            value = value.path(0);
        }
        if (value.isMissingNode() || value.isNull() || value.asString().isBlank()) {
            return null;
        }
        return new BigDecimal(value.asString());
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asString().isBlank()) {
                return value.asString();
            }
        }
        return null;
    }

    private static Instant epochMillis(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        return Instant.ofEpochMilli(Long.parseLong(value));
    }

    private static boolean sameProtocolInstrument(String actual, String expected) {
        if (actual == null || actual.isEmpty() || expected == null || expected.isEmpty()
                || actual.length() != expected.length()) {
            return false;
        }
        for (int index = 0; index < actual.length(); index++) {
            char actualCharacter = actual.charAt(index);
            char expectedCharacter = expected.charAt(index);
            if (actualCharacter != expectedCharacter
                    && (!isAsciiLetter(actualCharacter) || !isAsciiLetter(expectedCharacter)
                    || asciiUppercase(actualCharacter) != asciiUppercase(expectedCharacter))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static char asciiUppercase(char value) {
        return value >= 'a' && value <= 'z' ? (char) (value - ('a' - 'A')) : value;
    }

    private static String normalizeInstrument(String instrument) {
        if (instrument == null) {
            return "";
        }
        String upper = instrument.toUpperCase(Locale.ROOT);
        StringBuilder filtered = null;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                if (filtered != null) filtered.append(c);
            } else if (filtered == null) {
                filtered = new StringBuilder(upper.length());
                filtered.append(upper, 0, i);
            }
        }
        String normalized = filtered == null ? upper : filtered.toString();
        if (normalized.startsWith("XBT")) {
            return "BTC" + normalized.substring(3);
        }
        return normalized;
    }
}
