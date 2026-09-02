package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OutboxRelaySchedulerTest {

    private final OutboxRelayService relayService = mock(OutboxRelayService.class);
    private final OutboxRelayScheduler scheduler = new OutboxRelayScheduler(relayService);

    @Test
    void shouldInvokeRelayAndKeepFuturePollingAliveAfterBatchFailure() {
        doThrow(new IllegalStateException("database unavailable"))
                .doReturn(OutboxRelayBatchResult.empty())
                .when(relayService)
                .relayDueEvents();

        assertThatCode(scheduler::relay).doesNotThrowAnyException();
        assertThatCode(scheduler::relay).doesNotThrowAnyException();
        verify(relayService, times(2)).relayDueEvents();
    }
}
