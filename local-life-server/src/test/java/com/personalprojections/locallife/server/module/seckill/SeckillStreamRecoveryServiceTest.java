package com.personalprojections.locallife.server.module.seckill;

import com.personalprojections.locallife.server.domain.entity.SeckillReservation;
import com.personalprojections.locallife.server.domain.mapper.SeckillReservationMapper;
import com.personalprojections.locallife.server.module.mq.service.OutboxService;
import com.personalprojections.locallife.server.module.seckill.service.SeckillStreamRecoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SeckillStreamRecoveryServiceTest {

    @Mock private SeckillReservationMapper reservationMapper;
    @Mock private OutboxService outboxService;

    @Test
    void recoverOnePersistsReservationAndWritesOutbox() {
        SeckillStreamRecoveryService service = newService();

        service.recoverOne(record());

        ArgumentCaptor<SeckillReservation> captor = ArgumentCaptor.forClass(SeckillReservation.class);
        verify(reservationMapper).insert(captor.capture());
        assertThat(captor.getValue().getReservationId()).isEqualTo("100_300_seckill");
        assertThat(captor.getValue().getSessionId()).isEqualTo(100L);
        assertThat(captor.getValue().getCouponTemplateId()).isEqualTo(200L);
        assertThat(captor.getValue().getUserId()).isEqualTo(300L);
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        verify(outboxService).saveToOutbox(any(), eq("100_300_seckill"), any(), any());
    }

    @Test
    void duplicateReservationStillAttemptsOutboxRecovery() {
        SeckillStreamRecoveryService service = newService();
        doThrow(new DuplicateKeyException("duplicate"))
                .when(reservationMapper).insert(any(SeckillReservation.class));

        service.recoverOne(record());

        verify(outboxService).saveToOutbox(any(), eq("100_300_seckill"), any(), any());
    }

    private SeckillStreamRecoveryService newService() {
        return new SeckillStreamRecoveryService(reservationMapper, outboxService, null);
    }

    private Map<String, String> record() {
        return Map.of(
                "eventId", "100_300_seckill",
                "sessionId", "100",
                "couponTemplateId", "200",
                "userId", "300",
                "validDays", "7",
                "reservedAt", "2026-07-17T23:40:00"
        );
    }
}
