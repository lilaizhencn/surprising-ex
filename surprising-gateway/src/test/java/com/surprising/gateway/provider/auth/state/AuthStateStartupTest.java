package com.surprising.gateway.provider.auth.state;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

class AuthStateStartupTest {
    @Test void failedCommittedConfigurationIsRetriedWithoutAnyIdentityRequestDatabaseFallback() {
        var jdbc=mock(JdbcTemplate.class);var fixture=new AuthStateFixture();
        var sync=new AuthStateSync(jdbc,fixture.state,fixture.properties,com.surprising.gateway.provider.auth.state.AuthStateFixture.passthroughTransactions());
        fixture.backend.down=true;
        assertThatThrownBy(()->sync.apiKeyChanged("key")).isInstanceOf(AuthStateUnavailableException.class);
        fixture.backend.down=false;
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(7)).untilAsserted(()->{
            sync.retryPendingConfiguration();
            assertThat(org.mockito.Mockito.mockingDetails(jdbc).getInvocations()).hasSizeGreaterThanOrEqualTo(2);
        });
        assertThat(fixture.state.apiKey("key")).isNull();
    }
    @Test void startupReadsOnlyPersistentConfigurationNeverOldLoginSessions() {
        var jdbc=mock(JdbcTemplate.class);var fixture=new AuthStateFixture();
        new AuthStateSync(jdbc,fixture.state,fixture.properties,com.surprising.gateway.provider.auth.state.AuthStateFixture.passthroughTransactions()).syncPersistentState();
        for(var invocation:mockingDetails(jdbc).getInvocations())
            for(Object argument:invocation.getArguments())
                if(argument instanceof String sql) assertThat(sql).doesNotContain("refresh_sessions","auth:session");
        assertThat(fixture.state.sessionExists(123)).isFalse();
    }
    @Test void rolledBackUserChangeCannotPublishUncommittedAuthConfiguration() {
        var jdbc=mock(JdbcTemplate.class);var fixture=new AuthStateFixture();
        TransactionSynchronizationManager.initSynchronization();
        try {
            new AuthStateSync(jdbc,fixture.state,fixture.properties,com.surprising.gateway.provider.auth.state.AuthStateFixture.passthroughTransactions()).reloadUser(7);
            verifyNoInteractions(jdbc);
            for(var callback:TransactionSynchronizationManager.getSynchronizations()) callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        verifyNoInteractions(jdbc);
        assertThat(fixture.state.user(7)).isNull();
    }
}
