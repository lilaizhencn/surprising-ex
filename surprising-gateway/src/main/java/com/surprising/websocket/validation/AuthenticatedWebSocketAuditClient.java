package com.surprising.websocket.validation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class AuthenticatedWebSocketAuditClient implements AutoCloseable {

    private final Configuration configuration;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final WebSocketAuditLedger ledger;
    private final ArrayBlockingQueue<Frame> inbound;
    private final ScheduledExecutorService reconnectExecutor;
    private final Thread processorThread;
    private final Object stateMonitor = new Object();
    private final Map<String, Subscription> subscriptionsByChannel;
    private final Map<String, Long> caughtUpByTopic = new HashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean queueRejected = new AtomicBoolean();
    private volatile WebSocket activeSocket;
    private volatile boolean ready;
    private volatile boolean authenticationFailed;

    public AuthenticatedWebSocketAuditClient(Configuration configuration,
                                             HttpClient httpClient,
                                             ObjectMapper objectMapper,
                                             WebSocketAuditLedger ledger) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        inbound = new ArrayBlockingQueue<>(configuration.inboundQueueCapacity());
        subscriptionsByChannel = subscriptions(configuration.subscriptions());
        reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("ws-audit-reconnect-", 0).factory());
        processorThread = Thread.ofVirtual().name("ws-audit-processor-" + configuration.clientId())
                .start(this::processFrames);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("WebSocket audit client is already started");
        }
        connect();
    }

    public boolean awaitReady(Duration timeout) {
        return await(timeout, () -> ready);
    }

    public boolean awaitAuthenticationFailure(Duration timeout) {
        return await(timeout, () -> authenticationFailed);
    }

    public boolean awaitQueueRejection(Duration timeout) {
        return await(timeout, queueRejected::get);
    }

    public boolean awaitCaughtUp(String topic, long coreSequence, Duration timeout) {
        Objects.requireNonNull(topic, "topic");
        return await(timeout, () -> caughtUpByTopic.getOrDefault(topic, -1L) >= coreSequence);
    }

    public void markCaughtUp(String topic, long coreSequence) {
        Subscription subscription = configuration.subscriptions().stream()
                .filter(candidate -> candidate.expectedTopic().equals(topic))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown subscribed topic " + topic));
        synchronized (stateMonitor) {
            long previous = caughtUpByTopic.getOrDefault(topic, -1L);
            if (coreSequence > previous) {
                caughtUpByTopic.put(topic, coreSequence);
                ledger.append(WebSocketAuditRecord.catchUp(configuration.clientId(),
                        configuration.userId(), subscription.expectedTopic(), coreSequence));
                ledger.flush();
            }
            stateMonitor.notifyAll();
        }
    }

    @Override
    public void close() {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        WebSocket socket = activeSocket;
        if (socket != null) {
            socket.abort();
        }
        processorThread.interrupt();
        reconnectExecutor.shutdownNow();
        signalStateChange();
    }

    private void connect() {
        if (terminal.get()) {
            return;
        }
        ready = false;
        ConnectionListener listener = new ConnectionListener();
        httpClient.newWebSocketBuilder()
                .connectTimeout(configuration.connectTimeout())
                .buildAsync(configuration.uri(), listener)
                .whenComplete((socket, failure) -> {
                    if (failure != null) {
                        listener.ended("connect failed: " + message(failure));
                    }
                });
    }

    private void processFrames() {
        while (!terminal.get()) {
            try {
                Frame frame = inbound.take();
                process(frame);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                failAuthentication("invalid protocol frame: " + message(ex));
            }
        }
    }

    private void process(Frame frame) {
        JsonNode root = objectMapper.readTree(frame.payload());
        String op = text(root, "op");
        if ("event".equals(op) && !configuration.processingDelay().isZero()) {
            try {
                Thread.sleep(configuration.processingDelay());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        switch (op) {
            case "authenticated" -> authenticated(frame, root);
            case "subscribed" -> subscribed(frame.listener());
            case "event" -> event(frame.payload(), root);
            case "caught_up" -> caughtUp(root);
            case "error" -> failAuthentication(text(root, "error"));
            default -> { }
        }
    }

    private void authenticated(Frame frame, JsonNode root) {
        long authenticatedUser = required(root, "userId").asLong();
        if (authenticatedUser != configuration.userId()) {
            failAuthentication("authenticated user mismatch");
            return;
        }
        frame.listener().authenticated = true;
        for (Subscription subscription : configuration.subscriptions()) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("op", "subscribe");
            command.put("id", configuration.clientId() + '-' + subscription.channel());
            command.put("channel", subscription.channel());
            command.put("symbol", subscription.symbol());
            command.put("productLine", subscription.productLine());
            frame.socket().sendText(objectMapper.writeValueAsString(command), true);
        }
    }

    private void subscribed(ConnectionListener listener) {
        listener.subscriptionAcks++;
        if (listener.subscriptionAcks == configuration.subscriptions().size()) {
            ready = true;
            signalStateChange();
        }
    }

    private void event(String payload, JsonNode root) {
        String channel = text(root, "channel");
        Subscription subscription = requireSubscription(channel);
        JsonNode data = required(root, "data");
        String eventId = text(data, "eventId");
        JsonNode sequenceNode = data.get("exportSequence");
        if (sequenceNode == null) {
            sequenceNode = required(data, "coreSequence");
        }
        long sentAt = Instant.parse(text(root, "eventTime")).toEpochMilli();
        long receivedAt = System.currentTimeMillis();
        ledger.append(WebSocketAuditRecord.event(WebSocketAuditRecord.Layer.WEBSOCKET,
                configuration.clientId(), eventId, sequenceNode.asLong(), subscription.expectedTopic(),
                configuration.userId(), sentAt, receivedAt, WebSocketAuditRecord.sha256(payload),
                null, null, null));
    }

    private void caughtUp(JsonNode root) {
        Subscription subscription = requireSubscription(text(root, "channel"));
        markCaughtUp(subscription.expectedTopic(), required(root, "coreSequence").asLong());
    }

    private void failAuthentication(String detail) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        authenticationFailed = true;
        ledger.append(WebSocketAuditRecord.signal(WebSocketAuditRecord.Type.AUTH_FAILURE,
                configuration.clientId(), configuration.userId(), detail));
        ledger.flush();
        WebSocket socket = activeSocket;
        if (socket != null) {
            socket.abort();
        }
        reconnectExecutor.shutdownNow();
        signalStateChange();
    }

    private boolean enqueue(Frame frame) {
        if (inbound.offer(frame)) {
            return true;
        }
        synchronized (stateMonitor) {
            if (!queueRejected.get()) {
                ledger.append(WebSocketAuditRecord.signal(WebSocketAuditRecord.Type.QUEUE_REJECTION,
                        configuration.clientId(), configuration.userId(),
                        "inboundQueueCapacity=" + configuration.inboundQueueCapacity()));
                ledger.flush();
                queueRejected.set(true);
                stateMonitor.notifyAll();
            }
        }
        terminal.set(true);
        frame.socket().abort();
        reconnectExecutor.shutdownNow();
        signalStateChange();
        return false;
    }

    private boolean await(Duration timeout, BooleanSupplier condition) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (stateMonitor) {
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(stateMonitor, remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void signalStateChange() {
        synchronized (stateMonitor) {
            stateMonitor.notifyAll();
        }
    }

    private Subscription requireSubscription(String channel) {
        Subscription subscription = subscriptionsByChannel.get(channel);
        if (subscription == null) {
            throw new IllegalArgumentException("unsubscribed WebSocket channel " + channel);
        }
        return subscription;
    }

    private static Map<String, Subscription> subscriptions(List<Subscription> subscriptions) {
        Map<String, Subscription> indexed = new HashMap<>();
        for (Subscription subscription : subscriptions) {
            if (indexed.put(subscription.channel(), subscription) != null) {
                throw new IllegalArgumentException("duplicate WebSocket channel " + subscription.channel());
            }
        }
        return Map.copyOf(indexed);
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("missing WebSocket field " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        String value = required(node, field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("blank WebSocket field " + field);
        }
        return value;
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    public record Configuration(
            String clientId,
            URI uri,
            String accessToken,
            long userId,
            List<Subscription> subscriptions,
            int inboundQueueCapacity,
            Duration reconnectDelay,
            Duration connectTimeout,
            Duration processingDelay) {

        public Configuration {
            requireText(clientId, "clientId");
            Objects.requireNonNull(uri, "uri");
            if (!"ws".equalsIgnoreCase(uri.getScheme()) && !"wss".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("WebSocket URI must use ws or wss");
            }
            requireText(accessToken, "accessToken");
            if (userId <= 0L) {
                throw new IllegalArgumentException("userId must be positive");
            }
            subscriptions = List.copyOf(Objects.requireNonNull(subscriptions, "subscriptions"));
            if (subscriptions.isEmpty()) {
                throw new IllegalArgumentException("at least one subscription is required");
            }
            if (inboundQueueCapacity <= 0) {
                throw new IllegalArgumentException("inboundQueueCapacity must be positive");
            }
            requireNonNegative(reconnectDelay, "reconnectDelay");
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
                throw new IllegalArgumentException("connectTimeout must be positive");
            }
            requireNonNegative(processingDelay, "processingDelay");
        }
    }

    public record Subscription(String channel, String symbol, String productLine, String expectedTopic) {
        public Subscription {
            requireText(channel, "channel");
            requireText(symbol, "symbol");
            requireText(productLine, "productLine");
            requireText(expectedTopic, "expectedTopic");
        }
    }

    private final class ConnectionListener implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile boolean authenticated;
        private int subscriptionAcks;

        @Override
        public void onOpen(WebSocket webSocket) {
            activeSocket = webSocket;
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("op", "authenticate");
            command.put("id", configuration.clientId() + "-auth");
            command.put("token", configuration.accessToken());
            webSocket.sendText(objectMapper.writeValueAsString(command), true);
            webSocket.request(1L);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                String payload = fragments.toString();
                fragments.setLength(0);
                if (!enqueue(new Frame(webSocket, this, payload))) {
                    return null;
                }
            }
            webSocket.request(1L);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ended("close status=" + statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ended("transport error: " + message(error));
        }

        private void ended(String detail) {
            if (!ended.compareAndSet(false, true) || terminal.get()) {
                return;
            }
            ledger.append(WebSocketAuditRecord.signal(WebSocketAuditRecord.Type.RECONNECT,
                    configuration.clientId(), configuration.userId(), detail));
            reconnectExecutor.schedule(AuthenticatedWebSocketAuditClient.this::connect,
                    configuration.reconnectDelay().toNanos(), TimeUnit.NANOSECONDS);
            signalStateChange();
        }
    }

    private record Frame(WebSocket socket, ConnectionListener listener, String payload) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
