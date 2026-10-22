package com.surprising.gateway.provider.product;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.account.provider.service.AccountAeronGateway;
import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.gateway.provider.config.GatewayProductsProperties;
import com.surprising.gateway.provider.model.GatewayProductSetting;
import com.surprising.gateway.provider.service.GatewayProductSettings;
import com.surprising.product.api.ProductLine;
import io.aeron.Publication;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GatewayProductServicesTest {
    private static final String CONNECT_TIMEOUT = "surprising.aeron.client.connect-timeout-ms";
    private final ConfigurableApplicationContext parent = mock(ConfigurableApplicationContext.class);
    private final GatewayProductSettings settings = mock(GatewayProductSettings.class);
    private final GatewayProductsProperties configuration = new GatewayProductsProperties();
    private final AccountAeronGateway account = mock(AccountAeronGateway.class);
    private final KafkaListenerEndpointRegistry listeners = mock(KafkaListenerEndpointRegistry.class);
    private GatewayProductServices products;
    private MockedConstruction<AnnotationConfigApplicationContext> contexts;
    private MockedConstruction<RequestMappingHandlerMapping> mappings;
    private String previousConnectTimeout;

    @BeforeEach void setUp() {
        previousConnectTimeout = System.getProperty(CONNECT_TIMEOUT);
        System.setProperty(CONNECT_TIMEOUT, "500");
        when(parent.getId()).thenReturn("gateway-test");
        when(parent.getEnvironment()).thenReturn(new StandardEnvironment());
        when(settings.list()).thenReturn(List.of(new GatewayProductSetting(
                ProductLine.SPOT, true, 1, "test", "test", Instant.EPOCH)));
        configuration.getCores().put(ProductLine.SPOT, new GatewayProductsProperties.Core());
        products = new GatewayProductServices(parent, configuration, settings);
        when(listeners.getListenerContainers()).thenReturn(List.of());
        contexts = mockConstruction(AnnotationConfigApplicationContext.class, (child, ignored) -> {
            when(child.getEnvironment()).thenReturn(new StandardEnvironment());
            when(child.isActive()).thenReturn(true);
            when(child.getBean(AccountAeronGateway.class)).thenReturn(account);
            when(child.getBean(KafkaListenerEndpointRegistry.class)).thenReturn(listeners);
        });
        mappings = mockConstruction(RequestMappingHandlerMapping.class);
    }

    @AfterEach void tearDown() {
        mappings.close();
        contexts.close();
        if (previousConnectTimeout == null) System.clearProperty(CONNECT_TIMEOUT);
        else System.setProperty(CONNECT_TIMEOUT, previousConnectTimeout);
        Thread.interrupted();
    }

    @Test void reusesOneContextAndPoolAfterTheFirstReadinessQueryIsNotConnected() {
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenThrow(new CompletionException(notConnected())).thenReturn(new CoreResponse(ResponseStatus.OK, 0));

        products.reload();

        assertThat(products.enabled()).containsExactly(ProductLine.SPOT);
        assertThat(contexts.constructed()).hasSize(1);
        verify(child(), times(1)).refresh();
        verify(child(), never()).close();
        verify(account, times(2)).query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class));
        assertThat(configuration.getCores().get(ProductLine.SPOT).getResponseTimeout()).isEqualTo(Duration.ofSeconds(5));
        products.close();
        verify(child(), times(1)).close();
    }

    @Test void permanentlyDisconnectedCoreExhaustsTheWindowAndClosesOnceWithTheOriginalFailure() {
        System.setProperty(CONNECT_TIMEOUT, "40");
        var failure = new CompletionException(notConnected());
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenThrow(failure);

        assertTimeout(Duration.ofSeconds(2), () -> assertThatThrownBy(this::start).isSameAs(failure));

        assertThat(contexts.constructed()).hasSize(1);
        verify(child(), times(1)).close();
        verify(account, atLeastOnce()).query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class));
        assertThat(products.enabled()).isEmpty();
    }

    @Test void aProbeStartedWithinTheWindowMayCompleteWithItsOriginalResponseDeadline() {
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenThrow(notConnected()).thenAnswer(ignored -> {
                    Thread.sleep(550);
                    return new CoreResponse(ResponseStatus.OK, 0);
                });

        products.reload();

        assertThat(products.enabled()).containsExactly(ProductLine.SPOT);
        verify(account, times(2)).query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class));
        products.close();
        verify(child(), times(1)).close();
    }

    @ParameterizedTest
    @EnumSource(value = CoreCommandOutcome.NotAcceptedReason.class, names = "NOT_CONNECTED", mode = EnumSource.Mode.EXCLUDE)
    void otherAdmissionFailuresAreNotRetried(CoreCommandOutcome.NotAcceptedReason reason) {
        var failure = new CoreCommandOutcome.NotAcceptedException(new CoreCommandOutcome.NotAccepted(reason, -1));
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenThrow(new CompletionException(failure));

        assertThatThrownBy(this::start).isInstanceOf(CompletionException.class).hasCause(failure);

        verify(account, times(1)).query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class));
        verify(child(), times(1)).close();
    }

    @Test void admittedUnknownOutcomeIsNotTreatedAsAnUnfinishedConnection() {
        var failure = new ResultUnknownException(UUID.randomUUID(), "readiness response unavailable");
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class))).thenThrow(failure);

        assertThatThrownBy(this::start).isSameAs(failure);

        verify(account, times(1)).query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class));
        verify(child(), times(1)).close();
    }

    @Test void coreRejectionFailsWithoutRetry() {
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenReturn(new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND, 0));

        assertThatThrownBy(this::start).hasMessageContaining("Core readiness rejected: INVALID_COMMAND");

        verify(account, times(1)).query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class));
        verify(child(), times(1)).close();
    }

    @Test void invalidConnectionBudgetFailsAndClosesWithoutAProbe() {
        System.setProperty(CONNECT_TIMEOUT, "0");

        assertThatThrownBy(this::start).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connect timeout");

        verifyNoInteractions(account);
        verify(child(), times(1)).close();
    }

    @Test void startupFailureKeepsItsIdentityAndSuppressedCleanupFailure() {
        var failure = new IllegalStateException("invalid connection configuration");
        var cleanup = new IllegalStateException("context cleanup failed");
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenAnswer(ignored -> { doThrow(cleanup).when(child()).close(); throw failure; });

        assertThatThrownBy(this::start).isSameAs(failure).hasSuppressedException(cleanup);

        verify(account, times(1)).query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class));
        verify(child(), times(1)).close();
    }

    @Test void cleanupCannotSelfSuppressTheOriginalStartupFailure() {
        var failure = new IllegalStateException("same cached failure");
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenAnswer(ignored -> { doThrow(failure).when(child()).close(); throw failure; });

        assertThatThrownBy(this::start).isSameAs(failure).hasNoSuppressedExceptions();

        verify(child(), times(1)).close();
    }

    @Test void interruptionStopsRetryAndPreservesTheInterruptFlag() {
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenAnswer(ignored -> { Thread.currentThread().interrupt(); throw notConnected(); });

        assertThatThrownBy(this::start).hasMessage("Product activation interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(account, times(1)).query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class));
        verify(child(), times(1)).close();
    }

    @Test void concurrentCloseCancelsActivationBeforeAnotherProbeAndClosesTheChildOnce() throws Exception {
        var closing = new AtomicReference<Thread>();
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenAnswer(ignored -> {
                    Thread closer = Thread.ofPlatform().name("gateway-product-test-close").unstarted(products::close);
                    closing.set(closer);
                    closer.start();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (!Boolean.TRUE.equals(ReflectionTestUtils.getField(products, "closed"))
                            && System.nanoTime() < deadline) Thread.sleep(1);
                    assertThat(ReflectionTestUtils.getField(products, "closed")).isEqualTo(true);
                    throw notConnected();
                });
        try {
            products.reload();
            assertThat(products.enabled()).isEmpty();
            verify(account, times(1)).query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class));
            verify(child(), times(1)).close();
        } finally {
            if (closing.get() != null) closing.get().join(2000);
        }
        assertThat(closing.get().isAlive()).isFalse();
    }

    @Test void closeAfterCoreReadinessPreventsRuntimePublication() {
        when(account.query(eq(CoreMessageType.LANE_METRICS_QUERY), any(UUID.class), any(byte[].class)))
                .thenReturn(new CoreResponse(ResponseStatus.OK, 0));
        when(listeners.getListenerContainers()).thenAnswer(ignored -> { products.close(); return List.of(); });

        products.reload();

        assertThat(products.enabled()).isEmpty();
        verify(child(), times(1)).close();
    }

    @Test void closedGatewayDoesNotConstructAProductContext() {
        products.close();

        products.reload();

        assertThat(contexts.constructed()).isEmpty();
        verifyNoInteractions(account);
    }

    private void start() {
        ReflectionTestUtils.invokeMethod(products, "start", ProductLine.SPOT);
    }

    private AnnotationConfigApplicationContext child() {
        return contexts.constructed().getFirst();
    }

    private static CoreCommandOutcome.NotAcceptedException notConnected() {
        return new CoreCommandOutcome.NotAcceptedException(CoreCommandOutcome.notAccepted(Publication.NOT_CONNECTED));
    }
}
