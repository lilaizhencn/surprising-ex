package com.surprising.websocket.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class AuthenticatedWebSocketAuditClientTest {

    private static final String PRIVATE_TOPIC = "surprising.linear-perp.core.events.v1";
    private static final String PUBLIC_TOPIC = "surprising.linear-perp.price.events.v1";

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsMalformedConfigurationBeforeOpeningSocket() {
        assertThatThrownBy(() -> new AuthenticatedWebSocketAuditClient.Configuration(
                "client-1", java.net.URI.create("http://127.0.0.1/ws"), "token-1", 1L,
                List.of(new AuthenticatedWebSocketAuditClient.Subscription(
                        "orders", "BTC-USDT", "LINEAR_PERPETUAL", PRIVATE_TOPIC)),
                0, Duration.ZERO, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WebSocket URI");
    }

    @Test
    void rejectsInvalidAuthenticationWithoutReconnectLoop() throws Exception {
        try (LocalWebSocketProtocolStub stub = new LocalWebSocketProtocolStub();
             WebSocketAuditLedger ledger = WebSocketAuditLedger.open(
                     temporaryDirectory.resolve("auth-failure.jsonl"), new ObjectMapper());
             AuthenticatedWebSocketAuditClient client = client(stub, ledger, "invalid", 1L, 16, Duration.ZERO)) {
            client.start();

            assertThat(client.awaitAuthenticationFailure(Duration.ofSeconds(5)))
                    .as("connections=%s handshakes=%s commands=%s authErrors=%s failure=%s records=%s",
                            stub.acceptedConnections(), stub.handshakeResponses(),
                            stub.receivedCommands(), stub.sentAuthenticationErrors(), stub.lastFailure(),
                            ledger.records())
                    .isTrue();
            Thread.sleep(100L);
            assertThat(stub.acceptedConnections()).isEqualTo(1L);
            assertThat(new LayerContinuityAuditor().audit(ledger.records()).authenticationFailures()).isEqualTo(1L);
        }
    }

    @Test
    void boundedInboundQueueRecordsBackpressureAndStopsClient() throws Exception {
        try (LocalWebSocketProtocolStub stub = new LocalWebSocketProtocolStub();
             WebSocketAuditLedger ledger = WebSocketAuditLedger.open(
                     temporaryDirectory.resolve("backpressure.jsonl"), new ObjectMapper());
             AuthenticatedWebSocketAuditClient client = client(stub, ledger, "token-2", 2L, 8,
                     Duration.ofMillis(100))) {
            client.start();
            assertThat(client.awaitReady(Duration.ofSeconds(5))).isTrue();
            for (int sequence = 1; sequence <= 50; sequence++) {
                stub.broadcast(sequence);
            }

            assertThat(client.awaitQueueRejection(Duration.ofSeconds(5))).isTrue();
            assertThat(new LayerContinuityAuditor().audit(ledger.records()).queueRejections()).isEqualTo(1L);
        }
    }

    @Test
    void queueRejectionIsNotObservableUntilItIsDurablyFlushed() throws Exception {
        Path ledgerPath = temporaryDirectory.resolve("queue-rejection-race.jsonl");
        ObjectMapper objectMapper = new ObjectMapper();
        try (LocalWebSocketProtocolStub stub = new LocalWebSocketProtocolStub();
             WebSocketAuditLedger ledger = WebSocketAuditLedger.open(ledgerPath, objectMapper);
             AuthenticatedWebSocketAuditClient client = client(stub, ledger, "token-3", 3L, 8,
                     Duration.ZERO);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            client.start();
            assertThat(client.awaitReady(Duration.ofSeconds(5))).isTrue();
            assertThat(client.awaitCaughtUp(PRIVATE_TOPIC, 0L, Duration.ofSeconds(5))).isTrue();
            assertThat(client.awaitCaughtUp(PUBLIC_TOPIC, 0L, Duration.ofSeconds(5))).isTrue();

            CountDownLatch awaitReturned = new CountDownLatch(1);
            Future<Boolean> queueRejection = executor.submit(() -> {
                try {
                    return client.awaitQueueRejection(Duration.ofSeconds(5));
                } finally {
                    awaitReturned.countDown();
                }
            });
            synchronized (ledger) {
                for (int sequence = 1; sequence <= 10; sequence++) {
                    stub.broadcast(sequence);
                }

                if (awaitReturned.await(5L, TimeUnit.SECONDS)
                        && queueRejection.get(1L, TimeUnit.SECONDS)) {
                    try (WebSocketAuditLedger reopened = WebSocketAuditLedger.open(ledgerPath, objectMapper)) {
                        assertThat(new LayerContinuityAuditor().audit(reopened.records()).queueRejections())
                                .as("awaitQueueRejection returned before the held append/flush completed")
                                .isEqualTo(1L);
                    }
                }
            }
            assertThat(client.awaitQueueRejection(Duration.ofSeconds(5))).isTrue();
            try (WebSocketAuditLedger reopened = WebSocketAuditLedger.open(ledgerPath, objectMapper)) {
                assertThat(new LayerContinuityAuditor().audit(reopened.records()).queueRejections()).isEqualTo(1L);
            }
        }
    }

    @Test
    void oneHundredAuthenticatedClientsReconnectAndCatchUpWhileEventsFlow() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path ledgerPath = manualEvidencePath("hundred-clients.jsonl");
        try (LocalWebSocketProtocolStub stub = new LocalWebSocketProtocolStub();
             WebSocketAuditLedger ledger = WebSocketAuditLedger.open(
                     ledgerPath, objectMapper)) {
            List<AuthenticatedWebSocketAuditClient> clients = new ArrayList<>();
            try {
                for (long userId = 1; userId <= 100; userId++) {
                    AuthenticatedWebSocketAuditClient client = client(stub, ledger, "token-" + userId,
                            userId, 512, Duration.ZERO);
                    clients.add(client);
                    client.start();
                }
                assertThat(clients).allMatch(client -> client.awaitReady(Duration.ofSeconds(10)));

                for (int sequence = 1; sequence <= 10; sequence++) {
                    stub.broadcast(sequence);
                }
                stub.disconnectClients();
                for (int sequence = 11; sequence <= 30; sequence++) {
                    stub.broadcast(sequence);
                    Thread.sleep(2L);
                }
                stub.announceCatchUp(30L);

                assertThat(clients).allMatch(client -> client.awaitCaughtUp(PRIVATE_TOPIC, 30,
                        Duration.ofSeconds(15)));
                assertThat(clients).allMatch(client -> client.awaitCaughtUp(PUBLIC_TOPIC, 30,
                        Duration.ofSeconds(15)));
                LayerContinuityAuditor.Report report = new LayerContinuityAuditor().audit(ledger.records());
                assertThat(report.reconnects()).isGreaterThanOrEqualTo(100L);
                assertThat(report.webSocketRedeliveries()).isPositive();
                assertThat(report.queueRejections()).isZero();
                assertThat(report.authenticationFailures()).isZero();
                assertThat(stub.acceptedConnections()).isGreaterThanOrEqualTo(200L);
                System.out.printf("WS_AUDIT_QA=PASS clients=100 reconnects=%d redeliveries=%d "
                                + "queueRejections=%d authFailures=%d connections=%d ledger=%s%n",
                        report.reconnects(), report.webSocketRedeliveries(), report.queueRejections(),
                        report.authenticationFailures(), stub.acceptedConnections(), ledgerPath);
            } finally {
                clients.forEach(AuthenticatedWebSocketAuditClient::close);
            }
        }
    }

    private static AuthenticatedWebSocketAuditClient client(LocalWebSocketProtocolStub stub,
                                                             WebSocketAuditLedger ledger,
                                                             String token,
                                                             long userId,
                                                             int queueCapacity,
                                                             Duration processingDelay) {
        List<AuthenticatedWebSocketAuditClient.Subscription> subscriptions = List.of(
                new AuthenticatedWebSocketAuditClient.Subscription("orders", "BTC-USDT",
                        "LINEAR_PERPETUAL", PRIVATE_TOPIC),
                new AuthenticatedWebSocketAuditClient.Subscription("mark", "BTC-USDT",
                        "LINEAR_PERPETUAL", PUBLIC_TOPIC));
        AuthenticatedWebSocketAuditClient.Configuration configuration =
                new AuthenticatedWebSocketAuditClient.Configuration("client-" + userId, stub.uri(), token,
                        userId, subscriptions, queueCapacity, Duration.ofMillis(10),
                        Duration.ofSeconds(2), processingDelay);
        return new AuthenticatedWebSocketAuditClient(configuration, HttpClient.newHttpClient(),
                new ObjectMapper(), ledger);
    }

    private Path manualEvidencePath(String filename) {
        String evidenceDirectory = System.getProperty("task6.evidence.dir");
        return evidenceDirectory == null || evidenceDirectory.isBlank()
                ? temporaryDirectory.resolve(filename)
                : Path.of(evidenceDirectory).resolve(filename);
    }
}
