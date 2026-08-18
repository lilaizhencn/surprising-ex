package com.surprising.trading.trigger.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.trading.trigger.service.TriggerOrderService;
import org.junit.jupiter.api.Test;

class TriggerOrderMaintenanceTaskTest {

    @Test
    void delegatesMaintenanceToAeronCoreService() {
        TriggerOrderService service = mock(TriggerOrderService.class);
        TriggerOrderMaintenanceTask task = new TriggerOrderMaintenanceTask(service);

        task.maintainTriggerOrders();

        verify(service).maintenance();
    }
}
