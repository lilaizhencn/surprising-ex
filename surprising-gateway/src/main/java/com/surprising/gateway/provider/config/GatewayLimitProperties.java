package com.surprising.gateway.provider.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.DefaultValue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("surprising.gateway.limits")
@Validated
@Getter
@Setter
public class GatewayLimitProperties {
    public enum Group { READ, ORDER, CANCEL, CANCEL_ALL, RECEIPT, FUNDS, SECURITY, ADMIN, WEBSOCKET, UPLOAD }
    public record Rule(@Min(1) @Max(1000000) int rate, @Min(1) @Max(1000000) int burst,
                       @NotNull @DefaultValue("1s") Duration period) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Rule { }
        public Rule(int rate, int burst) { this(rate, burst, Duration.ofSeconds(1)); }
        @AssertTrue(message = "period must be 1ms..1d and a full bucket must refill within one day")
        public boolean isBounded() {
            return period != null && period.compareTo(Duration.ofMillis(1)) >= 0 && period.compareTo(Duration.ofDays(1)) <= 0
                    && rate > 0 && burst > 0 && period.toMillis() * burst <= (long) rate * 86400000;
        }
    }

    private boolean enabled = true;
    @Pattern(regexp = "[A-Za-z0-9:_-]{1,64}")
    private String namespace = "gateway:limits:v1";
    @Valid @NotNull private Rule ip = new Rule(300, 600);
    @Valid @NotNull private Rule loginIp = new Rule(1, 5);
    @Valid @NotNull private Rule loginAccount = new Rule(5, 5, Duration.ofMinutes(1));
    @Valid @NotNull private Rule publicRequests = new Rule(2000, 4000);
    @Valid @NotNull private Rule reservedRequests = new Rule(500, 1000);
    @Valid @NotNull private Rule registrationIp = new Rule(5, 5, Duration.ofHours(1));
    @Valid @NotNull private Rule refreshIp = new Rule(10, 20);
    @Valid @NotNull private Rule verificationIp = new Rule(10, 10, Duration.ofMinutes(1));
    @Valid @NotNull private Rule emailCooldown = new Rule(1, 1, Duration.ofMinutes(1));
    @Valid @NotNull private Rule emailHourly = new Rule(5, 5, Duration.ofHours(1));
    @Valid @NotNull private Rule emailDaily = new Rule(20, 20, Duration.ofDays(1));
    @Valid @NotNull private Rule emailGlobal = new Rule(10000, 100, Duration.ofDays(1));
    @Valid @NotNull private Rule uploadCount = new Rule(100, 100, Duration.ofDays(1));
    @Valid @NotNull private Rule uploadKibibytes = new Rule(102400, 102400, Duration.ofDays(1));
    @Valid @NotNull private Rule uploadGlobalKibibytes = new Rule(1000000, 1000000, Duration.ofDays(1));
    @Valid @NotNull private Rule uploadVerification = new Rule(20, 5, Duration.ofMinutes(1));
    @Valid @NotNull private Rule uploadGlobalVerification = new Rule(10, 20);
    @Min(1) @Max(64) private int concurrentPasswordHashes = 4;
    @Min(1) @Max(64) private int concurrentUploads = 4;
    @Valid @NotNull private Rule newWebSocket = new Rule(1, 10);
    @Valid @NotNull private Rule socketMessages = new Rule(10, 512);
    @Valid @NotNull private Rule socketAuthentication = new Rule(2, 20);
    @Valid @NotNull private Map<Group, Rule> user = defaults(false);
    @Valid @NotNull private Map<Group, Rule> apiKey = defaults(true);
    @Valid @NotNull private Map<Group, Rule> product = new EnumMap<>(Map.of(
            Group.READ, new Rule(60, 120), Group.ORDER, new Rule(20, 40), Group.CANCEL, new Rule(20, 80)));
    @Min(1) @Max(100000) private int concurrentRequests = 256;
    @Min(1) @Max(10000) private int concurrentCancels = 64;
    @Min(1) @Max(10000) private int concurrentReceipts = 64;
    @Min(1) @Max(10000) private int socketsPerIp = 20;
    @Min(1) @Max(1000) private int socketsPerUser = 10;
    @Min(1) @Max(100000) private int socketsPerGateway = 2000;
    @Min(30) @Max(300) private int socketLeaseSeconds = 60;

    @AssertTrue(message = "user/api-key policies must cover every group; product policies must cover READ, ORDER and CANCEL")
    public boolean isComplete() {
        return user != null && apiKey != null && product != null
                && user.keySet().containsAll(EnumSet.allOf(Group.class))
                && apiKey.keySet().containsAll(EnumSet.allOf(Group.class))
                && product.keySet().equals(EnumSet.of(Group.READ, Group.ORDER, Group.CANCEL));
    }

    private static Map<Group, Rule> defaults(boolean key) {
        var rules = new EnumMap<Group, Rule>(Group.class);
        for (var group : Group.values()) {
            Rule rule = switch (group) {
                case READ -> new Rule(100, 200);
                case ORDER -> new Rule(30, 60);
                case CANCEL -> new Rule(30, 100);
                case CANCEL_ALL -> new Rule(1, 12);
                case RECEIPT -> new Rule(20, 40);
                case FUNDS -> new Rule(2, 5);
                case SECURITY -> new Rule(2, 10);
                case ADMIN -> new Rule(30, 60);
                case WEBSOCKET -> new Rule(20, 512);
                case UPLOAD -> new Rule(2, 5);
            };
            rules.put(group, key ? new Rule(Math.max(1, rule.rate() / 2), Math.max(1, rule.burst() / 2), rule.period()) : rule);
        }
        return rules;
    }
}
