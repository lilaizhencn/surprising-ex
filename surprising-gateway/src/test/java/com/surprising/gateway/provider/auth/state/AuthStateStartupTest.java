package com.surprising.gateway.provider.auth.state;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

class AuthStateStartupTest {
    @Test void startupReadsOnlyPersistentConfigurationNeverOldLoginSessions() {
        var jdbc=mock(JdbcTemplate.class);var fixture=new AuthStateFixture();
        new AuthStateSync(jdbc,fixture.state,fixture.properties).syncPersistentState();
        for(var invocation:mockingDetails(jdbc).getInvocations())
            for(Object argument:invocation.getArguments())
                if(argument instanceof String sql) assertThat(sql).doesNotContain("refresh_sessions","auth:session");
        assertThat(fixture.state.sessionExists(123)).isFalse();
    }
    @Test void rolledBackUserChangeCannotPublishUncommittedAuthConfiguration() {
        var jdbc=mock(JdbcTemplate.class);var fixture=new AuthStateFixture();
        TransactionSynchronizationManager.initSynchronization();
        try {
            new AuthStateSync(jdbc,fixture.state,fixture.properties).reloadUser(7);
            verifyNoInteractions(jdbc);
            for(var callback:TransactionSynchronizationManager.getSynchronizations()) callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        verifyNoInteractions(jdbc);
        assertThat(fixture.state.user(7)).isNull();
    }
}
