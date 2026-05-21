package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.RequestContext;
import com.shop.dto.OrderDTO;
import com.shop.event.OrderCreatedEvent;
import com.shop.mapper.EventOutboxMapper;
import com.shop.model.EventOutbox;
import com.shop.model.EventOutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class OrderOutboxService {

    private final EventOutboxMapper eventOutboxMapper;
    private final ObjectMapper objectMapper;
    private final OrderEventPublisher orderEventPublisher;
    private final boolean enabled;
    private final int batchSize;
    private final int maxRetries;

    public OrderOutboxService(EventOutboxMapper eventOutboxMapper,
                              ObjectMapper objectMapper,
                              OrderEventPublisher orderEventPublisher,
                              @Value("${app.outbox.enabled:true}") boolean enabled,
                              @Value("${app.outbox.batch-size:20}") int batchSize,
                              @Value("${app.outbox.max-retries:5}") int maxRetries) {
        this.eventOutboxMapper = eventOutboxMapper;
        this.objectMapper = objectMapper;
        this.orderEventPublisher = orderEventPublisher;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    public void saveOrderCreatedEvent(OrderDTO order) {
        if (!enabled) {
            orderEventPublisher.publishOrderCreated(order);
            return;
        }
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("ORDER_CREATED")
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .totalQuantity(order.getTotalQuantity())
                .status(order.getStatus())
                .occurredAt(LocalDateTime.now())
                .traceId(RequestContext.getTraceId())
                .build();

        EventOutbox outbox = EventOutbox.builder()
                .aggregateType("ORDER")
                .aggregateId(order.getId())
                .eventType(event.getEventType())
                .payload(toJson(event))
                .status(EventOutboxStatus.PENDING.name())
                .retryCount(0)
                .build();
        eventOutboxMapper.insert(outbox);
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-delay-ms:5000}")
    @Transactional(rollbackFor = Exception.class)
    public void relayPendingEvents() {
        if (!enabled) {
            return;
        }
        LambdaQueryWrapper<EventOutbox> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EventOutbox::getStatus, EventOutboxStatus.PENDING.name())
                .lt(EventOutbox::getRetryCount, maxRetries)
                .orderByAsc(EventOutbox::getCreateTime)
                .last("limit " + batchSize);

        List<EventOutbox> events = eventOutboxMapper.selectList(wrapper);
        for (EventOutbox event : events) {
            relay(event);
        }
    }

    private void relay(EventOutbox outbox) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(outbox.getPayload(), OrderCreatedEvent.class);
            orderEventPublisher.publish(event);
            outbox.setStatus(EventOutboxStatus.SENT.name());
            outbox.setLastError(null);
        } catch (Exception ex) {
            outbox.setRetryCount(outbox.getRetryCount() + 1);
            outbox.setLastError(trimError(ex.getMessage()));
            if (outbox.getRetryCount() >= maxRetries) {
                outbox.setStatus(EventOutboxStatus.FAILED.name());
            }
            log.warn("Failed to relay outbox event {}: {}", outbox.getId(), ex.getMessage());
        }
        eventOutboxMapper.updateById(outbox);
    }

    private String toJson(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize order event", ex);
        }
    }

    private String trimError(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
