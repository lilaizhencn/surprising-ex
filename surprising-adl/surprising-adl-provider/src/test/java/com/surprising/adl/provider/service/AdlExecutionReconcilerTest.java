package com.surprising.adl.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.adl.provider.model.AdlSagaState;
import com.surprising.adl.provider.repository.AdlEventRepository;
import com.surprising.adl.provider.repository.AdlExecutionSagaRepository;
import com.surprising.adl.provider.repository.AdlPendingExecutionRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

class AdlExecutionReconcilerTest {

    @Test
    void completesSagaOnlyAfterEventIsPersisted() {
        AdlPendingExecutionRepository pendingRepository = mock(AdlPendingExecutionRepository.class);
        AdlExecutionSagaRepository sagaRepository = mock(AdlExecutionSagaRepository.class);
        AdlEventRepository eventRepository = mock(AdlEventRepository.class);
        AdlExecutionPersistenceService persistenceService = mock(AdlExecutionPersistenceService.class);
        AdlSagaState saga = saga(null, null, AccountCommandStatus.APPLIED.name(), null,
                "{\"remainingDeficitUnits\":123}");
        when(pendingRepository.lock(500)).thenReturn(List.of(saga));
        AdlExecutionReconciler reconciler = new AdlExecutionReconciler(
                pendingRepository, sagaRepository, eventRepository, persistenceService, new ObjectMapper());

        reconciler.reconcile();

        InOrder completionOrder = org.mockito.Mockito.inOrder(eventRepository, sagaRepository);
        completionOrder.verify(eventRepository).insert(eq(saga), eq(123L), any(Instant.class));
        completionOrder.verify(sagaRepository).complete(eq(saga.executionId()), any(Instant.class));
        verify(persistenceService, never()).beginRelease(any(), any());
    }

    @Test
    void startsCompensationAfterTargetRejection() {
        AdlPendingExecutionRepository pendingRepository = mock(AdlPendingExecutionRepository.class);
        AdlExecutionSagaRepository sagaRepository = mock(AdlExecutionSagaRepository.class);
        AdlEventRepository eventRepository = mock(AdlEventRepository.class);
        AdlExecutionPersistenceService persistenceService = mock(AdlExecutionPersistenceService.class);
        AdlSagaState saga = saga(
                AccountCommandStatus.APPLIED.name(), AccountCommandStatus.REJECTED.name(), null, null, null);
        when(pendingRepository.lock(500)).thenReturn(List.of(saga));
        AdlExecutionReconciler reconciler = new AdlExecutionReconciler(
                pendingRepository, sagaRepository, eventRepository, persistenceService, new ObjectMapper());

        reconciler.reconcile();

        verify(persistenceService).beginRelease(eq(saga), any(Instant.class));
        verify(eventRepository, never()).insert(any(), anyLong(), any());
        verify(sagaRepository, never()).complete(eq(saga.executionId()), any());
    }

    private static AdlSagaState saga(String reserveStatus,
                                     String targetStatus,
                                     String finalizeStatus,
                                     String releaseStatus,
                                     String finalizeResult) {
        return new AdlSagaState(
                7001L,
                "LINEAR_PERPETUAL",
                "USDT_PERPETUAL",
                11L,
                22L,
                "USDT",
                "BTC-USDT",
                "LONG",
                "NET",
                4L,
                60_000L,
                61_000L,
                1_000L,
                700L,
                600L,
                900_000L,
                "reserve",
                "target",
                "finalize",
                null,
                "PENDING",
                reserveStatus,
                targetStatus,
                finalizeStatus,
                releaseStatus,
                finalizeResult,
                "TARGET_REJECTED",
                "目标账户拒绝执行");
    }
}
