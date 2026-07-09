package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertDeliveryWorkItem;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class AdminAlertDeliveryWorkerTest {

    @Test
    void sendsWebhookDeliveryAndMarksSent() {
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        FakeAlertRepository repository = new FakeAlertRepository(delivery(1));
        CapturingRestTemplate restTemplate = new CapturingRestTemplate(ResponseEntity.ok("ok"));
        AdminAlertDeliveryWorker worker = worker(repository, properties(), restTemplate);

        int processed = worker.runOnce(now);

        assertThat(processed).isEqualTo(1);
        assertThat(repository.claimLimit).isEqualTo(25);
        assertThat(repository.claimLease).isEqualTo(Duration.ofMinutes(2));
        assertThat(restTemplate.url).isEqualTo(URI.create("https://ops.example.com/alerts"));
        assertThat(restTemplate.requestEntity.getBody().toString()).contains("ADMIN_ALERT", "\"deliveryId\":101");
        assertThat(repository.sentId).isEqualTo(101L);
    }

    @Test
    void retriesHttpFailureUntilAttemptsAreExhausted() {
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        GatewayProperties properties = properties();
        properties.getAlerts().getDeliveryWorker().setMaxAttempts(3);
        properties.getAlerts().getDeliveryWorker().setRetryDelay(Duration.ofSeconds(45));
        FakeAlertRepository repository = new FakeAlertRepository(delivery(2));
        CapturingRestTemplate restTemplate = new CapturingRestTemplate(ResponseEntity.status(503).body("downstream down"));
        AdminAlertDeliveryWorker worker = worker(repository, properties, restTemplate);

        int processed = worker.runOnce(now);

        assertThat(processed).isEqualTo(1);
        assertThat(repository.failedId).isEqualTo(102L);
        assertThat(repository.exhausted).isFalse();
        assertThat(repository.nextAttemptAt).isEqualTo(now.plusSeconds(45));
        assertThat(repository.errorMessage).contains("HTTP 503", "downstream down");
    }

    @Test
    void marksFailureFinalWhenMaxAttemptsReached() {
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        GatewayProperties properties = properties();
        properties.getAlerts().getDeliveryWorker().setMaxAttempts(3);
        FakeAlertRepository repository = new FakeAlertRepository(delivery(3));
        CapturingRestTemplate restTemplate = new CapturingRestTemplate(ResponseEntity.status(500).body("failed"));
        AdminAlertDeliveryWorker worker = worker(repository, properties, restTemplate);

        worker.runOnce(now);

        assertThat(repository.failedId).isEqualTo(103L);
        assertThat(repository.exhausted).isTrue();
        assertThat(repository.nextAttemptAt).isNull();
    }

    private AdminAlertDeliveryWorker worker(FakeAlertRepository repository,
                                            GatewayProperties properties,
                                            RestTemplate restTemplate) {
        return new AdminAlertDeliveryWorker(repository, properties, restTemplate, new ObjectMapper());
    }

    private GatewayProperties properties() {
        return new GatewayProperties();
    }

    private AlertDeliveryWorkItem delivery(int attemptCount) {
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        return new AlertDeliveryWorkItem(
                100L + attemptCount,
                200L + attemptCount,
                300L,
                "OPS_WEBHOOK",
                "Ops webhook",
                "WEBHOOK",
                true,
                "https://ops.example.com/alerts",
                "PENDING",
                attemptCount,
                now.plusSeconds(120),
                now,
                "MARK_DEVIATION_WARN",
                "MARKET",
                "WARN",
                "OPEN",
                "mark/index deviation too large",
                now,
                now);
    }

    private static final class FakeAlertRepository extends AdminAlertRepository {
        private final List<AlertDeliveryWorkItem> deliveries = new ArrayList<>();
        private int claimLimit;
        private int claimMaxAttempts;
        private Duration claimLease;
        private Long sentId;
        private Long failedId;
        private String errorMessage;
        private boolean exhausted;
        private Instant nextAttemptAt;

        private FakeAlertRepository(AlertDeliveryWorkItem delivery) {
            super(null);
            deliveries.add(delivery);
        }

        @Override
        public List<AlertDeliveryWorkItem> claimPendingDeliveries(Instant now,
                                                                  int limit,
                                                                  int maxAttempts,
                                                                  Duration claimLease) {
            this.claimLimit = limit;
            this.claimMaxAttempts = maxAttempts;
            this.claimLease = claimLease;
            return deliveries;
        }

        @Override
        public void markDeliverySent(long deliveryId, Instant now) {
            this.sentId = deliveryId;
        }

        @Override
        public void markDeliveryFailed(long deliveryId,
                                       String errorMessage,
                                       boolean exhausted,
                                       Instant nextAttemptAt,
                                       Instant now) {
            this.failedId = deliveryId;
            this.errorMessage = errorMessage;
            this.exhausted = exhausted;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    private static final class CapturingRestTemplate extends RestTemplate {
        private final ResponseEntity<String> response;
        private URI url;
        private HttpEntity<?> requestEntity;

        private CapturingRestTemplate(ResponseEntity<String> response) {
            this.response = response;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(URI url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            this.url = url;
            this.requestEntity = requestEntity;
            return (ResponseEntity<T>) response;
        }
    }
}
