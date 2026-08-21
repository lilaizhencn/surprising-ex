package com.surprising.aeron.tools;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

record HttpWorkloadConfig(
        URI baseUri,
        Path outputDirectory,
        String runId,
        long seed,
        long ratePerSecond,
        Duration duration,
        int maxInFlight,
        Duration requestTimeout,
        Duration pollInterval,
        int maxPolls,
        long[] users,
        String[] symbols,
        TrafficSkew skew,
        Map<WorkloadOperation, Integer> traffic) {

    HttpWorkloadConfig {
        if (baseUri == null || !"http".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalArgumentException("baseUrl must use real HTTP");
        }
        if (outputDirectory == null) throw new IllegalArgumentException("output is required");
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("runId is invalid");
        }
        if (ratePerSecond <= 0) throw new IllegalArgumentException("rate must be positive");
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        if (maxInFlight <= 0) throw new IllegalArgumentException("maxInFlight must be positive");
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (pollInterval == null || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be non-negative");
        }
        if (maxPolls <= 0) throw new IllegalArgumentException("maxPolls must be positive");
        users = users == null ? new long[0] : users.clone();
        symbols = symbols == null ? new String[0] : symbols.clone();
        if (users.length == 0 || symbols.length == 0) throw new IllegalArgumentException("users and symbols required");
        for (long user : users) if (user <= 0) throw new IllegalArgumentException("users must be positive");
        for (String symbol : symbols) if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbols must be non-blank");
        }
        traffic = Map.copyOf(traffic);
        int total = traffic.values().stream().mapToInt(Integer::intValue).sum();
        if (total != 100 || traffic.keySet().size() != WorkloadOperation.values().length) {
            throw new IllegalArgumentException("traffic weights must define every operation and total 100");
        }
        if (traffic.values().stream().anyMatch(value -> value < 0)) {
            throw new IllegalArgumentException("traffic weights must be non-negative");
        }
    }

    static HttpWorkloadConfig from(Properties properties) {
        return new HttpWorkloadConfig(
                URI.create(required(properties, "baseUrl")),
                Path.of(required(properties, "output")),
                required(properties, "runId"),
                parseLong(properties, "seed"),
                parseLong(properties, "rate"),
                Duration.parse(required(properties, "duration")),
                Math.toIntExact(parseLong(properties, "maxInFlight")),
                Duration.parse(required(properties, "requestTimeout")),
                Duration.parse(required(properties, "pollInterval")),
                Math.toIntExact(parseLong(properties, "maxPolls")),
                parseLongs(required(properties, "users")),
                split(required(properties, "symbols")),
                TrafficSkew.valueOf(properties.getProperty("skew", "UNIFORM").trim().toUpperCase(Locale.ROOT)),
                parseTraffic(properties.getProperty("traffic")));
    }

    static Map<WorkloadOperation, Integer> defaultTraffic() {
        EnumMap<WorkloadOperation, Integer> traffic = new EnumMap<>(WorkloadOperation.class);
        traffic.put(WorkloadOperation.PLACE, 55);
        traffic.put(WorkloadOperation.CANCEL, 10);
        traffic.put(WorkloadOperation.AMEND, 5);
        traffic.put(WorkloadOperation.MARKET_IOC_CLOSE, 10);
        traffic.put(WorkloadOperation.TRIGGER, 8);
        traffic.put(WorkloadOperation.TRIGGER_CANCEL, 2);
        traffic.put(WorkloadOperation.BATCH_ALGO_CONTROL, 10);
        return Map.copyOf(traffic);
    }

    long totalIntents() {
        return Math.multiplyExact(ratePerSecond, duration.toNanos()) / 1_000_000_000L;
    }

    long expectedIntervalNanos() {
        return Math.max(1L, 1_000_000_000L / ratePerSecond);
    }

    private static Map<WorkloadOperation, Integer> parseTraffic(String value) {
        if (value == null || value.isBlank()) return defaultTraffic();
        EnumMap<WorkloadOperation, Integer> result = new EnumMap<>(WorkloadOperation.class);
        for (String item : value.split(",")) {
            String[] parts = item.trim().split("=", -1);
            if (parts.length != 2) throw new IllegalArgumentException("malformed traffic entry: " + item);
            result.put(WorkloadOperation.valueOf(parts[0].trim().toUpperCase(Locale.ROOT)),
                    Integer.parseInt(parts[1].trim()));
        }
        return result;
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static long parseLong(Properties properties, String name) {
        try {
            return Long.parseLong(required(properties, name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static long[] parseLongs(String value) {
        String[] values = split(value);
        long[] result = new long[values.length];
        for (int index = 0; index < values.length; index++) result[index] = Long.parseLong(values[index]);
        return result;
    }

    private static String[] split(String value) {
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                .toArray(String[]::new);
    }
}
