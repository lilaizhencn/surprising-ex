package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.config.GatewayProperties.BackendRoute;
import com.surprising.gateway.provider.config.GatewayProperties.KafkaConsumerGroup;
import com.surprising.gateway.provider.config.GatewayProperties.KafkaLag;
import com.surprising.gateway.provider.config.GatewayProperties.PrometheusMonitor;
import com.surprising.gateway.provider.config.GatewayProperties.WebSocketMonitor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/system")
public class AdminSystemObservabilityController {

    private final AuthService authService;
    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public AdminSystemObservabilityController(AuthService authService,
                                              GatewayProperties properties,
                                              RestTemplate restTemplate) {
        this.authService = authService;
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @GetMapping("/observability")
    public SystemObservabilityResponse observability(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        try {
            JwtPrincipal principal = authService.requireAdminPermission(authorization, "admin.system.read");
            List<SystemObservabilityWarning> warnings = new ArrayList<>();
            return new SystemObservabilityResponse(
                    Instant.now(),
                    kafkaLag(warnings),
                    webSocketMetrics(principal, warnings),
                    prometheusScrapes(warnings),
                    warnings);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private KafkaLagResponse kafkaLag(List<SystemObservabilityWarning> warnings) {
        KafkaLag config = properties.getObservability().getKafka();
        if (!config.isEnabled()) {
            return new KafkaLagResponse(false, config.getBootstrapServers(), 0, 0, 0, List.of(),
                    "Kafka lag monitoring is disabled");
        }
        List<KafkaConsumerGroup> configuredGroups = config.getConsumerGroups().stream()
                .filter(group -> group.getGroupId() != null && !group.getGroupId().isBlank())
                .toList();
        if (configuredGroups.isEmpty()) {
            return new KafkaLagResponse(true, config.getBootstrapServers(), 0, 0, 0, List.of(),
                    "no Kafka consumer groups configured");
        }
        Properties clientProperties = new Properties();
        int timeoutMs = (int) Math.min(Integer.MAX_VALUE, Math.max(1000, config.getRequestTimeout().toMillis()));
        clientProperties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        clientProperties.put(AdminClientConfig.CLIENT_ID_CONFIG, config.getClientId());
        clientProperties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        clientProperties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);
        List<KafkaGroupLag> groups = new ArrayList<>();
        try (Admin admin = Admin.create(clientProperties)) {
            for (KafkaConsumerGroup group : configuredGroups) {
                groups.add(kafkaGroupLag(admin, group, config.getRequestTimeout(), config.getMaxPartitionsPerGroup()));
            }
        } catch (Exception ex) {
            String message = truncate(ex.getMessage(), 500);
            warnings.add(new SystemObservabilityWarning("kafka", "admin-client", message));
            return new KafkaLagResponse(true, config.getBootstrapServers(), configuredGroups.size(), 0, 0,
                    groups, message);
        }
        long totalLag = groups.stream().mapToLong(KafkaGroupLag::totalLag).sum();
        long maxLag = groups.stream().mapToLong(KafkaGroupLag::maxLag).max().orElse(0);
        return new KafkaLagResponse(true, config.getBootstrapServers(), groups.size(), totalLag, maxLag, groups, null);
    }

    private KafkaGroupLag kafkaGroupLag(Admin admin,
                                        KafkaConsumerGroup group,
                                        Duration timeout,
                                        int maxPartitionsPerGroup) {
        String groupId = group.getGroupId().trim();
        String state = consumerGroupState(admin, groupId, timeout);
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Set<String> topicFilter = Set.copyOf(group.getTopics() == null ? List.of() : group.getTopics());
            List<TopicPartition> partitions = committed.keySet().stream()
                    .filter(partition -> topicFilter.isEmpty() || topicFilter.contains(partition.topic()))
                    .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                    .toList();
            boolean truncated = partitions.size() > maxPartitionsPerGroup;
            partitions = partitions.stream().limit(maxPartitionsPerGroup).toList();
            Map<TopicPartition, OffsetSpec> latestRequest = new LinkedHashMap<>();
            partitions.forEach(partition -> latestRequest.put(partition, OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResultInfo> latest = latestRequest.isEmpty()
                    ? Map.of()
                    : admin.listOffsets(latestRequest).all()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            List<KafkaPartitionLag> rows = new ArrayList<>();
            long totalLag = 0;
            long maxLag = 0;
            for (TopicPartition partition : partitions) {
                OffsetAndMetadata currentOffset = committed.get(partition);
                ListOffsetsResultInfo endOffset = latest.get(partition);
                long current = currentOffset == null ? -1 : currentOffset.offset();
                long end = endOffset == null ? -1 : endOffset.offset();
                long lag = current < 0 || end < 0 ? 0 : Math.max(0, end - current);
                totalLag += lag;
                maxLag = Math.max(maxLag, lag);
                rows.add(new KafkaPartitionLag(partition.topic(), partition.partition(), current, end, lag));
            }
            return new KafkaGroupLag(groupId, state, rows.size(), totalLag, maxLag, truncated, rows, null);
        } catch (Exception ex) {
            return new KafkaGroupLag(groupId, state, 0, 0, 0, false, List.of(), truncate(ex.getMessage(), 500));
        }
    }

    private String consumerGroupState(Admin admin, String groupId, Duration timeout) {
        try {
            return admin.describeConsumerGroups(List.of(groupId))
                    .describedGroups()
                    .get(groupId)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .groupState()
                    .name();
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }

    private WebSocketMetricsResponse webSocketMetrics(JwtPrincipal principal,
                                                      List<SystemObservabilityWarning> warnings) {
        WebSocketMonitor config = properties.getObservability().getWebSocket();
        String service = config.getAdminRoute();
        if (!config.isEnabled()) {
            return WebSocketMetricsResponse.disabled(service, "WebSocket monitoring is disabled");
        }
        BackendRoute route = properties.getAdminRoutes().get(service);
        if (route == null) {
            String message = "admin route is not configured: " + service;
            warnings.add(new SystemObservabilityWarning("websocket", service, message));
            return WebSocketMetricsResponse.disabled(service, message);
        }
        URI target = URI.create(trimTrailingSlash(route.getBaseUrl())
                + ensureLeadingSlash(route.getTargetPrefix()) + "/metrics");
        Instant startedAt = Instant.now();
        try {
            HttpHeaders headers = adminHeaders(principal, route);
            ResponseEntity<Map> response = restTemplate.exchange(target, HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);
            long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
            Map<?, ?> body = response.getBody() == null ? Map.of() : response.getBody();
            return new WebSocketMetricsResponse(
                    true,
                    "UP",
                    service,
                    target.toString(),
                    response.getStatusCode().value(),
                    latencyMs,
                    longValue(body.get("activeConnections")),
                    longValue(body.get("authenticatedConnections")),
                    longValue(body.get("anonymousConnections")),
                    longValue(body.get("totalSubscriptions")),
                    longValue(body.get("uniqueTopics")),
                    longValue(body.get("maxSubscriptionsPerSession")),
                    records(body.get("channels")),
                    null);
        } catch (ResourceAccessException ex) {
            return websocketFailed(warnings, service, target, startedAt, "websocket admin metrics request timed out");
        } catch (RestClientException | IllegalArgumentException ex) {
            return websocketFailed(warnings, service, target, startedAt, ex.getMessage());
        }
    }

    private WebSocketMetricsResponse websocketFailed(List<SystemObservabilityWarning> warnings,
                                                     String service,
                                                     URI target,
                                                     Instant startedAt,
                                                     String message) {
        String error = truncate(message, 500);
        warnings.add(new SystemObservabilityWarning("websocket", service, error));
        return new WebSocketMetricsResponse(true, "DOWN", service, target.toString(), null,
                Duration.between(startedAt, Instant.now()).toMillis(), 0, 0, 0, 0, 0, 0,
                List.of(), error);
    }

    private PrometheusScrapeResponse prometheusScrapes(List<SystemObservabilityWarning> warnings) {
        PrometheusMonitor config = properties.getObservability().getPrometheus();
        if (!config.isEnabled()) {
            return new PrometheusScrapeResponse(false, 0, 0, 0, List.of(),
                    "Prometheus scrape proxy is disabled");
        }
        List<PrometheusTarget> targets = prometheusTargets();
        List<PrometheusTargetScrape> scrapes = targets.stream()
                .map(target -> scrapePrometheusTarget(target, config, warnings))
                .toList();
        long up = scrapes.stream().filter(scrape -> "UP".equals(scrape.status())).count();
        long down = scrapes.size() - up;
        return new PrometheusScrapeResponse(true, scrapes.size(), up, down, scrapes, null);
    }

    private List<PrometheusTarget> prometheusTargets() {
        Map<String, PrometheusTarget> targets = new LinkedHashMap<>();
        properties.getAdminRoutes().forEach((service, route) -> {
            String baseUrl = trimTrailingSlash(route.getBaseUrl());
            String key = baseUrl + "|" + route.hasBasicAuth();
            targets.putIfAbsent(key, new PrometheusTarget(service, baseUrl, route));
        });
        return new ArrayList<>(targets.values());
    }

    private PrometheusTargetScrape scrapePrometheusTarget(PrometheusTarget target,
                                                         PrometheusMonitor config,
                                                         List<SystemObservabilityWarning> warnings) {
        URI uri = URI.create(target.baseUrl() + "/actuator/prometheus");
        Instant startedAt = Instant.now();
        try {
            HttpHeaders headers = new HttpHeaders();
            if (target.route().hasBasicAuth()) {
                headers.setBasicAuth(target.route().getBasicAuthUsername(), target.route().getBasicAuthPassword());
            }
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
            String body = response.getBody() == null ? "" : response.getBody();
            boolean truncated = body.getBytes(StandardCharsets.UTF_8).length > config.getMaxBodyBytes();
            String parsedBody = truncated ? body.substring(0, Math.min(body.length(), config.getMaxBodyBytes())) : body;
            PrometheusSampleSummary summary = summarizePrometheus(parsedBody, config.getSamplePreviewLimit());
            String status = response.getStatusCode().is2xxSuccessful() ? "UP" : "DOWN";
            return new PrometheusTargetScrape(target.service(), uri.toString(), status,
                    response.getStatusCode().value(), latencyMs, summary.sampleCount(),
                    summary.metricFamilies(), truncated, summary.preview(), null);
        } catch (ResourceAccessException ex) {
            return prometheusFailed(warnings, target, uri, startedAt, "prometheus scrape request timed out");
        } catch (RestClientException | IllegalArgumentException ex) {
            return prometheusFailed(warnings, target, uri, startedAt, ex.getMessage());
        }
    }

    private PrometheusTargetScrape prometheusFailed(List<SystemObservabilityWarning> warnings,
                                                    PrometheusTarget target,
                                                    URI uri,
                                                    Instant startedAt,
                                                    String message) {
        String error = truncate(message, 500);
        warnings.add(new SystemObservabilityWarning("prometheus", target.service(), error));
        return new PrometheusTargetScrape(target.service(), uri.toString(), "DOWN", null,
                Duration.between(startedAt, Instant.now()).toMillis(), 0, 0, false, List.of(), error);
    }

    private PrometheusSampleSummary summarizePrometheus(String body, int previewLimit) {
        long samples = 0;
        Set<String> families = new java.util.HashSet<>();
        List<String> preview = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            samples++;
            String family = value.split("[{\\s]", 2)[0];
            if (!family.isBlank()) {
                families.add(family);
            }
            if (preview.size() < previewLimit) {
                preview.add(truncate(value, 180));
            }
        }
        return new PrometheusSampleSummary(samples, families.size(), preview);
    }

    private HttpHeaders adminHeaders(JwtPrincipal principal, BackendRoute route) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-User-Id", Long.toString(principal.userId()));
        headers.set("X-Admin-Username", principal.username());
        headers.set("X-Admin-Roles", String.join(",", principal.roles()));
        if (route.hasBasicAuth()) {
            headers.setBasicAuth(route.getBasicAuthUsername(), route.getBasicAuthPassword());
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((key, mapValue) -> row.put(String.valueOf(key), mapValue));
                    rows.add(row);
                }
            }
            return rows;
        }
        return List.of();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Long.parseLong(stringValue.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("backend baseUrl is required");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String ensureLeadingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record PrometheusTarget(
            String service,
            String baseUrl,
            BackendRoute route) {
    }

    private record PrometheusSampleSummary(
            long sampleCount,
            int metricFamilies,
            List<String> preview) {
    }

    public record SystemObservabilityResponse(
            Instant generatedAt,
            KafkaLagResponse kafka,
            WebSocketMetricsResponse webSocket,
            PrometheusScrapeResponse prometheus,
            List<SystemObservabilityWarning> warnings) {
    }

    public record KafkaLagResponse(
            boolean enabled,
            String bootstrapServers,
            int groupCount,
            long totalLag,
            long maxLag,
            List<KafkaGroupLag> groups,
            String error) {
    }

    public record KafkaGroupLag(
            String groupId,
            String state,
            int partitionCount,
            long totalLag,
            long maxLag,
            boolean truncated,
            List<KafkaPartitionLag> partitions,
            String error) {
    }

    public record KafkaPartitionLag(
            String topic,
            int partition,
            long currentOffset,
            long endOffset,
            long lag) {
    }

    public record WebSocketMetricsResponse(
            boolean enabled,
            String status,
            String service,
            String targetUrl,
            Integer httpStatus,
            long latencyMs,
            long activeConnections,
            long authenticatedConnections,
            long anonymousConnections,
            long totalSubscriptions,
            long uniqueTopics,
            long maxSubscriptionsPerSession,
            List<Map<String, Object>> channels,
            String error) {

        static WebSocketMetricsResponse disabled(String service, String error) {
            return new WebSocketMetricsResponse(false, "DISABLED", service, null, null, 0,
                    0, 0, 0, 0, 0, 0, List.of(), error);
        }
    }

    public record PrometheusScrapeResponse(
            boolean enabled,
            int targetCount,
            long up,
            long down,
            List<PrometheusTargetScrape> targets,
            String error) {
    }

    public record PrometheusTargetScrape(
            String service,
            String url,
            String status,
            Integer httpStatus,
            long latencyMs,
            long sampleCount,
            int metricFamilies,
            boolean truncated,
            List<String> preview,
            String error) {
    }

    public record SystemObservabilityWarning(
            String module,
            String source,
            String message) {
    }
}
