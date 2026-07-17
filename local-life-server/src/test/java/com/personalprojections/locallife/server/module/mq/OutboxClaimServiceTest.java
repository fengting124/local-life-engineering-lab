package com.personalprojections.locallife.server.module.mq;

import com.personalprojections.locallife.server.domain.entity.OutboxMessage;
import com.personalprojections.locallife.server.domain.mapper.OutboxMessageMapper;
import com.personalprojections.locallife.server.module.mq.service.OutboxClaimService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxClaimServiceTest {

    @Mock
    private OutboxMessageMapper mapper;

    @Test
    void claimBatchMarksSelectedRowsWithWorkerLease() {
        OutboxClaimService service = new OutboxClaimService(mapper);
        OutboxMessage message = OutboxMessage.builder()
                .id(10L)
                .eventId("event-10")
                .status("PENDING")
                .build();
        LocalDateTime leaseUntil = LocalDateTime.now().plusMinutes(1);

        when(mapper.selectClaimCandidates(100)).thenReturn(List.of(message));
        when(mapper.markProcessing(10L, "worker-a", leaseUntil)).thenReturn(1);

        List<OutboxMessage> claimed = service.claimBatch(100, "worker-a", leaseUntil);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getStatus()).isEqualTo("PROCESSING");
        assertThat(claimed.get(0).getWorkerId()).isEqualTo("worker-a");
        assertThat(claimed.get(0).getLeaseUntil()).isEqualTo(leaseUntil);
        verify(mapper).selectClaimCandidates(100);
        verify(mapper).markProcessing(10L, "worker-a", leaseUntil);
    }

    @Test
    void claimBatchSkipsRowsThatLostPendingState() {
        OutboxClaimService service = new OutboxClaimService(mapper);
        OutboxMessage message = OutboxMessage.builder().id(11L).status("PENDING").build();
        LocalDateTime leaseUntil = LocalDateTime.now().plusMinutes(1);

        when(mapper.selectClaimCandidates(10)).thenReturn(List.of(message));
        when(mapper.markProcessing(11L, "worker-b", leaseUntil)).thenReturn(0);

        assertThat(service.claimBatch(10, "worker-b", leaseUntil)).isEmpty();
    }

    @Test
    void emptyCandidateBatchDoesNotIssueUpdates() {
        OutboxClaimService service = new OutboxClaimService(mapper);
        when(mapper.selectClaimCandidates(20)).thenReturn(List.of());

        assertThat(service.claimBatch(20, "worker-c", LocalDateTime.now())).isEmpty();
    }
}
