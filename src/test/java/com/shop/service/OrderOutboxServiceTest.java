package com.shop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.dto.OrderDTO;
import com.shop.mapper.EventOutboxMapper;
import com.shop.model.EventOutbox;
import com.shop.model.EventOutboxStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderOutboxServiceTest {

    @Mock
    private EventOutboxMapper eventOutboxMapper;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void saveOrderCreatedEventShouldInsertPendingOutboxRecord() {
        OrderOutboxService service = new OrderOutboxService(
                eventOutboxMapper,
                objectMapper,
                orderEventPublisher,
                true,
                20,
                5
        );

        OrderDTO order = OrderDTO.builder()
                .id("order_001")
                .orderNo("SO001")
                .userId("user_001")
                .totalAmount(new BigDecimal("99.90"))
                .totalQuantity(1)
                .status("CREATED")
                .build();

        service.saveOrderCreatedEvent(order);

        ArgumentCaptor<EventOutbox> captor = ArgumentCaptor.forClass(EventOutbox.class);
        verify(eventOutboxMapper).insert(captor.capture());
        EventOutbox outbox = captor.getValue();
        assertEquals("ORDER", outbox.getAggregateType());
        assertEquals("order_001", outbox.getAggregateId());
        assertEquals("ORDER_CREATED", outbox.getEventType());
        assertEquals(EventOutboxStatus.PENDING.name(), outbox.getStatus());
        assertTrue(outbox.getPayload().contains("SO001"));
    }
}
